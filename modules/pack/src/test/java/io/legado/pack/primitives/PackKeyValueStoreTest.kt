package io.legado.pack.primitives

import io.legado.pack.api.PackCancelledException
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import io.legado.pack.api.PackResourceLimits
import io.legado.pack.api.PackStorageLayout
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

class PackKeyValueStoreTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private val limits = PackResourceLimits(maxKvKeyChars = 8, maxKvValueBytes = 32)

    private fun store(
        root: File,
        id: String = "kv-pack",
        active: AtomicBoolean = AtomicBoolean(true),
    ): PackKeyValueStoreImpl {
        val layout = PackStorageLayout(root)
        return PackKeyValueStoreImpl(
            layout = layout,
            id = PackId.require(id),
            limits = limits,
            guard = PackEpochGuard { active.get() },
        )
    }

    @Test
    fun valuesPersistAcrossStoreInstances() {
        val root = temp.newFolder("packs")
        val first = store(root)
        first.put("alpha", "one")
        first.put("beta", "two")
        assertEquals("one", first.get("alpha"))
        assertEquals(setOf("alpha", "beta"), first.keys())

        val second = store(root)
        assertEquals("one", second.get("alpha"))
        assertEquals("two", second.get("beta"))
        assertEquals(setOf("alpha", "beta"), second.keys())

        second.remove("alpha")
        assertNull(store(root).get("alpha"))
        assertEquals(setOf("beta"), store(root).keys())
    }

    @Test
    fun perPackDirectoriesAreIsolated() {
        val root = temp.newFolder("packs")
        val a = store(root, "pack-a")
        val b = store(root, "pack-b")
        a.put("shared", "a-value")
        b.put("shared", "b-value")
        assertEquals("a-value", store(root, "pack-a").get("shared"))
        assertEquals("b-value", store(root, "pack-b").get("shared"))
    }

    @Test
    fun quotaIsEnforced() {
        val root = temp.newFolder("packs")
        val kv = store(root)
        kv.put("ok", "x".repeat(32))
        assertEquals(32, kv.get("ok")!!.length)

        try {
            kv.put("too-big", "x".repeat(33))
            fail("expected value quota failure")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.IMPORT_LIMIT_EXCEEDED, e.diagnostic.code)
        }

        try {
            kv.put("key-too-long", "v")
            fail("expected key quota failure")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.IMPORT_LIMIT_EXCEEDED, e.diagnostic.code)
        }

        try {
            kv.put("", "v")
            fail("expected invalid key failure")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.INVALID_REQUEST, e.diagnostic.code)
        }
    }

    @Test
    fun unicodeKeysAndValuesRoundTrip() {
        val root = temp.newFolder("packs")
        val kv = store(root)
        val key = "键"
        val value = "值 with spaces & symbols ✓"
        kv.put(key, value)
        assertEquals(value, kv.get(key))
        assertEquals(setOf(key), kv.keys())
    }

    @Test
    fun staleEpochRejectsEveryOperation() {
        val root = temp.newFolder("packs")
        val active = AtomicBoolean(true)
        val kv = store(root, active = active)
        kv.put("k", "v")
        active.set(false)
        try {
            kv.get("k")
            fail("expected epoch failure")
        } catch (_: PackCancelledException) {
        }
        try {
            kv.put("k", "v2")
            fail("expected epoch failure")
        } catch (_: PackCancelledException) {
        }
        try {
            kv.remove("k")
            fail("expected epoch failure")
        } catch (_: PackCancelledException) {
        }
        try {
            kv.keys()
            fail("expected epoch failure")
        } catch (_: PackCancelledException) {
        }
    }

    @Test
    fun atomicWriteLeavesNoTemporaryFiles() {
        val root = temp.newFolder("packs")
        val kv = store(root)
        kv.put("k", "v")
        val kvDir = File(File(root, "kv-pack"), "kv")
        assertTrue(kvDir.isDirectory)
        val files = kvDir.listFiles()?.map { it.name } ?: emptyList()
        assertTrue(files.none { it.endsWith(".tmp") || it.endsWith(".bak") })
        assertFalse(files.isEmpty())
    }
}