package io.legado.pack.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PackPermissionSetTest {

    private val id = PackId.require("hello-js")

    private fun permissions(
        network: NetworkPolicy = NetworkPolicy.None,
        tools: Set<String> = setOf("demo_add"),
        writeTools: Set<String> = emptySet(),
    ) = PackPermissionSet(
        packId = id,
        capabilities = setOf(PackCapability.SOURCE_COMMANDS, PackCapability.MCP_TOOLS),
        network = network,
        sourceCommands = setOf("hello"),
        mcpTools = tools,
        writeToolNames = writeTools,
        apiRoutes = setOf("status"),
    )

    @Test
    fun `digest is stable and changes with permissions`() {
        val first = permissions().digest()
        val second = permissions().digest()

        assertEquals(64, first.length)
        assertEquals(first, second)
        assertNotEquals(first, permissions(network = NetworkPolicy.Open("x")).digest())
        assertNotEquals(first, permissions(tools = setOf("demo_mul")).digest())
        assertNotEquals(first, permissions(writeTools = setOf("demo_add")).digest())
    }

    @Test
    fun `write tool names must be declared tools`() {
        assertThrows(IllegalArgumentException::class.java) {
            permissions(tools = setOf("demo_add"), writeTools = setOf("missing"))
            Unit
        }
    }

    @Test
    fun `grant requires explicit tool permission`() {
        val hash = permissions().digest()
        val grant = PackGrant(
            permissionHash = hash,
            networkOpenApproved = false,
            toolPermissions = mapOf("demo_add" to true, "demo_mul" to false),
        )

        assertTrue(grant.permitsTool("demo_add"))
        assertFalse(grant.permitsTool("demo_mul"))
        assertFalse(grant.permitsTool("unknown"))
        assertThrows(IllegalArgumentException::class.java) {
            PackGrant("not-a-hash")
            Unit
        }
    }

    @Test
    fun `grant approval binds the permission hash and open network consent`() {
        val closed = permissions()
        assertTrue(PackGrant(closed.digest()).approves(closed))

        val open = permissions(network = NetworkPolicy.Open("dynamic"))
        assertFalse(PackGrant(open.digest(), networkOpenApproved = false).approves(open))
        assertTrue(PackGrant(open.digest(), networkOpenApproved = true).approves(open))
        assertFalse(PackGrant(closed.digest(), networkOpenApproved = true).approves(open))
    }

    @Test
    fun `open network permission requires approval flag`() {
        val open = permissions(network = NetworkPolicy.Open("dynamic"))
        assertTrue(open.requiresOpenNetworkApproval)
        assertFalse(permissions().requiresOpenNetworkApproval)
    }

    @Test
    fun `uninstall report distinguishes clean closure from residue and restart`() {
        assertTrue(
            UninstallReport(id, registrationRevoked = true, residualPaths = emptyList(),
                loadedCodeStillResident = false, needsRestart = false).clean,
        )
        assertFalse(
            UninstallReport(id, registrationRevoked = true, residualPaths = listOf("logs/app.log"),
                loadedCodeStillResident = false, needsRestart = false).clean,
        )
        assertFalse(
            UninstallReport(id, registrationRevoked = true, residualPaths = emptyList(),
                loadedCodeStillResident = true, needsRestart = true).clean,
        )
        assertFalse(
            UninstallReport(id, registrationRevoked = false, residualPaths = emptyList(),
                loadedCodeStillResident = false, needsRestart = false).clean,
        )
    }
}