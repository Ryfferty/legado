package io.legado.pack.runtime.dex

import dalvik.system.DexClassLoader
import io.legado.pack.api.*
import io.legado.pack.runtime.*
import java.util.concurrent.atomic.AtomicReference

/** Trusted in-process code. Class initialization is cooperative, never a hard-cancellable sandbox. */
class DexPackRuntimeProvider : PackRuntimeProvider {
    override val kind = ComponentKind.DEX
    override val trust = RuntimeTrust.TRUSTED_IN_PROCESS
    override val capabilities = RuntimeCapabilities(false, false, IsolationLevel.NONE, IsolationLevel.NONE, IsolationLevel.NONE)
    private val loaders = HashMap<String, DexClassLoader>()

    override fun probe(component: ComponentSpec, platform: PackPlatform): RuntimeAvailability =
        if (component is ComponentSpec.Dex) RuntimeAvailability.RequiresDeviceProbe(
            "DEX verification/read-only dynamic loading and entry initialization require an Android device",
        ) else RuntimeAvailability.Unavailable(PackDiagnostic(PackErrorCode.UNSUPPORTED, "DEX provider received ${component.kind.wire}"))

    override fun load(ref: PackRef, component: ComponentSpec, context: PackContext, call: PackCallContext): PackInstance {
        if (component !is ComponentSpec.Dex) runtimeFailure(PackErrorCode.UNSUPPORTED, "not a DEX component")
        verifyBinding(ref, component, context, call)
        val code = ReadOnlyCode.stage(context, component.path, "dex", call)
        val optimized = context.storage.dataFile(RelativePath.require("runtime-code/optimized"))
        if (!optimized.isDirectory && !optimized.mkdirs()) runtimeFailure(PackErrorCode.IO_FAILED, "cannot create DEX optimization directory")
        // Parent is the host API loader so PackEntry identity cannot be replaced by
        // packaging a second API copy. Cached entries are never evicted as if unloaded.
        val loader = synchronized(loaders) {
            loaders[code.absolutePath] ?: run {
                if (loaders.size >= MAX_LOADERS) runtimeFailure(PackErrorCode.NEEDS_RESTART, "DEX loader cache is full ($MAX_LOADERS); restart required")
                DexClassLoader(code.absolutePath, optimized.absolutePath, null, PackEntry::class.java.classLoader).also {
                    loaders[code.absolutePath] = it
                }
            }
        }
        call.checkActive()
        var instance: DexInstance? = null
        var loaded = false
        try {
            // loadClass verifies without initializing; Class.forName(false) avoids
            // executing <clinit> before interface/origin validation.
            val type = Class.forName(component.className, false, loader)
            if (type.classLoader !== loader || !PackEntry::class.java.isAssignableFrom(type)) {
                runtimeFailure(PackErrorCode.LOAD_FAILED, "DEX entry must originate from this code file and implement host PackEntry")
            }
            call.checkActive()
            // <clinit>, constructor and initialize all share the ORIGINAL call.
            // Arbitrary trusted bytecode can ignore it; the manager must quarantine
            // a timed-out worker, never claim interrupt stopped class initialization.
            val entry = type.getConstructor().newInstance() as PackEntry
            val loadedInstance = DexInstance(ref, component.id, context, entry)
            instance = loadedInstance
            call.checkActive()
            entry.initialize(context, call)
            call.checkActive()
            loaded = true
            return loadedInstance
        } catch (e: PackException) {
            throw e
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: ReflectiveOperationException) {
            runtimeFailure(PackErrorCode.LOAD_FAILED, "DEX entry constructor/initialization failed: ${component.className}", e)
        } catch (e: LinkageError) {
            runtimeFailure(PackErrorCode.LOAD_FAILED, "DEX verification/dependency/static initialization failed: ${component.className}", e)
        } catch (e: Exception) {
            runtimeFailure(PackErrorCode.LOAD_FAILED, "DEX initialize failed: ${component.className}", e)
        } finally {
            // Also request cooperative cleanup when trusted initialize throws an Error.
            if (!loaded) instance?.close()
        }
    }

    private class DexInstance(
        override val ref: PackRef,
        override val componentId: String,
        private val context: PackContext,
        private val entry: PackEntry,
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
                if (closed) runtimeFailure(PackErrorCode.PACK_DISABLED, "DEX instance is closed")
                if (active != 0) runtimeFailure(PackErrorCode.LOAD_FAILED, "concurrent DEX invocation is not admitted")
                active++
            }
            try {
                call.checkActive()
                val result = entry.invoke(command, argJson.ifEmpty { "null" }, call)
                call.checkActive()
                RuntimeJson.parse(result, call)
                return result
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
            // A successful entry close does not unmap ART classes or prove loader collection.
            val closeResult = closeTask?.get()
            val detail = when {
                active != 0 -> "DEX call still executing; ART classes remain resident"
                closeResult == null -> "DEX entry close pending; classes/classloader remain resident"
                closeResult.released -> "DEX entry close completed; classes/classloader remain resident"
                closeResult.failure != null -> "DEX entry close failed (${closeResult.failure.code}): " +
                    "${closeResult.failure.message.take(1024)}; classes/classloader remain resident"
                else -> "DEX entry close reports resident; classes/classloader remain resident"
            }
            CloseOutcome.resident(detail)
        }

        private fun scheduleClose() {
            if (closeTask == null) closeTask = TrustedCloseWorker.submit { entry.close() }
        }
    }

    companion object {
        private const val MAX_LOADERS = 32
    }
}
