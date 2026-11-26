package io.legado.pack.manifest

import io.legado.pack.TestPacks
import io.legado.pack.api.ComponentSpec
import io.legado.pack.api.JsonValue
import io.legado.pack.api.NativeAbi
import io.legado.pack.api.NativeLoadMode
import io.legado.pack.api.NetworkPolicy
import io.legado.pack.api.PackArchiveSpec
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackManifestParserTest {

    @Test
    fun `minimal js manifest defaults network to none`() {
        val manifest = TestPacks.parseValid(TestPacks.manifestJson())

        assertEquals(PackId.require("hello-js"), manifest.id)
        assertEquals(1, manifest.components.size)
        val component = manifest.components.single() as ComponentSpec.Js
        assertEquals(PackArchiveSpec.SINGLE_FILE_JS_ENTRY, component.entry.value)
        assertTrue(manifest.network is NetworkPolicy.None)
        assertFalse(manifest.network.permits("example.com"))
        assertFalse(manifest.permissions.requiresOpenNetworkApproval)
        assertEquals(setOf("hello"), manifest.permissions.sourceCommands)
    }

    @Test
    fun `duplicate json fields are rejected before model construction`() {
        val diagnostics = TestPacks.parseInvalid(
            """{"schemaVersion":1,"id":"hello-js","id":"other"}""",
        )

        assertTrue(diagnostics.any { it.code == PackErrorCode.MANIFEST_INVALID })
        assertTrue(diagnostics.any { it.path == "$.id" && it.message.contains("duplicate") })
    }

    @Test
    fun `invalid id and traversal entry are rejected`() {
        val diagnostics = TestPacks.parseInvalid(
            TestPacks.manifestJson(
                id = "../evil",
                components = """[{"id":"main","type":"js","entry":"../main.js"}]""",
            ),
        )

        assertTrue(diagnostics.any { it.path == "$.id" })
        assertTrue(diagnostics.any { it.path == "$.components[0].entry" })
    }

    @Test
    fun `incompatible packLib gate is a red card`() {
        val diagnostics = TestPacks.parseInvalid(
            TestPacks.manifestJson(packLib = ">=2.0.0 <3.0.0"),
        )

        val mismatch = diagnostics.single { it.code == PackErrorCode.PACKLIB_INCOMPATIBLE }
        assertTrue(mismatch.isRedCard)
        assertEquals("$.packLib", mismatch.path)
    }

    @Test
    fun `newer manifest schema is rejected`() {
        val diagnostics = TestPacks.parseInvalid(
            TestPacks.manifestJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
        )

        assertTrue(diagnostics.any { it.code == PackErrorCode.MANIFEST_SCHEMA_UNSUPPORTED })
    }

    @Test
    fun `duplicate tool names are rejected`() {
        val diagnostics = TestPacks.parseInvalid(
            TestPacks.manifestJson(
                capabilities = """["SOURCE_COMMANDS","MCP_TOOLS"]""",
                mcpTools = """[
                    {"name":"demo_add","command":"hello"},
                    {"name":"demo_add","command":"hello"}
                ]""",
            ),
        )

        assertTrue(
            diagnostics.any {
                it.path == "$.mcpTools[1].name" && it.message.contains("duplicate tool")
            },
        )
    }

    @Test
    fun `reserved tool name is a conflict`() {
        val diagnostics = TestPacks.parseInvalid(
            TestPacks.manifestJson(
                capabilities = """["SOURCE_COMMANDS","MCP_TOOLS"]""",
                mcpTools = """[{"name":"demo_add","command":"hello"}]""",
            ),
            ManifestValidationContext(reservedToolNames = setOf("demo_add")),
        )

        assertTrue(diagnostics.any { it.code == PackErrorCode.TOOL_CONFLICT })
    }

    @Test
    fun `tool schema required properties must exist`() {
        val diagnostics = TestPacks.parseInvalid(
            TestPacks.manifestJson(
                capabilities = """["SOURCE_COMMANDS","MCP_TOOLS"]""",
                mcpTools = """[{
                    "name":"demo_add",
                    "command":"hello",
                    "inputSchema":{
                        "type":"object",
                        "properties":{"a":{"type":"number"}},
                        "required":["b"]
                    }
                }]""",
            ),
        )

        assertTrue(diagnostics.any { it.path == "$.mcpTools[0].inputSchema.required[0]" })
    }

    @Test
    fun `tool command must reference an existing command`() {
        val diagnostics = TestPacks.parseInvalid(
            TestPacks.manifestJson(
                capabilities = """["SOURCE_COMMANDS","MCP_TOOLS"]""",
                mcpTools = """[{"name":"demo_add","command":"missing"}]""",
            ),
        )

        assertTrue(diagnostics.any { it.path == "$.mcpTools[0].command" })
    }

    @Test
    fun `pattern allowlist matches subdomains but not the bare domain`() {
        val manifest = TestPacks.parseValid(
            TestPacks.manifestJson(
                network = """{"mode":"patterns","patterns":["*.example.com","api.test"]}""",
            ),
        )

        val policy = manifest.network as NetworkPolicy.Allowlist
        assertTrue(policy.permits("api.example.com"))
        assertTrue(policy.permits("a.b.example.com"))
        assertFalse(policy.permits("example.com"))
        assertFalse(policy.permits("notexample.com"))
        assertTrue(policy.permits("api.test"))
    }

    @Test
    fun `open network is parsed and flagged for explicit consent`() {
        val result = PackManifestParser().parseJson(
            TestPacks.manifestJson(
                network = """{"mode":"open","reason":"dynamic host discovery"}""",
            ),
        )

        assertTrue(result is ManifestParseResult.Valid)
        val valid = result as ManifestParseResult.Valid
        assertTrue(valid.manifest.network is NetworkPolicy.Open)
        assertTrue(valid.manifest.network.permits("anything.example"))
        assertTrue(valid.manifest.permissions.requiresOpenNetworkApproval)
        assertTrue(valid.warnings.any { it.code == PackErrorCode.NETWORK_OPEN_REQUIRES_CONSENT })
    }

    @Test
    fun `unknown field is rejected`() {
        val diagnostics = TestPacks.parseInvalid(
            TestPacks.manifestJson(extra = "\"unexpected\":1"),
        )

        assertTrue(diagnostics.any { it.path == "$.unexpected" })
    }

    @Test
    fun `duplicate capability is rejected`() {
        val diagnostics = TestPacks.parseInvalid(
            TestPacks.manifestJson(capabilities = """["KV","KV"]"""),
        )

        assertTrue(diagnostics.any { it.path == "$.capabilities[1]" })
    }

    @Test
    fun `js dex and native declarations parse without claiming runtime availability`() {
        val manifest = TestPacks.parseValid(
            TestPacks.manifestJson(
                capabilities = """["SOURCE_COMMANDS"]""",
                components = """[
                    {"id":"js","type":"js","entry":"main.js"},
                    {"id":"dex","type":"dex","path":"classes.dex","class":"example.pack.Entry"},
                    {"id":"native","type":"native","path":"libs/arm64-v8a/libhello.so","abi":"arm64-v8a","mode":"memfd","abiVersion":1}
                ]""",
                commands = """[{"name":"hello","component":"js","source":true}]""",
            ),
        )

        assertEquals(3, manifest.components.size)
        val native = manifest.components.single { it is ComponentSpec.Native } as ComponentSpec.Native
        assertEquals(NativeAbi.ARM64_V8A, native.abi)
        assertEquals(NativeLoadMode.MEMFD, native.requestedMode)
    }

    @Test
    fun `source exported command requires its capability`() {
        val diagnostics = TestPacks.parseInvalid(
            TestPacks.manifestJson(capabilities = """[]"""),
        )

        assertTrue(diagnostics.any { it.path == "$.commands[0].source" })
    }

    @Test
    fun `mcp tools require the mcp capability`() {
        val diagnostics = TestPacks.parseInvalid(
            TestPacks.manifestJson(
                capabilities = """["SOURCE_COMMANDS"]""",
                mcpTools = """[{"name":"demo_add","command":"hello"}]""",
            ),
        )

        assertTrue(diagnostics.any { it.path == "$.capabilities" })
    }

    @Test
    fun `write route must use post`() {
        val diagnostics = TestPacks.parseInvalid(
            TestPacks.manifestJson(
                capabilities = """["SOURCE_COMMANDS","API_ROUTES"]""",
                apiRoutes = """[{"name":"status","method":"GET","command":"hello","write":true}]""",
            ),
        )

        assertTrue(diagnostics.any { it.path == "$.apiRoutes[0].write" })
    }

    @Test
    fun `duplicate component ids and commands are rejected`() {
        val diagnostics = TestPacks.parseInvalid(
            TestPacks.manifestJson(
                components = """[
                    {"id":"main","type":"js","entry":"a.js"},
                    {"id":"main","type":"js","entry":"b.js"}
                ]""",
                commands = """[
                    {"name":"hello","component":"main"},
                    {"name":"hello","component":"main"}
                ]""",
            ),
        )

        assertTrue(diagnostics.any { it.path == "$.components[1].id" })
        assertTrue(diagnostics.any { it.path == "$.commands[1].name" })
    }

    @Test
    fun `tool schema without type is normalized before publication`() {
        val manifest = TestPacks.parseValid(
            TestPacks.manifestJson(
                capabilities = """["SOURCE_COMMANDS","MCP_TOOLS"]""",
                mcpTools = """[{
                    "name":"demo_add",
                    "command":"hello",
                    "inputSchema":{"properties":{"a":{"type":"string"}}}
                }]""",
            ),
        )

        val schema = manifest.mcpTools.single().inputSchema
        assertEquals("object", (schema["type"] as JsonValue.Str).value)
    }

    @Test
    fun `tool schema rejects uppercase and invalid nested types`() {
        val uppercase = TestPacks.parseInvalid(
            TestPacks.manifestJson(
                capabilities = """["SOURCE_COMMANDS","MCP_TOOLS"]""",
                mcpTools = """[{
                    "name":"demo_add",
                    "command":"hello",
                    "inputSchema":{"type":"OBJECT"}
                }]""",
            ),
        )
        assertTrue(uppercase.any { it.path == "$.mcpTools[0].inputSchema.type" })

        val nested = TestPacks.parseInvalid(
            TestPacks.manifestJson(
                capabilities = """["SOURCE_COMMANDS","MCP_TOOLS"]""",
                mcpTools = """[{
                    "name":"demo_add",
                    "command":"hello",
                    "inputSchema":{
                        "type":"object",
                        "properties":{"a":{"type":17}}
                    }
                }]""",
            ),
        )
        assertTrue(nested.any { it.path == "$.mcpTools[0].inputSchema.properties.a.type" })
    }

    @Test
    fun `tool schema rejects unsupported keywords`() {
        val diagnostics = TestPacks.parseInvalid(
            TestPacks.manifestJson(
                capabilities = """["SOURCE_COMMANDS","MCP_TOOLS"]""",
                mcpTools = """[{
                    "name":"demo_add",
                    "command":"hello",
                    "inputSchema":{"type":"object","${'$'}ref":"#/definitions/x"}
                }]""",
            ),
        )

        assertTrue(diagnostics.any { it.path == "$.mcpTools[0].inputSchema.\$ref" })
    }

    @Test
    fun `invalid tool constraints are rejected through json parser`() {
        val diagnostics = TestPacks.parseInvalid(
            TestPacks.manifestJson(
                capabilities = """["SOURCE_COMMANDS","MCP_TOOLS"]""",
                mcpTools = """[{
                    "name":"demo_add",
                    "command":"hello",
                    "inputSchema":{
                        "type":"object",
                        "properties":{
                            "a":{"type":"string","minLength":-1.5},
                            "b":{"type":"array","maxItems":-1},
                            "c":{"type":"number","multipleOf":0},
                            "d":{"type":"string","pattern":"["}
                        }
                    }
                }]""",
            ),
        )

        assertTrue(diagnostics.any { it.path == "$.mcpTools[0].inputSchema.properties.a.minLength" })
        assertTrue(diagnostics.any { it.path == "$.mcpTools[0].inputSchema.properties.b.maxItems" })
        assertTrue(diagnostics.any { it.path == "$.mcpTools[0].inputSchema.properties.c.multipleOf" })
        assertTrue(diagnostics.any { it.path == "$.mcpTools[0].inputSchema.properties.d.pattern" })
    }

    @Test
    fun `malformed and non object json are rejected`() {
        assertTrue(
            TestPacks.parseInvalid("{").any { it.code == PackErrorCode.MANIFEST_INVALID },
        )
        assertTrue(
            TestPacks.parseInvalid("[]").any { it.code == PackErrorCode.MANIFEST_INVALID },
        )
    }
}