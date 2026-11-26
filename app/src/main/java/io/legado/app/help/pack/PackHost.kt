package io.legado.app.help.pack

import android.os.Build
import android.system.Os
import android.system.OsConstants
import io.legado.app.BuildConfig
import io.legado.pack.api.ComponentKind
import io.legado.pack.api.NativeAbi
import io.legado.pack.api.NativeLoadMode
import io.legado.pack.api.PackGrant
import io.legado.pack.api.PackHostEnvironment
import io.legado.pack.api.PackId
import io.legado.pack.api.PackInfo
import io.legado.pack.api.PackLibVersion
import io.legado.pack.api.PackManager
import io.legado.pack.api.PackPlatform
import io.legado.pack.api.PackResourceLimits
import io.legado.pack.api.PackResult
import io.legado.pack.api.PackRuntimeProvider
import io.legado.pack.api.PackStorageLayout
import io.legado.pack.api.ToolSpec
import io.legado.pack.api.UninstallReport
import io.legado.pack.manager.DefaultPackManager
import io.legado.pack.registry.PackRegistry
import io.legado.pack.runtime.dex.DexPackRuntimeProvider
import io.legado.pack.runtime.js.JsPackRuntimeProvider
import io.legado.pack.runtime.nativebridge.NativePackRuntimeProvider
import splitties.init.appCtx
import java.io.File

/**
 * Single host entry point for the pack framework.
 *
 * Both the UI and every bridge (JavaScript, MCP, Web) obtain the manager and
 * registry here; no other object constructs them. Everything is lazy: merely
 * loading `PackHost` does not create the manager, touch pack files, or execute
 * package code. The manager only registers metadata until a call/self-test is
 * explicitly made.
 */
object PackHost {

    private val lock = Any()

    private var testEnvironment: PackHostEnvironment? = null
    private var testManager: PackManager? = null
    private var testRegistry: PackRegistry? = null
    private var testToolPermissions: PackToolPermissionStore? = null

    private var environmentInstance: PackHostEnvironment? = null
    private var registryInstance: PackRegistry? = null
    private var managerInstance: PackManager? = null
    private var permissionsInstance: PackToolPermissionStore? = null

    val limits: PackResourceLimits by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PackResourceLimits()
    }

    val environment: PackHostEnvironment
        get() = synchronized(lock) {
            testEnvironment ?: environmentInstance ?: AndroidPackHostEnvironment.create()
                .also { environmentInstance = it }
        }

    /**
     * The registry starts with every host-owned MCP tool name reserved. A pack
     * manifest that tries to reuse one is rejected by the registry's atomic
     * `replaceAll` validation and never overwrites a built-in.
     */
    val registry: PackRegistry
        get() = synchronized(lock) {
            testRegistry ?: registryInstance ?: PackRegistry(
                reservedToolNames = HostMcpToolNames.RESERVED,
            ).also { registryInstance = it }
        }

    val manager: PackManager
        get() = synchronized(lock) {
            testManager ?: managerInstance ?: HostPackManager(
                delegate = DefaultPackManager(
                    environment = environment,
                    registry = registry,
                    limits = limits,
                    dynamicWriteToolGrant = { ref, manifest, toolName ->
                        toolPermissions.isWriteToolEnabled(ref, manifest, toolName)
                    },
                ),
                permissions = toolPermissions,
            ).also { managerInstance = it }
        }

    /**
     * Host-owned write grants for pack MCP tools. Default state is empty, so
     * every manifest write tool is denied until explicitly enabled through
     * [setPackWriteToolEnabled] by the management surface.
     */
    val toolPermissions: PackToolPermissionStore
        get() = synchronized(lock) {
            testToolPermissions ?: permissionsInstance ?: PackToolPermissionStore(
                File(
                    File(environment.packsRoot, ".host"),
                    "tool-permissions.json",
                )
            ).also { permissionsInstance = it }
        }

    val packsRoot: File
        get() = environment.packsRoot

    fun newSourceKvBridge(sourceKeyProvider: () -> String?): PackKvBridge =
        PackKvBridge(sourceKeyProvider, manager, environment.packsRoot, limits)

    data class PackToolStatus(
        val tool: ToolSpec,
        val writeEnabled: Boolean,
    )

    fun declaredTools(id: PackId): List<PackToolStatus> {
        val info = manager.info(id) ?: return emptyList()
        return info.manifest.mcpTools.map { tool ->
            PackToolStatus(
                tool = tool,
                writeEnabled = toolPermissions.isWriteToolEnabled(
                    info.ref,
                    info.manifest,
                    tool.name,
                ),
            )
        }
    }

    fun isPackWriteToolEnabled(id: PackId, toolName: String): Boolean {
        val info = manager.info(id) ?: return false
        return toolPermissions.isWriteToolEnabled(info.ref, info.manifest, toolName)
    }

    /**
     * Management-surface entry for enabling a declared write tool. Read tools
     * and undeclared names are rejected by [PackToolPermissionStore].
     */
    fun setPackWriteToolEnabled(
        id: PackId,
        toolName: String,
        enabled: Boolean,
    ): Boolean {
        val info = manager.info(id) ?: return false
        return toolPermissions.setWriteToolEnabled(
            info.ref,
            info.manifest,
            toolName,
            enabled,
        )
    }

    internal fun installForTest(
        environment: PackHostEnvironment,
        manager: PackManager,
        registry: PackRegistry,
        toolPermissions: PackToolPermissionStore,
    ) {
        synchronized(lock) {
            testEnvironment = environment
            testManager = manager
            testRegistry = registry
            testToolPermissions = toolPermissions
            environmentInstance = null
            managerInstance = null
            registryInstance = null
            permissionsInstance = null
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            testEnvironment = null
            testManager = null
            testRegistry = null
            testToolPermissions = null
            environmentInstance = null
            managerInstance = null
            registryInstance = null
            permissionsInstance = null
        }
    }
}

/**
 * Host facade around the framework manager. Lifecycle transitions that make a
 * prior write grant meaningless (import/disable/enable/uninstall) revoke the
 * persisted write permission; re-importing identical content therefore cannot
 * inherit an old grant just because its revision and epoch happen to repeat.
 */
internal class HostPackManager(
    private val delegate: PackManager,
    private val permissions: PackToolPermissionStore,
) : PackManager by delegate {

    override suspend fun commitImport(previewToken: String, grant: PackGrant): PackInfo {
        val info = delegate.commitImport(previewToken, grant)
        // The import dialog's explicit write checks are the initial grant.
        // Everything else starts off; later management toggles use the same
        // store through setPackWriteToolEnabled.
        permissions.removePack(info.ref.id)
        info.manifest.mcpTools
            .filter { it.isWrite && grant.permitsTool(it.name) }
            .forEach { tool ->
                permissions.setWriteToolEnabled(
                    info.ref,
                    info.manifest,
                    tool.name,
                    true,
                )
            }
        return info
    }

    override suspend fun setEnabled(id: PackId, enabled: Boolean): PackResult {
        val result = delegate.setEnabled(id, enabled)
        if (!result.isError) {
            permissions.removePack(id)
        }
        return result
    }

    override suspend fun uninstall(id: PackId): UninstallReport {
        val report = delegate.uninstall(id)
        permissions.removePack(id)
        return report
    }
}

/**
 * Android facts are injected into the framework; the pack module itself never
 * reads `Build` or hardcodes an application id. `filesDir/packs` is the current
 * app's private directory, so uninstalling the app structurally removes all
 * pack files.
 */
internal class AndroidPackHostEnvironment private constructor(
    override val packsRoot: File,
    override val platform: PackPlatform,
    override val defaultNativeLoadMode: NativeLoadMode,
) : PackHostEnvironment {

    override val frameworkPackLibVersion: PackLibVersion = PackLibVersion.CURRENT

    private val providers: Map<ComponentKind, PackRuntimeProvider> by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED
    ) {
        mapOf(
            ComponentKind.JS to JsPackRuntimeProvider(),
            ComponentKind.DEX to DexPackRuntimeProvider(),
            ComponentKind.NATIVE to NativePackRuntimeProvider(defaultNativeLoadMode),
        )
    }

    override fun runtimeProvider(kind: ComponentKind): PackRuntimeProvider? =
        providers[kind]

    companion object {
        fun create(): AndroidPackHostEnvironment {
            val filesDir = appCtx.filesDir
            val layout = PackStorageLayout.fromHostFilesDir(filesDir)
            val abis = Build.SUPPORTED_ABIS
                .mapNotNull { NativeAbi.parse(it) }
                .toSet()
            val rawPageSize = try {
                Os.sysconf(OsConstants._SC_PAGESIZE).toInt()
            } catch (_: Throwable) {
                4096
            }
            val pageSize = if (
                rawPageSize >= 4096 && Integer.bitCount(rawPageSize) == 1
            ) {
                rawPageSize
            } else {
                4096
            }
            val nativeMode = if (
                !BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 30
            ) {
                NativeLoadMode.MEMFD
            } else {
                NativeLoadMode.FILE
            }
            return AndroidPackHostEnvironment(
                packsRoot = layout.packsRoot,
                platform = PackPlatform(
                    sdkInt = Build.VERSION.SDK_INT,
                    supportedAbis = abis,
                    pageSizeBytes = pageSize,
                ),
                defaultNativeLoadMode = nativeMode,
            )
        }
    }
}