package io.legado.app.help.pack

import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackGrant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class HostPackManagerTest {

    @Test
    fun `uninstall and disable revoke persisted write grants`() = runBlocking {
        val manifest = PackFixtures.manifest(toolName = "demo_write", isWrite = true)
        val info = PackFixtures.info(manifest)
        val manager = FakePackManager(listOf(info))
        val store = PackToolPermissionStore(
            Files.createTempDirectory("host-pack-manager").resolve("grants.json").toFile()
        )
        val host = HostPackManager(manager, store)
        val ref = PackFixtures.ref(manifest)

        assertTrue(store.setWriteToolEnabled(ref, manifest, "demo_write", true))
        host.uninstall(PackFixtures.packId)
        assertFalse(store.isWriteToolEnabled(ref, manifest, "demo_write"))

        manager.add(info)
        assertTrue(store.setWriteToolEnabled(ref, manifest, "demo_write", true))
        val disabled = host.setEnabled(PackFixtures.packId, false)
        assertFalse(disabled.isError)
        assertFalse(store.isWriteToolEnabled(ref, manifest, "demo_write"))
    }

    @Test
    fun `commitImport seeds write grants from the confirmed import selection`() = runBlocking {
        val manifest = PackFixtures.manifest(toolName = "demo_write", isWrite = true)
        val staleGrantInfo = PackFixtures.info(manifest, epoch = 1)
        val importedInfo = PackFixtures.info(manifest, epoch = 2)
        val manager = FakePackManager()
        val store = PackToolPermissionStore(
            Files.createTempDirectory("host-pack-import").resolve("grants.json").toFile()
        )
        val host = HostPackManager(manager, store)

        // A grant left over from an earlier install of the same pack id must
        // not survive a re-import, checked selection or not.
        assertTrue(store.setWriteToolEnabled(staleGrantInfo.ref, manifest, "demo_write", true))

        // Import dialog with the write tool left unchecked: nothing is granted.
        manager.enqueueImport(importedInfo)
        val unchecked = host.commitImport("token-unchecked", PackGrant("a".repeat(64)))
        assertEquals(importedInfo, unchecked)
        assertEquals(importedInfo, manager.info(PackFixtures.packId))
        assertFalse(store.isWriteToolEnabled(staleGrantInfo.ref, manifest, "demo_write"))
        assertFalse(store.isWriteToolEnabled(importedInfo.ref, manifest, "demo_write"))

        // Re-import with the write tool checked: the grant is seeded on.
        manager.enqueueImport(importedInfo)
        host.commitImport(
            "token-checked",
            PackGrant("a".repeat(64), toolPermissions = mapOf("demo_write" to true)),
        )
        assertTrue(store.isWriteToolEnabled(importedInfo.ref, manifest, "demo_write"))
    }

    @Test
    fun `a failing grants-file write does not revive a revoked grant after reinstall and restart`() =
        runBlocking {
            val root = Files.createTempDirectory("host-pack-revoke")
            val grantsFile = root.resolve("grants.json").toFile()
            val manifest = PackFixtures.manifest(toolName = "demo_write", isWrite = true)
            // Identical content reinstalled after an uninstall: the framework
            // restarts the admission epoch at 1, so revision, epoch and
            // permission digest all repeat.
            val firstInstall = PackFixtures.info(manifest, epoch = 1)
            val secondInstall = PackFixtures.info(manifest, epoch = 1)
            val manager = FakePackManager()
            val store = PackToolPermissionStore(grantsFile)
            val host = HostPackManager(manager, store)

            manager.enqueueImport(firstInstall)
            host.commitImport(
                "token-first",
                PackGrant("a".repeat(64), toolPermissions = mapOf("demo_write" to true)),
            )
            assertTrue(store.isWriteToolEnabled(firstInstall.ref, manifest, "demo_write"))

            // The grants file can no longer be replaced: its atomic-write tmp
            // path is occupied by a directory. Only the revocation journal in
            // the separate file keeps working.
            val fault = Files.createDirectory(root.resolve("grants.json.tmp"))
            host.uninstall(PackFixtures.packId)
            assertFalse(store.isWriteToolEnabled(firstInstall.ref, manifest, "demo_write"))

            // Same content, write permission left unchecked in the import
            // dialog: nothing is seeded, and the revocation must not be lost.
            manager.enqueueImport(secondInstall)
            val reimported = host.commitImport("token-second", PackGrant("a".repeat(64)))
            assertEquals(secondInstall, reimported)
            assertFalse(store.isWriteToolEnabled(secondInstall.ref, manifest, "demo_write"))

            // Process restart: the stale entry is still in the grants file,
            // but its generation is not newer than the durable journal.
            Files.delete(fault)
            val restarted = PackToolPermissionStore(grantsFile)
            assertFalse(restarted.isWriteToolEnabled(secondInstall.ref, manifest, "demo_write"))
            assertTrue(restarted.snapshot().isEmpty())

            // Fail closed does not mean bricked: an explicit authorization
            // after the restart grants a fresh generation.
            assertTrue(
                restarted.setWriteToolEnabled(secondInstall.ref, manifest, "demo_write", true)
            )
            assertTrue(restarted.isWriteToolEnabled(secondInstall.ref, manifest, "demo_write"))
        }

    @Test
    fun `uninstall attaches a red card when no durable revocation is possible`() = runBlocking {
        val root = Files.createTempDirectory("host-pack-revoke-undurable")
        val grantsFile = root.resolve("grants.json").toFile()
        val manifest = PackFixtures.manifest(toolName = "demo_write", isWrite = true)
        val info = PackFixtures.info(manifest, epoch = 1)
        val manager = FakePackManager(listOf(info))
        val store = PackToolPermissionStore(grantsFile)
        val host = HostPackManager(manager, store)
        assertTrue(store.setWriteToolEnabled(info.ref, manifest, "demo_write", true))

        // Break every durable channel: move the grants directory away and put
        // a regular file in its place, so the journal write, the grants write
        // and the destructive delete all fail.
        Files.move(root, root.parent.resolve(root.fileName.toString() + "-moved"))
        Files.createFile(root)

        val report = host.uninstall(PackFixtures.packId)
        assertEquals(PackErrorCode.IO_FAILED, report.failure?.code)
        assertNotNull(report.failure?.message)
        assertFalse(report.clean)
        // In-process fail closed: the revoked pack is denied until repaired.
        assertFalse(store.isWriteToolEnabled(info.ref, manifest, "demo_write"))
        // A retry (the reinstall path calls removePack again) still red-flags
        // instead of silently returning.
        try {
            store.removePack(PackFixtures.packId)
            fail("expected the undurable revocation to throw")
        } catch (expected: PackException) {
            assertEquals(PackErrorCode.IO_FAILED, expected.diagnostic.code)
        }
    }
}