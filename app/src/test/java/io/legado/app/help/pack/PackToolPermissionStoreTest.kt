package io.legado.app.help.pack

import io.legado.pack.api.PackId
import io.legado.pack.api.PackRef
import io.legado.pack.api.RevisionId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PackToolPermissionStoreTest {

    @Test
    fun `write tools are denied by default and grants persist`() {
        val file = Files.createTempDirectory("pack-permissions").resolve("grants.json").toFile()
        val manifest = PackFixtures.manifest(toolName = "demo_write", isWrite = true)
        val ref = PackFixtures.ref(manifest)
        val store = PackToolPermissionStore(file)

        assertFalse(store.isWriteToolEnabled(ref, manifest, "demo_write"))
        assertTrue(store.setWriteToolEnabled(ref, manifest, "demo_write", true))
        assertTrue(store.isWriteToolEnabled(ref, manifest, "demo_write"))

        val reloaded = PackToolPermissionStore(file)
        assertTrue(reloaded.isWriteToolEnabled(ref, manifest, "demo_write"))
    }

    @Test
    fun `a different revision or permission digest invalidates an old grant`() {
        val file = Files.createTempDirectory("pack-permissions").resolve("grants.json").toFile()
        val manifest = PackFixtures.manifest(toolName = "demo_write", isWrite = true)
        val ref = PackFixtures.ref(manifest)
        val store = PackToolPermissionStore(file)
        assertTrue(store.setWriteToolEnabled(ref, manifest, "demo_write", true))

        val nextRevision = PackRef(
            id = ref.id,
            revision = RevisionId.require("b".repeat(64)),
            epoch = ref.epoch + 1,
        )
        assertFalse(store.isWriteToolEnabled(nextRevision, manifest, "demo_write"))

        val changedManifest = manifest.copy(
            capabilities = manifest.capabilities - io.legado.pack.api.PackCapability.CRYPTO,
        )
        assertFalse(store.isWriteToolEnabled(ref, changedManifest, "demo_write"))
    }

    @Test
    fun `read tools cannot be promoted through the write grant API`() {
        val file = Files.createTempDirectory("pack-permissions").resolve("grants.json").toFile()
        val manifest = PackFixtures.manifest(toolName = "demo_read", isWrite = false)
        val ref = PackFixtures.ref(manifest)
        val store = PackToolPermissionStore(file)

        assertFalse(store.setWriteToolEnabled(ref, manifest, "demo_read", true))
        assertFalse(store.isWriteToolEnabled(ref, manifest, "demo_read"))
    }

    @Test
    fun `uninstall removes the persisted grant`() {
        val file = Files.createTempDirectory("pack-permissions").resolve("grants.json").toFile()
        val manifest = PackFixtures.manifest(toolName = "demo_write", isWrite = true)
        val ref = PackFixtures.ref(manifest)
        val store = PackToolPermissionStore(file)
        assertTrue(store.setWriteToolEnabled(ref, manifest, "demo_write", true))

        store.removePack(PackId.require("demo.pack"))

        assertFalse(store.isWriteToolEnabled(ref, manifest, "demo_write"))
        assertTrue(PackToolPermissionStore(file).snapshot().isEmpty())
    }
}