package io.legado.pack.registry

import io.legado.pack.TestPacks
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import io.legado.pack.api.PackManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PackRegistryTest {

    private fun manifest(id: String, tool: String? = null): PackManifest {
        val capabilities = if (tool == null) {
            TestPacks.DEFAULT_CAPABILITIES
        } else {
            """["SOURCE_COMMANDS","MCP_TOOLS"]"""
        }
        val tools = tool?.let { """[{"name":"$it","command":"hello","description":"test"}]""" }
        return TestPacks.parseValid(
            TestPacks.manifestJson(
                id = id,
                capabilities = capabilities,
                mcpTools = tools,
            ),
        )
    }

    private fun registration(id: String, tool: String? = null): PackRegistration =
        PackRegistration(TestPacks.ref(id), manifest(id, tool))

    @Test
    fun `replaceAll publishes one snapshot and rejects tool conflicts wholesale`() {
        val registry = PackRegistry()
        val packA = registration("pack-a", "demo_add")

        val applied = registry.replaceAll(listOf(packA))
        assertTrue(applied is RegistrationResult.Applied)
        assertEquals(1L, registry.snapshot().version)
        assertEquals(setOf("demo_add"), registry.snapshot().toolNames)

        val conflicting = registration("pack-c", "demo_add")
        val rejected = registry.replaceAll(listOf(packA, conflicting))
        assertTrue(rejected is RegistrationResult.Rejected)
        assertEquals(
            PackErrorCode.TOOL_CONFLICT,
            (rejected as RegistrationResult.Rejected).diagnostics.first().code,
        )
        assertEquals(1L, registry.snapshot().version)
        assertEquals(setOf("demo_add"), registry.snapshot().toolNames)
    }

    @Test
    fun `reserved tool names are rejected at registration`() {
        val registry = PackRegistry(reservedToolNames = setOf("demo_add"))

        val result = registry.replaceAll(listOf(registration("pack-a", "demo_add")))

        assertTrue(result is RegistrationResult.Rejected)
        assertEquals(
            PackErrorCode.TOOL_CONFLICT,
            (result as RegistrationResult.Rejected).diagnostics.first().code,
        )
    }

    @Test
    fun `reserved tool names are matched case-insensitively`() {
        val registry = PackRegistry(reservedToolNames = setOf("demo_add"))

        val result = registry.replaceAll(listOf(registration("pack-a", "DEMO_ADD")))

        // The bridge layer already treats reserved names case-insensitively;
        // the registry must reject the variant instead of admitting a tool
        // that would never install.
        assertTrue(result is RegistrationResult.Rejected)
        assertEquals(
            PackErrorCode.TOOL_CONFLICT,
            (result as RegistrationResult.Rejected).diagnostics.first().code,
        )
    }

    @Test
    fun `attach delivers the current snapshot and then later updates`() {
        val registry = PackRegistry()
        val seen = mutableListOf<PackRegistrationSnapshot>()

        val handle = registry.attach { seen += it }
        assertEquals(1, seen.size)
        assertTrue(seen.single().registrations.isEmpty())

        val applied = registry.replaceAll(listOf(registration("pack-a", "demo_add")))
        assertTrue(applied is RegistrationResult.Applied)
        assertEquals(2, seen.size)
        assertTrue(seen.last().registrations.containsKey(PackId.require("pack-a")))

        handle.close()
        registry.replaceAll(emptyList())
        assertEquals(2, seen.size)
    }

    @Test
    fun `a failing sink does not prevent other sinks from receiving the snapshot`() {
        val registry = PackRegistry()
        var calls = 0
        registry.attach {
            calls++
            if (calls > 1) throw IllegalStateException("observer failure")
        }
        val seen = mutableListOf<PackRegistrationSnapshot>()
        registry.attach { seen += it }

        val applied = registry.replaceAll(
            listOf(registration("pack-a", "demo_add")),
        ) as RegistrationResult.Applied

        assertTrue(applied.sinkFailures.isNotEmpty())
        assertTrue(seen.any { it.registrations.containsKey(PackId.require("pack-a")) })
    }

    @Test
    fun `closed registry rejects attach and replace`() {
        val registry = PackRegistry()
        registry.close()

        assertThrows(PackException::class.java) {
            registry.attach { }
            Unit
        }
        assertThrows(PackException::class.java) {
            registry.replaceAll(emptyList())
            Unit
        }
    }

    @Test
    fun `registration id must match manifest id`() {
        assertThrows(IllegalArgumentException::class.java) {
            PackRegistration(TestPacks.ref("pack-a"), manifest("pack-b", "demo_add"))
            Unit
        }
    }
}