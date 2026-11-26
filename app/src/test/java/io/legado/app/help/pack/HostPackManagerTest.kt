package io.legado.app.help.pack

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}