package io.legado.pack.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackPathAndStorageTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `pack id rejects traversal case and reserved names`() {
        assertEquals("hello-js", PackId.require("hello-js").value)
        assertTrue(PackId.parse("a.b_c-1") != null)
        assertNull(PackId.parse("../evil"))
        assertNull(PackId.parse("hello/js"))
        assertNull(PackId.parse("HELLO"))
        assertNull(PackId.parse("hello-"))
        assertNull(PackId.parse("a"))
        assertNull(PackId.parse("con"))
        assertNull(PackId.parse("com1"))
        assertNull(PackId.parse(" hello"))
    }

    @Test
    fun `revision id is exactly a lower case sha256`() {
        assertEquals(64, RevisionId.require("a".repeat(64)).value.length)
        assertNull(RevisionId.parse("A".repeat(64)))
        assertNull(RevisionId.parse("a".repeat(63)))
        assertNull(RevisionId.parse("a".repeat(65)))
    }

    @Test
    fun `relative paths reject absolute windows and parent segments`() {
        assertEquals("ui/main.js", RelativePath.require("ui/main.js").value)
        assertNull(RelativePath.parse("/etc/passwd"))
        assertNull(RelativePath.parse("C:/windows/system32"))
        assertNull(RelativePath.parse("..\\main.js"))
        assertNull(RelativePath.parse("../main.js"))
        assertNull(RelativePath.parse("ui//main.js"))
        assertNull(RelativePath.parse("ui/./main.js"))
        assertNull(RelativePath.parse("ui/main.js/"))
        assertNull(RelativePath.parse("ui/中文.js"))
        assertNull(RelativePath.parse("ui/ main.js"))
    }

    @Test
    fun `layout derives every private path from an injected filesDir`() {
        val layout = PackStorageLayout.fromHostFilesDir(temp.root)
        val id = PackId.require("hello-js")
        val revision = RevisionId.require("a".repeat(64))

        assertEquals(File(layout.packsRoot, "hello-js"), layout.packDir(id))
        assertEquals(
            File(layout.revisionDir(id, revision), "ui/main.js").canonicalFile,
            layout.resolveInRevision(id, revision, RelativePath.require("ui/main.js")),
        )
        assertEquals(
            File(layout.dataDir(id), "state/kv.json").canonicalFile,
            layout.resolveInData(id, RelativePath.require("state/kv.json")),
        )
        assertEquals(File(layout.packDir(id), "current.json"), layout.currentFile(id))
        assertEquals(File(layout.packDir(id), "code"), layout.codeRoot(id))
        assertEquals(File(layout.packDir(id), "logs"), layout.logsDir(id))
        assertEquals(File(layout.packDir(id), "cookies"), layout.cookiesDir(id))
        assertEquals(File(layout.packDir(id), "opt"), layout.optDir(id))
    }

    @Test
    fun `layout rejects relative roots and unsafe staging tokens`() {
        assertThrows(IllegalArgumentException::class.java) {
            PackStorageLayout(File("relative-root"))
            Unit
        }

        val layout = PackStorageLayout.fromHostFilesDir(temp.root)
        val id = PackId.require("hello-js")
        assertThrows(IllegalArgumentException::class.java) {
            layout.stagingDir(id, "../escape")
            Unit
        }
        assertThrows(IllegalArgumentException::class.java) {
            layout.stagingDir(id, "short")
            Unit
        }
    }

    @Test
    fun `web routes use fixed pack prefix and reject unsafe segments`() {
        val id = PackId.require("hello-js")

        assertEquals("/packs/hello-js/api/status", PackRoutes.apiPath(id, "status"))
        assertEquals("/packs/hello-js/selftest/run_1", PackRoutes.selfTestPath(id, "run_1"))
        assertTrue(PackRoutes.isPackPath("/packs/hello-js/api/status"))
        assertThrows(IllegalArgumentException::class.java) {
            PackRoutes.apiPath(id, "../status")
            Unit
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackRoutes.selfTestPath(id, "../run")
            Unit
        }
    }
}