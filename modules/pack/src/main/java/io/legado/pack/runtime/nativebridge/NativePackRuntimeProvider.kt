package io.legado.pack.runtime.nativebridge

import android.os.Build
import io.legado.pack.api.*
import io.legado.pack.runtime.*
import java.util.concurrent.atomic.AtomicReference

class NativePackRuntimeProvider(private val defaultMode: NativeLoadMode) : PackRuntimeProvider {
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
        if (mode == NativeLoadMode.MEMFD && Build.VERSION.SDK_INT < 30) {
            runtimeFailure(PackErrorCode.NATIVE_PLATFORM, "MEMFD requires actual Android API 30+; no fallback")
        }
        val file = ReadOnlyCode.stage(context, component.path, "so", call)
        NativeBridge.ensureLoaded()
        call.checkActive()
        val handle = try {
            NativeBridge.open(file.absolutePath, PackNativeAbiV1.modeOf(mode), component.abiVersion, Build.VERSION.SDK_INT,
                NativeCallState(call), NativeLogSink(context), MAX_OUTPUT_BYTES)
        } catch (e: NativeBridgeFailure) {
            throw platformFailure(component, context.platform, mode, e)
        }
        val instance = NativeInstance(ref, component.id, context, handle)
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
            ),
        ), failure)

    private class NativeInstance(
        override val ref: PackRef,
        override val componentId: String,
        private val context: PackContext,
        private val handle: Long,
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

        override fun close(): CloseOutcome = synchronized(gate) {
            closeRequested = true
            if (active == 0) scheduleClose()
            closeTask?.get() ?: CloseOutcome.resident("native close pending or call still executing; library remains mapped")
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

internal class NativeBridgeFailure(message: String) : RuntimeException(message)

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
