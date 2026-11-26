package io.legado.app.help.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level contract checks for host wiring that cannot be exercised on a
 * plain JVM (NanoHTTPD/Android assembly). Behavioral checks live in the
 * dedicated bridge/controller/fixture tests.
 */
class PackHostIntegrationContractTest {

    @Test
    fun `http pack prefix is intercepted before body parsing and static assets`() {
        val server = projectFile("app/src/main/java/io/legado/app/web/HttpServer.kt")
        val intercept = server.indexOf("PackRoutes.isPackPath(uri)")
        val parsedBody = server.indexOf("session.parseBody(files)")
        val assets = server.indexOf("assetsWeb.getResponse(uri)")
        val dispatch = server.indexOf("return servePackRequest(session)")

        assertTrue(intercept >= 0)
        assertTrue(dispatch > intercept)
        assertTrue(parsedBody > intercept)
        assertTrue(assets > dispatch)
        assertTrue(server.contains("PackController.handle("))
        assertTrue(server.contains("addWebHeaders(session.headers[\"origin\"], session.uri)"))
    }

    @Test
    fun `web controller checks the token before invoking the body reader`() {
        val controller = projectFile(
            "app/src/main/java/io/legado/app/api/controller/PackController.kt"
        )
        val token = controller.indexOf("val tokenAccepted")
        val body = controller.indexOf("readBodyChecked(headers")

        assertTrue(token >= 0)
        assertTrue(body > token)
        assertTrue(controller.contains("PackRoutes.isPackPath(uri)"))
        assertTrue(controller.contains("PackJson.result(result)"))
        assertTrue(controller.contains("PackJson.success(PackJson.report(report))"))
    }

    @Test
    fun `mcp server advertises list changed and shares one server instance`() {
        val server = projectFile("app/src/main/java/io/legado/app/web/mcp/McpToolServer.kt")
        assertTrue(server.contains("ServerCapabilities.Tools(listChanged = true)"))
        assertTrue(server.contains("sharedServer"))
        assertTrue(server.contains("PackMcpBridge("))
        assertTrue(server.contains("McpPackTools("))
        assertTrue(server.contains("bridge.attach(PackHost.registry)"))
        assertTrue(server.contains("PackHost.toolPermissions"))

        val bridge = projectFile("app/src/main/java/io/legado/app/web/mcp/PackMcpBridge.kt")
        assertTrue(bridge.contains("server.addTool(tool, handler)"))
        assertTrue(bridge.contains("server.removeTool(name)"))
        assertTrue(bridge.contains("PackEntryPoint.McpTool(spec.name)"))
        assertTrue(bridge.contains("expectedRef = registration.ref"))
        assertTrue(bridge.contains("permissions.isWriteToolEnabled("))
        assertTrue(bridge.contains("PackInvocation("))
    }

    @Test
    fun `all existing builtin mcp names are reserved`() {
        val source = projectFile("app/src/main/java/io/legado/app/web/mcp/McpToolServer.kt")
        val registrations = source.substringAfter("private fun registerTools")
        val names = Regex("name = \\\"([a-z_]+)\\\"")
            .findAll(registrations)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(13, names.size)
        assertTrue(HostMcpToolNames.BUILTIN.containsAll(names))
        assertTrue(HostMcpToolNames.RESERVED.containsAll(HostMcpToolNames.PACK_MANAGEMENT))
        assertTrue(HostMcpToolNames.isReserved("SAVE_SOURCE"))

        val packTools = projectFile(
            "app/src/main/java/io/legado/app/web/mcp/McpPackTools.kt"
        )
        val packNames = Regex("name = \\\"(pack_[a-z_]+)\\\"")
            .findAll(packTools)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(HostMcpToolNames.PACK_MANAGEMENT, packNames)
    }

    @Test
    fun `javascript bridge exposes only narrow pack entry points`() {
        val extensions = projectFile("app/src/main/java/io/legado/app/help/JsExtensions.kt")
        assertTrue(extensions.contains("val packKV: PackKvBridge"))
        assertTrue(extensions.contains("val packCrypto: PackCryptoBridge"))
        assertTrue(extensions.contains("fun engineCall(pack: String, cmd: String, arg: String?)"))
        assertTrue(extensions.contains("PackHost.manager"))
        assertTrue(extensions.contains("PackSourceIdentity.of(source)"))
        assertTrue(extensions.contains("PackCaller.Source(sourceKey, source.getTag())"))
        assertTrue(extensions.contains("command.sourceExported"))
        assertTrue(extensions.contains("manager.invokeBlocking(invocation)"))
        assertFalse(extensions.contains("PackManager"))
        assertFalse(extensions.contains("android.content.Context"))
        assertFalse(extensions.contains("ClassLoader"))
    }

    @Test
    fun `host uses the documented lazy manager and providers`() {
        val host = projectFile("app/src/main/java/io/legado/app/help/pack/PackHost.kt")
        assertTrue(host.contains("DefaultPackManager("))
        assertTrue(host.contains("environment = environment"))
        assertTrue(host.contains("registry = registry"))
        assertTrue(host.contains("limits = limits"))
        assertTrue(host.contains("dynamicWriteToolGrant ="))
        assertTrue(host.contains("toolPermissions.isWriteToolEnabled"))
        assertTrue(host.contains("HostPackManager("))
        assertTrue(host.contains("permissions.removePack(info.ref.id)"))
        assertTrue(host.contains("JsPackRuntimeProvider()"))
        assertTrue(host.contains("DexPackRuntimeProvider()"))
        assertTrue(host.contains("NativePackRuntimeProvider(defaultNativeLoadMode)"))
        assertTrue(host.contains("PackStorageLayout.fromHostFilesDir(filesDir)"))
        assertTrue(host.contains("HostMcpToolNames.RESERVED"))
        assertTrue(host.contains("filesDir"))
        assertTrue(host.contains("supportedAbis"))
    }

    private fun projectFile(path: String): String = projectPath(path).readText()

    private fun projectPath(path: String): File {
        var root = File(requireNotNull(System.getProperty("user.dir")))
        repeat(6) {
            val candidate = File(root, path)
            if (candidate.exists()) return candidate
            root = root.parentFile ?: error("Project root not found for: $path")
        }
        error("Project path not found: $path")
    }
}