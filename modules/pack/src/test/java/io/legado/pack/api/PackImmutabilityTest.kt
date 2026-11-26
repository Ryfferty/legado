package io.legado.pack.api

import io.legado.pack.TestPacks
import io.legado.pack.registry.PackRegistration
import io.legado.pack.registry.PackRegistrationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PackImmutabilityTest {

    @Test
    fun `json objects and arrays copy input collections`() {
        val entries = mutableMapOf<String, JsonValue>("a" to JsonValue.Str("x"))
        val obj = JsonValue.Obj(entries)
        entries["b"] = JsonValue.Str("y")

        assertFalse(obj.entries.containsKey("b"))
        assertThrows(UnsupportedOperationException::class.java) {
            (obj.entries as MutableMap<String, JsonValue>)["c"] = JsonValue.Str("z")
            Unit
        }

        val items = mutableListOf<JsonValue>(JsonValue.Str("x"))
        val arr = JsonValue.Arr(items)
        items += JsonValue.Str("y")

        assertEquals(1, arr.items.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (arr.items as MutableList<JsonValue>).add(JsonValue.Str("z"))
            Unit
        }
    }

    @Test
    fun `manifest copies every collection`() {
        val source = TestPacks.parseValid(TestPacks.manifestJson())
        val components = source.components.toMutableList()
        val commands = source.commands.toMutableList()
        val capabilities = source.capabilities.toMutableSet()
        val tools = source.mcpTools.toMutableList()
        val routes = source.apiRoutes.toMutableList()
        val selfTests = source.selfTests.toMutableList()

        val copy = PackManifest(
            schemaVersion = source.schemaVersion,
            id = source.id,
            name = source.name,
            version = source.version,
            versionCode = source.versionCode,
            packLib = source.packLib,
            components = components,
            commands = commands,
            description = source.description,
            author = source.author,
            capabilities = capabilities,
            network = source.network,
            mcpTools = tools,
            apiRoutes = routes,
            selfTests = selfTests,
        )

        components.clear()
        commands.clear()
        capabilities.clear()
        tools.clear()
        routes.clear()
        selfTests.clear()

        assertEquals(1, copy.components.size)
        assertEquals(1, copy.commands.size)
        assertTrue(copy.capabilities.isNotEmpty())
        assertThrows(UnsupportedOperationException::class.java) {
            (copy.components as MutableList<ComponentSpec>).clear()
            Unit
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (copy.capabilities as MutableSet<PackCapability>).clear()
            Unit
        }
    }

    @Test
    fun `permission grant snapshot and preview copy their inputs`() {
        val source = TestPacks.parseValid(TestPacks.manifestJson())
        val capabilities = mutableSetOf(PackCapability.KV)
        val sourceCommands = mutableSetOf("hello")
        val tools = mutableSetOf("demo_add")
        val routes = mutableSetOf("status")
        val permissions = PackPermissionSet(
            packId = source.id,
            capabilities = capabilities,
            network = NetworkPolicy.None,
            sourceCommands = sourceCommands,
            mcpTools = tools,
            apiRoutes = routes,
        )
        capabilities.clear()
        sourceCommands.clear()
        tools.clear()
        routes.clear()

        assertTrue(PackCapability.KV in permissions.capabilities)
        assertTrue("hello" in permissions.sourceCommands)
        assertTrue("demo_add" in permissions.mcpTools)
        assertTrue("status" in permissions.apiRoutes)
        assertThrows(UnsupportedOperationException::class.java) {
            (permissions.capabilities as MutableSet<PackCapability>).clear()
            Unit
        }

        val toolPermissions = mutableMapOf("demo_add" to true)
        val routePermissions = mutableMapOf("status" to true)
        val grant = PackGrant(
            permissionHash = "a".repeat(64),
            toolPermissions = toolPermissions,
            routePermissions = routePermissions,
        )
        toolPermissions["demo_add"] = false
        routePermissions["status"] = false
        assertTrue(grant.permitsTool("demo_add"))
        assertTrue(grant.permitsRoute("status"))
        assertThrows(UnsupportedOperationException::class.java) {
            (grant.toolPermissions as MutableMap<String, Boolean>)["demo_add"] = false
            Unit
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (grant.routePermissions as MutableMap<String, Boolean>)["status"] = false
            Unit
        }

        val registration = PackRegistration(TestPacks.ref("hello-js"), source)
        val registrations = mutableMapOf(registration.ref.id to registration)
        val snapshot = PackRegistrationSnapshot(1L, registrations)
        registrations.clear()
        assertEquals(1, snapshot.registrations.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.registrations as MutableMap<PackId, PackRegistration>).clear()
            Unit
        }

        val warnings = mutableListOf(PackDiagnostic(PackErrorCode.INTERNAL, "warning"))
        val preview = ImportPreview(
            token = "preview-token",
            manifest = source,
            contentSha256 = RevisionId.require("a".repeat(64)),
            warnings = warnings,
        )
        warnings.clear()
        assertEquals(1, preview.warnings.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (preview.warnings as MutableList<PackDiagnostic>).clear()
            Unit
        }

        val patterns = mutableListOf(DomainPattern.require("*.example.com"))
        val policy = NetworkPolicy.Allowlist(patterns)
        patterns.clear()
        assertEquals(1, policy.patterns.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (policy.patterns as MutableList<DomainPattern>).clear()
            Unit
        }
    }
}