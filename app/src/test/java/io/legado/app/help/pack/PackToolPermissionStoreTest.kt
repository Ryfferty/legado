package io.legado.app.help.pack

import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import io.legado.pack.api.PackRef
import io.legado.pack.api.RevisionId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test
    fun `a revocation that cannot be written falls back to deleting the grants file`() {
        val dir = Files.createTempDirectory("pack-permissions")
        val file = dir.resolve("grants.json").toFile()
        val manifest = PackFixtures.manifest(toolName = "demo_write", isWrite = true)
        val ref = PackFixtures.ref(manifest)
        val store = PackToolPermissionStore(file)
        assertTrue(store.setWriteToolEnabled(ref, manifest, "demo_write", true))

        // Block both atomic-write channels; deleting the grants file is the
        // remaining fail-closed escape.
        Files.createDirectory(dir.resolve("grants.json.tmp"))
        Files.createDirectory(dir.resolve("grants-revocations.json.tmp"))

        store.removePack(PackId.require("demo.pack"))

        assertFalse(file.exists())
        assertFalse(store.isWriteToolEnabled(ref, manifest, "demo_write"))
        assertFalse(PackToolPermissionStore(file).isWriteToolEnabled(ref, manifest, "demo_write"))
    }

    @Test
    fun `a revocation that can neither be journalled nor deleted persists a tombstone`() {
        val dir = Files.createTempDirectory("pack-permissions")
        val file = dir.resolve("grants.json").toFile()
        val manifest = PackFixtures.manifest(toolName = "demo_write", isWrite = true)
        val ref = PackFixtures.ref(manifest)
        val store = PackToolPermissionStore(file)
        assertTrue(store.setWriteToolEnabled(ref, manifest, "demo_write", true))

        // The stale grant content a restart would otherwise revive.
        val staleGrants = file.readText(Charsets.UTF_8)

        // Block both atomic-write channels, and occupy the grants path with a
        // non-empty directory so the destructive delete fails as well.
        Files.createDirectory(dir.resolve("grants.json.tmp"))
        Files.createDirectory(dir.resolve("grants-revocations.json.tmp"))
        file.delete()
        Files.createDirectory(file.toPath())
        Files.write(dir.resolve("grants.json/occupied"), byteArrayOf(1))

        // Creating a fresh file still works, so the tombstone channel makes
        // the revocation durable instead of leaving a process-local poison.
        store.removePack(PackId.require("demo.pack"))

        assertFalse(store.isWriteToolEnabled(ref, manifest, "demo_write"))
        assertTrue(dir.resolve("grants-undurable.json").toFile().isFile)

        // Restart with the stale grants file restored next to the tombstone:
        // the journal never recorded this revocation, so the stale entry's
        // generation is still ahead and only the tombstone keeps it dead —
        // even if a reinstall repeats the same admission epoch.
        Files.delete(dir.resolve("grants.json/occupied"))
        Files.delete(file.toPath())
        Files.delete(dir.resolve("grants.json.tmp"))
        Files.delete(dir.resolve("grants-revocations.json.tmp"))
        file.writeText(staleGrants, Charsets.UTF_8)
        val restarted = PackToolPermissionStore(file)
        assertFalse(restarted.isWriteToolEnabled(ref, manifest, "demo_write"))
        assertTrue(restarted.snapshot().isEmpty())

        // Fail closed does not mean bricked: an explicit re-grant clears the
        // tombstone entry and survives yet another restart.
        assertTrue(restarted.setWriteToolEnabled(ref, manifest, "demo_write", true))
        assertTrue(restarted.isWriteToolEnabled(ref, manifest, "demo_write"))
        assertTrue(PackToolPermissionStore(file).isWriteToolEnabled(ref, manifest, "demo_write"))
    }

    @Test
    fun `a revocation with no writable record at all throws and denies in-process`() {
        val dir = Files.createTempDirectory("pack-permissions")
        val file = dir.resolve("grants.json").toFile()
        val manifest = PackFixtures.manifest(toolName = "demo_write", isWrite = true)
        val ref = PackFixtures.ref(manifest)
        val store = PackToolPermissionStore(file)
        assertTrue(store.setWriteToolEnabled(ref, manifest, "demo_write", true))

        // Block every channel: both atomic writes, the grants-file delete
        // (non-empty directory in its place) and the fresh tombstone file.
        Files.createDirectory(dir.resolve("grants.json.tmp"))
        Files.createDirectory(dir.resolve("grants-revocations.json.tmp"))
        Files.createDirectory(dir.resolve("grants-undurable.json.tmp"))
        file.delete()
        Files.createDirectory(file.toPath())
        Files.write(dir.resolve("grants.json/occupied"), byteArrayOf(1))

        assertThrows(PackException::class.java) {
            store.removePack(PackId.require("demo.pack"))
        }
        assertFalse(store.isWriteToolEnabled(ref, manifest, "demo_write"))
        assertFalse(dir.resolve("grants-undurable.json").toFile().isFile)
    }
}