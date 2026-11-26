package io.legado.pack.storage

import io.legado.pack.api.PackCancelledException
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import io.legado.pack.api.PackStorageLayout
import io.legado.pack.api.RelativePath
import io.legado.pack.api.RevisionId
import io.legado.pack.primitives.PackEpochGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class PackStorageImplTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun resourcesAndDataFilesStayInsidePrivateDirectories() {
        val root = temp.newFolder("packs")
        val layout = PackStorageLayout(root)
        val id = PackId.require("storage-pack")
        val revision = RevisionId.require("a".repeat(64))
        val revisionDir = layout.revisionDir(id, revision)
        assertTrue(revisionDir.mkdirs())
        File(revisionDir, "main.js").writeText("hello")

        val active = AtomicBoolean(true)
        val storage = PackStorageImpl(layout, id, revision, PackEpochGuard { active.get() })
        val resource = RelativePath.require("main.js")
        assertEquals("hello", storage.openResource(resource).use { String(it.readBytes()) })
        assertTrue(storage.resourceExists(resource))
        assertFalse(storage.resourceExists(RelativePath.require("missing.js")))
        assertEquals(File(revisionDir, "main.js").canonicalFile, storage.revisionFile(resource)!!.canonicalFile)

        val data = storage.dataFile(RelativePath.require("nested/value.txt"))
        assertTrue(data.path.startsWith(File(File(root, "storage-pack"), "data").canonicalPath))
        assertTrue(data.parentFile.isDirectory)

        active.set(false)
        try {
            storage.openResource(resource)
            fail("expected epoch failure")
        } catch (_: PackCancelledException) {
        }
    }

    @Test
    fun missingResourceIsTypedRedCard() {
        val root = temp.newFolder("packs")
        val layout = PackStorageLayout(root)
        val id = PackId.require("storage-pack")
        val revision = RevisionId.require("b".repeat(64))
        assertTrue(layout.revisionDir(id, revision).mkdirs())
        val storage = PackStorageImpl(layout, id, revision, PackEpochGuard { true })
        try {
            storage.openResource(RelativePath.require("missing.js"))
            fail("expected COMPONENT_MISSING")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.COMPONENT_MISSING, e.diagnostic.code)
            assertTrue(e.diagnostic.isRedCard)
        }
        assertNull(storage.revisionFile(RelativePath.require("missing.js")))
    }
}