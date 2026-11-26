package io.legado.pack.api

import io.legado.pack.TestPacks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PackInvocationTest {

    private val ref = TestPacks.ref("hello-js")

    @Test
    fun `entry point and caller must match`() {
        PackInvocation(
            expectedRef = ref,
            entry = PackEntryPoint.SourceCommand("hello"),
            argJson = "{}",
            caller = PackCaller.Source("source-key"),
        )
        PackInvocation(
            expectedRef = ref,
            entry = PackEntryPoint.McpTool("demo_add"),
            argJson = "{}",
            caller = PackCaller.Mcp("client"),
        )
        PackInvocation(
            expectedRef = ref,
            entry = PackEntryPoint.WebRoute("status"),
            argJson = "{}",
            caller = PackCaller.Web("127.0.0.1"),
        )
        PackInvocation(
            expectedRef = ref,
            entry = PackEntryPoint.DirectCommand("hello"),
            argJson = "{}",
            caller = PackCaller.Host,
        )

        assertThrows(IllegalArgumentException::class.java) {
            PackInvocation(
                expectedRef = ref,
                entry = PackEntryPoint.SourceCommand("hello"),
                argJson = "{}",
                caller = PackCaller.Mcp("client"),
            )
            Unit
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackInvocation(
                expectedRef = ref,
                entry = PackEntryPoint.McpTool("demo_add"),
                argJson = "{}",
                caller = PackCaller.Web("127.0.0.1"),
            )
            Unit
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackInvocation(
                expectedRef = ref,
                entry = PackEntryPoint.WebRoute("status"),
                argJson = "{}",
                caller = PackCaller.Host,
            )
            Unit
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackInvocation(
                expectedRef = ref,
                entry = PackEntryPoint.DirectCommand("hello"),
                argJson = "{}",
                caller = PackCaller.Source("source-key"),
            )
            Unit
        }
    }

    @Test
    fun `entry names use their own schema`() {
        assertThrows(IllegalArgumentException::class.java) {
            PackEntryPoint.SourceCommand("Bad Name")
            Unit
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackEntryPoint.McpTool("")
            Unit
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackEntryPoint.WebRoute("../status")
            Unit
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackEntryPoint.DirectCommand("hello/")
            Unit
        }
    }

    @Test
    fun `route grant is explicit and separate from tool grant`() {
        val hash = "a".repeat(64)
        val grant = PackGrant(
            permissionHash = hash,
            toolPermissions = mapOf("demo_add" to true),
            routePermissions = mapOf("status" to true),
        )

        assertTrue(grant.permitsTool("demo_add"))
        assertTrue(grant.permitsRoute("status"))
        assertFalse(grant.permitsRoute("other"))
        assertFalse(PackGrant(hash).permitsRoute("status"))
        assertFalse(PackGrant(hash, routePermissions = mapOf("status" to false)).permitsRoute("status"))
    }
}