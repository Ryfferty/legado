package io.legado.app.web.mcp

import io.legado.app.help.pack.FakePackManager
import io.legado.app.help.pack.PackFixtures
import io.legado.app.help.pack.PackToolPermissionStore
import io.legado.pack.api.PackErrorCode
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class McpPackToolsTest {

    @Test
    fun `pack management tools are registered with structured handlers`() = runBlocking {
        val server = newServer()
        val manager = FakePackManager(listOf(PackFixtures.info()))
        val permissions = PackToolPermissionStore(
            Files.createTempDirectory("mcp-pack-tools").resolve("grants.json").toFile()
        )
        val tools = McpPackTools({ manager }, permissions)
        tools.register(server)

        assertTrue(server.tools.containsKey("pack_list"))
        assertTrue(server.tools.containsKey("pack_info"))
        assertTrue(server.tools.containsKey("pack_selftest"))
        assertTrue(server.tools.containsKey("pack_selftest_report"))
        assertTrue(server.tools.containsKey("pack_tool_permissions"))
        assertTrue(server.tools.containsKey("pack_set_write_tool"))
        assertTrue(server.tools.containsKey("pack_set_enabled"))
        assertTrue(server.tools.containsKey("pack_uninstall"))

        val list = tools.listResult()
        assertEquals("OK", list.structuredContent!!["code"]!!.jsonPrimitive.content)
        assertEquals(1, list.structuredContent!!["data"]!!.jsonObject["packs"]!!.jsonArray.size)
        assertFalse(list.isError == true)

        val info = tools.infoResult("demo.pack")
        assertEquals("demo.pack", info.structuredContent!!["data"]!!.jsonObject["id"]!!.jsonPrimitive.content)

        server.close()
    }

    @Test
    fun `self test execution and report lookup are separate`() = runBlocking {
        val server = newServer()
        val manager = FakePackManager(listOf(PackFixtures.info()))
        val report = PackFixtures.report()
        manager.putReport(report)
        val permissions = PackToolPermissionStore(
            Files.createTempDirectory("mcp-pack-report").resolve("grants.json").toFile()
        )
        val tools = McpPackTools({ manager }, permissions)
        tools.register(server)

        val executed = tools.selftestResult("demo.pack")
        assertEquals(1, manager.selfTestCount)
        assertEquals(
            "run_1",
            executed.structuredContent!!["data"]!!.jsonObject["runId"]!!.jsonPrimitive.content,
        )

        val fetched = tools.reportResult("demo.pack", "run_1")
        assertEquals(1, manager.selfTestCount)
        assertEquals(
            "run_1",
            fetched.structuredContent!!["data"]!!.jsonObject["runId"]!!.jsonPrimitive.content,
        )

        val missing = tools.reportResult("demo.pack", "missing")
        assertEquals(
            "REPORT_NOT_FOUND",
            missing.structuredContent!!["code"]!!.jsonPrimitive.content,
        )
        assertTrue(missing.isError == true)

        val badRunId = tools.reportResult("demo.pack", "../../etc")
        assertEquals(
            PackErrorCode.INVALID_REQUEST.wire,
            badRunId.structuredContent!!["code"]!!.jsonPrimitive.content,
        )

        server.close()
    }

    @Test
    fun `write authorization and uninstall are structured`() = runBlocking {
        val server = newServer()
        val manifest = PackFixtures.manifest(toolName = "demo_write", isWrite = true)
        val manager = FakePackManager(listOf(PackFixtures.info(manifest)))
        val permissions = PackToolPermissionStore(
            Files.createTempDirectory("mcp-pack-auth").resolve("grants.json").toFile()
        )
        val tools = McpPackTools({ manager }, permissions)
        tools.register(server)

        val denied = tools.setWriteToolResult("demo.pack", "demo_write", true)
        assertEquals(
            "OK",
            denied.structuredContent!!["code"]!!.jsonPrimitive.content,
        )
        assertTrue(permissions.isWriteToolEnabled(PackFixtures.ref(manifest), manifest, "demo_write"))

        val readTool = tools.setWriteToolResult("demo.pack", "missing", true)
        assertEquals(
            PackErrorCode.INVALID_REQUEST.wire,
            readTool.structuredContent!!["code"]!!.jsonPrimitive.content,
        )

        val uninstalled = tools.uninstallResult("demo.pack")
        assertEquals("OK", uninstalled.structuredContent!!["code"]!!.jsonPrimitive.content)
        assertTrue(uninstalled.structuredContent!!["data"]!!.jsonObject["clean"]!!.jsonPrimitive.boolean)
        assertTrue(manager.infos.isEmpty())
        assertFalse(permissions.isWriteToolEnabled(PackFixtures.ref(manifest), manifest, "demo_write"))

        server.close()
    }

    private fun newServer(): Server = Server(
        serverInfo = Implementation(name = "test", version = "1"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
                resources = ServerCapabilities.Resources(),
                logging = ServerCapabilities.Logging,
            ),
        ),
    )
}