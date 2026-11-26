package io.legado.pack.manager

import io.legado.pack.TestPacks
import io.legado.pack.api.CloseOutcome
import io.legado.pack.api.ComponentKind
import io.legado.pack.api.ComponentSpec
import io.legado.pack.api.ImportPreview
import io.legado.pack.api.NativeAbi
import io.legado.pack.api.NativeLoadMode
import io.legado.pack.api.PackCallContext
import io.legado.pack.api.PackContext
import io.legado.pack.api.PackGrant
import io.legado.pack.api.PackHostEnvironment
import io.legado.pack.api.PackImportHint
import io.legado.pack.api.PackInstance
import io.legado.pack.api.PackLibVersion
import io.legado.pack.api.PackPlatform
import io.legado.pack.api.PackRef
import io.legado.pack.api.PackRuntimeProvider
import io.legado.pack.api.RuntimeAvailability
import io.legado.pack.api.RuntimeCapabilities
import io.legado.pack.api.RuntimeTrust
import io.legado.pack.api.IsolationLevel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FakeEnvironment(
    override val packsRoot: File,
    providers: Map<ComponentKind, PackRuntimeProvider> = emptyMap(),
) : PackHostEnvironment {

    private val runtimeProviders = providers

    override val platform = PackPlatform(
        sdkInt = 34,
        supportedAbis = setOf(NativeAbi.ARM64_V8A, NativeAbi.ARMEABI_V7A),
        pageSizeBytes = 4096,
    )
    override val frameworkPackLibVersion = PackLibVersion(1, 0, 0)
    override val defaultNativeLoadMode: NativeLoadMode = NativeLoadMode.FILE

    override fun runtimeProvider(kind: ComponentKind): PackRuntimeProvider? = runtimeProviders[kind]
}

class FakeRuntimeProvider(
    override val kind: ComponentKind = ComponentKind.JS,
    private val loader: (PackRef, ComponentSpec, PackContext, PackCallContext) -> PackInstance =
        { ref, component, _, _ -> FakeInstance(ref, component.id) },
) : PackRuntimeProvider {

    val probeCount = AtomicInteger()
    val loadCount = AtomicInteger()

    @Volatile
    var probeResult: RuntimeAvailability = RuntimeAvailability.Available

    @Volatile
    var loadFailure: Exception? = null

    override val trust: RuntimeTrust = RuntimeTrust.SANDBOXED_JS
    override val capabilities: RuntimeCapabilities = RuntimeCapabilities(
        hardCancellation = false,
        safeUnload = true,
        fileIsolation = IsolationLevel.ENFORCED,
        networkIsolation = IsolationLevel.ENFORCED,
        jsSandbox = IsolationLevel.ENFORCED,
    )

    override fun probe(component: ComponentSpec, platform: PackPlatform): RuntimeAvailability {
        probeCount.incrementAndGet()
        return probeResult
    }

    override fun load(
        ref: PackRef,
        component: ComponentSpec,
        context: PackContext,
        call: PackCallContext,
    ): PackInstance {
        loadCount.incrementAndGet()
        loadFailure?.let { throw it }
        return loader(ref, component, context, call)
    }
}

class FakeInstance(
    override val ref: PackRef,
    override val componentId: String,
    private val invoker: (String, String, PackCallContext) -> String = { _, arg, _ -> arg },
    private val closer: () -> CloseOutcome = { CloseOutcome.released() },
) : PackInstance {

    @Volatile
    override var closed: Boolean = false
        private set

    val invokeCount = AtomicInteger()

    override fun invoke(command: String, argJson: String, call: PackCallContext): String {
        call.checkActive()
        invokeCount.incrementAndGet()
        return invoker(command, argJson, call)
    }

    override fun close(): CloseOutcome {
        closed = true
        return closer()
    }
}

object PackFixtures {

    fun userscript(
        id: String = "hello-js",
        name: String = "Hello JS",
        version: String = "1.0.0",
        versionCode: Long = 1L,
        capabilities: List<String> = listOf("SOURCE_COMMANDS"),
        commands: List<String> = listOf("hello source"),
        network: String? = null,
        mcpTools: List<String> = emptyList(),
        apiRoutes: List<String> = emptyList(),
        selfTests: List<String> = emptyList(),
        extraHeaders: List<String> = emptyList(),
        body: String = "globalThis.__packExports = { hello: function (arg) { return arg; } };",
    ): ByteArray {
        val header = buildString {
            appendLine("// ==Pack==")
            appendLine("// @id $id")
            appendLine("// @name $name")
            appendLine("// @version $version")
            appendLine("// @versionCode $versionCode")
            appendLine("// @packLib >=1.0.0 <2.0.0")
            capabilities.forEach { appendLine("// @capability $it") }
            commands.forEach { appendLine("// @command $it") }
            network?.let { appendLine("// @network $it") }
            mcpTools.forEach { appendLine("// @mcpTool $it") }
            apiRoutes.forEach { appendLine("// @apiRoute $it") }
            selfTests.forEach { appendLine("// @selfTest $it") }
            extraHeaders.forEach { appendLine("// $it") }
            appendLine("// ==/Pack==")
            appendLine(body)
        }
        return header.toByteArray(Charsets.UTF_8)
    }

    fun manifestJson(
        id: String = "hello-js",
        name: String = "Hello JS",
        version: String = "1.0.0",
        versionCode: Long = 1L,
        capabilities: String = """["SOURCE_COMMANDS"]""",
        components: String = TestPacks.DEFAULT_COMPONENTS,
        commands: String = TestPacks.DEFAULT_COMMANDS,
        network: String? = null,
        mcpTools: String? = null,
        apiRoutes: String? = null,
        selfTests: String? = null,
    ): String = TestPacks.manifestJson(
        id = id,
        name = name,
        version = version,
        versionCode = versionCode,
        capabilities = capabilities,
        components = components,
        commands = commands,
        network = network,
        mcpTools = mcpTools,
        apiRoutes = apiRoutes,
        selfTests = selfTests,
    )

    fun zip(manifestJson: String, files: Map<String, String>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write(manifestJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    fun writeZip(file: File, manifestJson: String, files: Map<String, String>) {
        FileOutputStream(file).use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("pack.json"))
                zip.write(manifestJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                files.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    fun defaultGrant(
        preview: ImportPreview,
        toolPermissions: Map<String, Boolean> = emptyMap(),
        routePermissions: Map<String, Boolean> = emptyMap(),
        networkOpenApproved: Boolean = true,
    ): PackGrant = PackGrant(
        permissionHash = preview.permissionHash,
        networkOpenApproved = networkOpenApproved,
        toolPermissions = toolPermissions,
        routePermissions = routePermissions,
    )

    data class InstalledPack(
        val preview: ImportPreview,
        val info: io.legado.pack.api.PackInfo,
    )

    suspend fun install(
        manager: DefaultPackManager,
        content: ByteArray,
        hint: PackImportHint = PackImportHint(),
        grant: (ImportPreview) -> PackGrant = { PackFixtures.defaultGrant(it) },
    ): InstalledPack {
        val preview = manager.prepareImport(ByteArrayInputStream(content), hint)
        val info = manager.commitImport(preview.token, grant(preview))
        return InstalledPack(preview, info)
    }

    fun packDirectory(root: File, id: String): File = File(root, id)

    fun findRevisionDirectories(root: File, id: String): List<File> {
        val codeRoot = File(File(root, id), "code")
        return codeRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
    }
}