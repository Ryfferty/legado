package io.legado.pack.manifest

import io.legado.pack.api.ComponentSpec
import io.legado.pack.api.NetworkPolicy
import io.legado.pack.api.PackArchiveSpec
import io.legado.pack.api.PackErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserScriptHeaderParserTest {

    private fun parse(source: String): ManifestParseResult =
        PackManifestParser().parseUserScript(source)

    private fun valid(source: String): ManifestParseResult.Valid {
        val result = parse(source)
        return result as? ManifestParseResult.Valid
            ?: throw AssertionError("expected valid header: $result")
    }

    private fun invalid(source: String): List<io.legado.pack.api.PackDiagnostic> {
        val result = parse(source)
        return (result as? ManifestParseResult.Invalid)?.diagnostics
            ?: throw AssertionError("expected invalid header: $result")
    }

    private val minimalHeader = """
        // ==Pack==
        // @id hello-js
        // @name Hello JS
        // @version 1.0.0
        // @versionCode 1
        // @packLib >=1.0.0 <2.0.0
        // @capability SOURCE_COMMANDS
        // @network none
        // @command hello source
        // ==/Pack==
    """.trimIndent()

    @Test
    fun `single file header produces one js component and fixed entry`() {
        val result = valid(
            minimalHeader + "\n\nfunction hello() { return \"hello\"; }\n",
        )

        assertEquals(PackArchiveSpec.SINGLE_FILE_JS_ENTRY, result.manifest.components.single().let {
            (it as ComponentSpec.Js).entry.value
        })
        assertTrue(result.manifest.network is NetworkPolicy.None)
        assertTrue(result.manifest.commands.single().sourceExported)
        assertFalse(result.manifest.permissions.requiresOpenNetworkApproval)
        assertTrue(result.singleFileBody!!.contains("function hello"))
        assertTrue(result.singleFileBodyStartLine!! > 0)
    }

    @Test
    fun `header pattern allowlist is parsed`() {
        val result = valid(
            minimalHeader.replace(
                "// @network none",
                "// @network patterns:*.example.com,api.test",
            ) + "\n\nfunction hello() { return 1; }\n",
        )

        val policy = result.manifest.network as NetworkPolicy.Allowlist
        assertTrue(policy.permits("cdn.example.com"))
        assertFalse(policy.permits("example.com"))
        assertTrue(policy.permits("api.test"))
    }

    @Test
    fun `header open network requests explicit consent`() {
        val result = valid(
            minimalHeader.replace(
                "// @network none",
                "// @network open:Dynamic host discovery",
            ) + "\n\nfunction hello() { return 1; }\n",
        )

        val policy = result.manifest.network as NetworkPolicy.Open
        assertEquals("Dynamic host discovery", policy.reason)
        assertTrue(result.manifest.permissions.requiresOpenNetworkApproval)
        assertTrue(result.warnings.any { it.code == PackErrorCode.NETWORK_OPEN_REQUIRES_CONSENT })
    }

    @Test
    fun `header mcp tool carries schema and write flag`() {
        val source = minimalHeader
            .replace(
                "// @capability SOURCE_COMMANDS",
                "// @capability SOURCE_COMMANDS\n// @capability MCP_TOOLS",
            )
            .replace(
                "// @command hello source",
                "// @command hello source\n" +
                    "// @mcpTool demo_add hello write " +
                    """{"type":"object","properties":{"a":{"type":"number"},"b":{"type":"number"}},"required":["a","b"]}""",
            ) + "\n\nfunction hello(a, b) { return a + b; }\n"

        val result = valid(source)
        val tool = result.manifest.mcpTools.single()
        assertEquals("demo_add", tool.name)
        assertEquals("hello", tool.command)
        assertTrue(tool.isWrite)
        assertTrue(tool.inputSchema.entries.containsKey("required"))
    }

    @Test
    fun `header api route and self test are parsed`() {
        val source = minimalHeader
            .replace(
                "// @capability SOURCE_COMMANDS",
                "// @capability SOURCE_COMMANDS\n// @capability API_ROUTES\n// @capability SELF_TEST",
            )
            .replace(
                "// @command hello source",
                "// @command hello source\n" +
                    "// @apiRoute status GET hello\n" +
                    "// @selfTest smoke hello 5000",
            ) + "\n\nfunction hello() { return 1; }\n"

        val result = valid(source)
        assertEquals("status", result.manifest.apiRoutes.single().name)
        assertEquals("smoke", result.manifest.selfTests.single().name)
        assertEquals(5000L, result.manifest.selfTests.single().timeoutMs)
    }

    @Test
    fun `duplicate single value header key is rejected`() {
        val source = minimalHeader
            .replace("// @id hello-js", "// @id hello-js\n// @id other-pack") +
            "\n\nfunction hello() { return 1; }\n"

        val diagnostics = invalid(source)
        assertTrue(diagnostics.any { it.message.contains("duplicate @id") })
        assertTrue(diagnostics.any { it.path?.startsWith("header.line") == true })
    }

    @Test
    fun `unknown header key and native component declaration are rejected`() {
        val source = minimalHeader
            .replace(
                "// @capability SOURCE_COMMANDS",
                "// @capability SOURCE_COMMANDS\n// @component native main libhello.so",
            ) + "\n\nfunction hello() { return 1; }\n"

        assertTrue(invalid(source).any { it.message.contains("unknown header key") })
    }

    @Test
    fun `missing end marker and empty body are rejected`() {
        assertTrue(
            invalid(
                minimalHeader.replace("// ==/Pack==", "") +
                    "\n\nfunction hello() { return 1; }\n",
            ).any { it.message.contains("missing") },
        )
        assertTrue(
            invalid(minimalHeader + "\n\n   \n").any { it.message.contains("body is empty") },
        )
    }

    @Test
    fun `header must be the first non blank line`() {
        val source = "// ordinary js comment\n" + minimalHeader +
            "\n\nfunction hello() { return 1; }\n"

        assertTrue(invalid(source).any { it.message.contains("first non-blank line") })
    }

    @Test
    fun `duplicate command names in header are rejected`() {
        val source = minimalHeader.replace(
            "// @command hello source",
            "// @command hello source\n// @command hello",
        ) + "\n\nfunction hello() { return 1; }\n"

        assertTrue(invalid(source).any { it.path == "$.commands[1].name" })
    }

    @Test
    fun `header tool schema numeric constraints are validated`() {
        val source = minimalHeader
            .replace(
                "// @capability SOURCE_COMMANDS",
                "// @capability SOURCE_COMMANDS\n// @capability MCP_TOOLS",
            )
            .replace(
                "// @command hello source",
                "// @command hello source\n" +
                    "// @mcpTool demo_add hello " +
                    """{"type":"object","properties":{"a":{"type":"string","minLength":-1}}}""",
            ) + "\n\nfunction hello() { return 1; }\n"

        assertTrue(
            invalid(source).any {
                it.path == "$.mcpTools[0].inputSchema.properties.a.minLength"
            },
        )
    }

    @Test
    fun `invalid versionCode and malformed tool schema are rejected`() {
        assertTrue(
            invalid(
                minimalHeader.replace("// @versionCode 1", "// @versionCode nope") +
                    "\n\nfunction hello() { return 1; }\n",
            ).any { it.message.contains("@versionCode") },
        )

        val badTool = minimalHeader
            .replace(
                "// @capability SOURCE_COMMANDS",
                "// @capability SOURCE_COMMANDS\n// @capability MCP_TOOLS",
            )
            .replace(
                "// @command hello source",
                "// @command hello source\n// @mcpTool demo_add hello {broken",
            ) + "\n\nfunction hello() { return 1; }\n"
        assertTrue(invalid(badTool).any { it.message.contains("schema is invalid JSON") })
    }
}