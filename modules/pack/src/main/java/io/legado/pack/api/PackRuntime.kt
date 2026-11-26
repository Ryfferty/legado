package io.legado.pack.api

/**
 * Host platform facts injected by the app. Constructing this in pure JVM tests
 * is intentional; the framework module never reads Android `Build` itself.
 */
data class PackPlatform(
    val sdkInt: Int,
    val supportedAbis: Set<NativeAbi>,
    val pageSizeBytes: Int = 4096,
) {
    init {
        require(sdkInt >= 1) { "sdkInt must be >= 1" }
        require(pageSizeBytes >= 4096 && Integer.bitCount(pageSizeBytes) == 1) {
            "pageSizeBytes must be a power of two >= 4096"
        }
    }
}

enum class RuntimeTrust {
    /** Rhino-based JS with an explicit deny-all Java bridge and no host objects. */
    SANDBOXED_JS,

    /** DEX/native code runs inside the app process. ClassLoader/native loading is not a security sandbox. */
    TRUSTED_IN_PROCESS,
}

enum class IsolationLevel {
    /** The host can actually enforce the boundary for calls through this runtime. */
    ENFORCED,

    /** The runtime cooperates, but trusted code may bypass the primitive. */
    COOPERATIVE,

    NONE,
}

/**
 * What a concrete runtime provider can actually enforce. These values must be
 * reported by the provider, never inferred from a manifest declaration.
 */
data class RuntimeCapabilities(
    val hardCancellation: Boolean,
    val safeUnload: Boolean,
    val fileIsolation: IsolationLevel,
    val networkIsolation: IsolationLevel,
    val jsSandbox: IsolationLevel,
)

sealed interface RuntimeAvailability {
    /** The provider has checked the actual component file and can load it on demand. */
    data object Available : RuntimeAvailability

    /** A known hard failure (wrong ABI, unsupported API, missing symbol, ...). */
    data class Unavailable(val failure: PackDiagnostic) : RuntimeAvailability

    /**
     * Manifest/static facts are compatible, but only a device probe or first
     * load can establish availability. The host must not display this as
     * "supported" yet.
     */
    data class RequiresDeviceProbe(val reason: String) : RuntimeAvailability
}

/**
 * Result of releasing a runtime instance. [stillResident] means code may still
 * be mapped/executing; [needsRestart] means the host cannot safely reload a
 * different revision in this process.
 */
data class CloseOutcome(
    val released: Boolean,
    val stillResident: Boolean = !released,
    val needsRestart: Boolean = stillResident,
    val failure: PackDiagnostic? = null,
) {
    init {
        if (released) {
            require(!stillResident) { "released close outcome cannot still be resident" }
            require(!needsRestart) { "released close outcome cannot need a restart" }
        }
        if (needsRestart) {
            require(stillResident) { "needsRestart implies stillResident" }
        }
    }

    companion object {
        fun released(): CloseOutcome = CloseOutcome(released = true)

        fun resident(reason: String, needsRestart: Boolean = true): CloseOutcome =
            CloseOutcome(
                released = false,
                stillResident = true,
                needsRestart = needsRestart,
                failure = PackDiagnostic(
                    code = PackErrorCode.NEEDS_RESTART,
                    message = reason,
                ),
            )

        fun failed(failure: PackDiagnostic, stillResident: Boolean = true): CloseOutcome =
            CloseOutcome(
                released = false,
                stillResident = stillResident,
                needsRestart = stillResident,
                failure = failure,
            )
    }
}

/**
 * Runtime handle for one component of one revision. Invocation happens on the
 * pack's own worker with a [PackCallContext] whose deadline/cancellation must
 * be checked by the implementation.
 */
interface PackInstance {
    val ref: PackRef
    val componentId: String
    val closed: Boolean

    fun invoke(command: String, argJson: String, call: PackCallContext): String

    /** Must be bounded by the host close budget and must not claim a kill it cannot perform. */
    fun close(): CloseOutcome
}

/**
 * Entry point contract for DEX components. The class named in a DEX manifest is
 * loaded with DexClassLoader on first use, checked to implement this interface,
 * then initialized once on the pack worker.
 *
 * [call] is the exact first command/self-test call that triggered loading. Its
 * deadline and cancellation cover class verification, top-level/static
 * initialization and the first invocation; the runtime must not restart the
 * clock. [PackContext] primitives receive this same call explicitly.
 */
interface PackEntry {
    fun initialize(context: PackContext, call: PackCallContext)

    fun invoke(command: String, argJson: String, call: PackCallContext): String

    fun close(): CloseOutcome
}

/**
 * A concrete component runtime (JS/DEX/native). Providers are installed by the
 * host; the manifest can declare components but cannot make a provider
 * available.
 *
 * JS providers must use their own Rhino ContextFactory and must not call the
 * global `Context.enter()` or the book-source `RhinoScriptEngine` bindings.
 * They must implement [PackJsProtocol] and expose only
 * [PackJsPrimitiveFacade] through the fixed `__packApi` object; DEX/native
 * providers must never be described as security sandboxes.
 */
interface PackRuntimeProvider {
    val kind: ComponentKind
    val trust: RuntimeTrust
    val capabilities: RuntimeCapabilities

    /**
     * Static/device capability check; must not execute package code.
     * [component] must have kind == [kind]; otherwise the provider returns
     * an Unsupported diagnostic instead of loading it.
     */
    fun probe(component: ComponentSpec, platform: PackPlatform): RuntimeAvailability

    /**
     * Loads and initializes one component. [component] must have kind == [kind].
     * Called only on the pack worker and only after admission checks (enabled,
     * epoch, permission). [call] is the first call that triggered loading; the
     * same deadline/cancellation covers verification, top-level/static
     * initialization and the first command. It must not be re-timed. May throw
     * [PackException] with LOAD_FAILED/COMPONENT_MISSING/NATIVE_PLATFORM.
     */
    fun load(
        ref: PackRef,
        component: ComponentSpec,
        context: PackContext,
        call: PackCallContext,
    ): PackInstance
}

/**
 * Pure preflight for native declarations. It deliberately never returns
 * [RuntimeAvailability.Available]: a manifest plus ABI/API compatibility is
 * not proof that the file can be mapped/loaded on the actual device.
 */
object NativeLoadPreflight {

    fun check(
        component: ComponentSpec.Native,
        platform: PackPlatform,
        hostDefaultMode: NativeLoadMode,
    ): RuntimeAvailability {
        if (component.abi !in platform.supportedAbis) {
            return RuntimeAvailability.Unavailable(
                PackDiagnostic(
                    code = PackErrorCode.NATIVE_PLATFORM,
                    message = "native ABI ${component.abi.wire} is not supported by this host",
                    details = mapOf(
                        "abi" to component.abi.wire,
                        "supportedAbis" to platform.supportedAbis.joinToString(",") { it.wire },
                    ),
                ),
            )
        }
        if (component.abiVersion != PackNativeAbiV1.ABI_VERSION) {
            return RuntimeAvailability.Unavailable(
                PackDiagnostic(
                    code = PackErrorCode.NATIVE_PLATFORM,
                    message = "native ABI version ${component.abiVersion} is not supported",
                    details = mapOf("abiVersion" to component.abiVersion.toString()),
                ),
            )
        }
        val mode = component.requestedMode ?: hostDefaultMode
        if (mode == NativeLoadMode.MEMFD && platform.sdkInt < 30) {
            return RuntimeAvailability.Unavailable(
                PackDiagnostic(
                    code = PackErrorCode.NATIVE_PLATFORM,
                    message = "MEMFD native loading requires Android API 30 or newer",
                    details = mapOf(
                        "requestedMode" to mode.wire,
                        "sdkInt" to platform.sdkInt.toString(),
                    ),
                ),
            )
        }
        return RuntimeAvailability.RequiresDeviceProbe(
            "native ${mode.wire} loading must be verified on the actual device; " +
                "manifest, ABI and API level are not sufficient",
        )
    }
}