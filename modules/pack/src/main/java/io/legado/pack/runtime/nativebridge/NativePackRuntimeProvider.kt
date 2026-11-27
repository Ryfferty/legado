package io.legado.pack.runtime.nativebridge

import io.legado.pack.api.*
import io.legado.pack.runtime.*
import java.util.concurrent.atomic.AtomicReference

class NativePackRuntimeProvider(
    private val defaultMode: NativeLoadMode,
    /**
     * Bound for waiting on the asynchronous native close. Mirrors
     * [PackExecutionPolicy.closeTimeoutMs] so a healthy native library
     * reports a released close within the host close budget instead of an
     * immediate bogus "resident" result.
     */
    private val closeTimeoutMs: Long = 2_000L,
) : PackRuntimeProvider {

    init {
        require(closeTimeoutMs > 0L) { "closeTimeoutMs must be positive" }
    }

    override val kind = ComponentKind.NATIVE
    override val trust = RuntimeTrust.TRUSTED_IN_PROCESS
    override val capabilities = RuntimeCapabilities(false, false, IsolationLevel.NONE, IsolationLevel.NONE, IsolationLevel.NONE)

    override fun probe(component: ComponentSpec, platform: PackPlatform): RuntimeAvailability =
        if (component is ComponentSpec.Native) NativeLoadPreflight.check(component, platform, defaultMode)
        else RuntimeAvailability.Unavailable(PackDiagnostic(PackErrorCode.UNSUPPORTED, "native provider received ${component.kind.wire}"))

    override fun load(ref: PackRef, component: ComponentSpec, context: PackContext, call: PackCallContext): PackInstance {
        if (component !is ComponentSpec.Native) runtimeFailure(PackErrorCode.UNSUPPORTED, "not a native component")
        verifyBinding(ref, component, context, call)
        val availability = probe(component, context.platform)
        if (availability is RuntimeAvailability.Unavailable) throw PackException(availability.failure)
        val mode = component.requestedMode ?: defaultMode
        // MEMFD gate from the injected platform (never a direct Build read:
        // the framework module does not read android.os.Build).
        if (mode == NativeLoadMode.MEMFD && context.platform.sdkInt < 30) {
            runtimeFailure(PackErrorCode.NATIVE_PLATFORM, "MEMFD requires actual Android API 30+; no fallback")
        }
        val file = ReadOnlyCode.stage(context, component.path, "so", call)
        NativeBridge.ensureLoaded()
        call.checkActive()
        val handle = try {
            NativeBridge.open(file.absolutePath, PackNativeAbiV1.modeOf(mode), component.abiVersion, context.platform.sdkInt,
                NativeCallState(call), NativeLogSink(context), MAX_OUTPUT_BYTES)
        } catch (e: NativeBridgeFailure) {
            throw platformFailure(component, context.platform, mode, e)
        }
        val instance = NativeInstance(ref, component.id, context, handle, closeTimeoutMs)
        return try {
            call.checkActive()
            instance
        } catch (e: Exception) {
            instance.close()
            throw e
        }
    }

    private fun platformFailure(component: ComponentSpec.Native, platform: PackPlatform, mode: NativeLoadMode, failure: Throwable): PackException =
        PackException(PackDiagnostic(
            PackErrorCode.NATIVE_PLATFORM, "native ${mode.wire} load failed: ${failure.message?.take(2048)}",
            component.path.value, mapOf(
                "mode" to mode.wire, "abi" to component.abi.wire, "sdkInt" to platform.sdkInt.toString(),
                "pageSizeBytes" to platform.pageSizeBytes.toString(), "deviceValidation" to "failed-on-this-device",
                "fallback" to "forbidden", "trust" to "in-process",
                // Typed residency protocol: the bridge reports mapped-but-failed
                // loads with NativeResidentFailure; the manager marks the pack
                // NEEDS_RESTART so disable/uninstall report the mapping.
                DETAIL_RESIDENT_AFTER_FAILURE to (failure is NativeResidentFailure).toString(),
            ),
        ), failure)

    private class NativeInstance(
        override val ref: PackRef,
        override val componentId: String,
        private val context: PackContext,
        private val handle: Long,
        private val closeTimeoutMs: Long,
    ) : PackInstance {
        private val gate = Any()
        @Volatile private var closeRequested = false
        override val closed get() = closeRequested
        private var active = 0
        private var closeTask: AtomicReference<CloseOutcome?>? = null

        override fun invoke(command: String, argJson: String, call: PackCallContext): String {
            verifyCommand(context, componentId, command, call)
            RuntimeJson.parse(argJson, call)
            synchronized(gate) {
                if (closed) runtimeFailure(PackErrorCode.PACK_DISABLED, "native instance is closed")
                if (active != 0) runtimeFailure(PackErrorCode.LOAD_FAILED, "concurrent native invocation is not admitted")
                active++
            }
            try {
                call.checkActive()
                val bytes = try {
                    NativeBridge.invoke(handle, command, argJson.ifEmpty { "null" }.toByteArray(Charsets.UTF_8), NativeCallState(call))
                } catch (failure: NativeBridgeFailure) {
                    call.checkActive()
                    runtimeFailure(PackErrorCode.LOAD_FAILED, "native invoke failed: ${failure.message?.take(2048)}", failure)
                }
                call.checkActive()
                val json = decodeUtf8(bytes)
                RuntimeJson.parse(json, call)
                return json
            } finally {
                synchronized(gate) {
                    active--
                    if (closed) scheduleClose()
                }
            }
        }

        override fun close(): CloseOutcome {
            val task: AtomicReference<CloseOutcome?>?
            synchronized(gate) {
                closeRequested = true
                if (active == 0) scheduleClose()
                task = closeTask
            }
            // Bounded wait for the asynchronous close instead of an immediate
            // (almost certainly null) get(): a healthy native library closes
            // within the budget and must report released; resident is only
            // reported when the wait really expires or a call still holds the
            // instance.
            val outcome = awaitCloseOutcome(task)
            return outcome ?: CloseOutcome.resident(
                "native close did not finish within $closeTimeoutMs ms or a call is still executing; library remains mapped",
            )
        }

        private fun awaitCloseOutcome(task: AtomicReference<CloseOutcome?>?): CloseOutcome? {
            if (task == null) return null
            val deadlineNanos = System.nanoTime() + closeTimeoutMs * 1_000_000L
            while (true) {
                task.get()?.let { return it }
                if (System.nanoTime() - deadlineNanos >= 0L) return null
                try {
                    Thread.sleep(CLOSE_POLL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return task.get()
                }
            }
        }

        private fun scheduleClose() {
            if (closeTask != null) return
            closeTask = TrustedCloseWorker.submit {
                try {
                    when (NativeBridge.close(handle)) {
                        PackNativeAbiV1.CLOSE_RELEASED -> CloseOutcome.released()
                        else -> CloseOutcome.resident("native ABI reports retained calls/threads; dlclose was not performed")
                    }
                } catch (failure: NativeBridgeFailure) {
                    CloseOutcome.failed(PackDiagnostic(PackErrorCode.NEEDS_RESTART, "native close failed: ${failure.message?.take(1024)}"))
                }
            }
        }
    }

    companion object {
        private const val MAX_OUTPUT_BYTES = PackJsProtocol.MAX_JSON_CHARS * 4
        private const val CLOSE_POLL_MS = 5L
    }
}

/** JNI-only objects. These classes and their method names require consumer keep rules. */
internal class NativeCallState(private val call: PackCallContext) {
    fun deadlineNanos(): Long = call.deadlineNanos
    fun nowNanos(): Long = System.nanoTime()
    fun checkActiveState(): Int = when {
        call.cancellation.isCancelled -> PackNativeAbiV1.CALL_CANCELLED
        call.isExpired() -> PackNativeAbiV1.CALL_TIMEOUT
        else -> PackNativeAbiV1.CALL_ACTIVE
    }
}

internal class NativeLogSink(private val context: PackContext) {
    fun log(level: Int, message: ByteArray) {
        if (PackCapability.LOG !in context.manifest.capabilities) return
        val severity = when (level) {
            0 -> PackLogLevel.DEBUG
            1 -> PackLogLevel.INFO
            2 -> PackLogLevel.WARN
            else -> PackLogLevel.ERROR
        }
        context.logger.log(severity, decodeUtf8(message))
    }
}

// Open for the resident subtype thrown by C++ (see NativeResidentFailure).
internal open class NativeBridgeFailure(message: String) : RuntimeException(message)

/**
 * The JNI bridge throws this subtype when a load failed AFTER dlopen mapped
 * the library: the mapping (and the per-path failure latch) stays in the
 * native registry until a process restart, so the manager must record
 * residency instead of letting the unload path report a clean release.
 */
internal class NativeResidentFailure(message: String) : NativeBridgeFailure(message)

internal object NativeBridge {
    @Volatile private var loaded = false
    @Volatile private var loadFailure: Throwable? = null

    @Synchronized fun ensureLoaded() {
        if (loaded) return
        loadFailure?.let { runtimeFailure(PackErrorCode.NATIVE_PLATFORM, "JNI bridge unavailable: ${it.message}", it) }
        try {
            System.loadLibrary("legado_pack_bridge")
            loaded = true
        } catch (failure: UnsatisfiedLinkError) {
            loadFailure = failure
            runtimeFailure(PackErrorCode.NATIVE_PLATFORM, "JNI bridge is not built/packaged for this process ABI", failure)
        } catch (failure: SecurityException) {
            loadFailure = failure
            runtimeFailure(PackErrorCode.NATIVE_PLATFORM, "JNI bridge loading denied", failure)
        }
    }

    external fun open(path: String, mode: Int, abiVersion: Int, sdkInt: Int, call: NativeCallState, logger: NativeLogSink, maxOutputBytes: Int): Long
    external fun invoke(handle: Long, command: String, argJson: ByteArray, call: NativeCallState): ByteArray
    external fun close(handle: Long): Int
}
