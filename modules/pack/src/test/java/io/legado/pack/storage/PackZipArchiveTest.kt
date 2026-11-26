package io.legado.pack.storage

import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackResourceLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

class PackZipArchiveTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private val limits = PackResourceLimits()

    private fun archive(entries: Map<String, String>, name: String = "pack.zip"): File {
        val file = temp.newFile(name)
        io.legado.pack.manager.PackFixtures.writeZip(file, entries["pack.json"] ?: "{}", entries.filterKeys { it != "pack.json" })
        return file
    }

    private fun expectFailure(file: File, code: PackErrorCode): PackException {
        return try {
            PackZipArchive.listEntries(file, limits)
            fail("expected ${code.wire}")
            throw AssertionError("unreachable")
        } catch (e: PackException) {
            assertEquals(code, e.diagnostic.code)
            e
        }
    }

    @Test
    fun validArchiveListsAndExtractsFiles() {
        val file = archive(
            mapOf(
                "pack.json" to "{}",
                "main.js" to "hello",
                "dir/data.txt" to "data",
            ),
        )
        val entries = PackZipArchive.listEntries(file, limits)
        assertEquals(3, entries.size)
        val destination = temp.newFolder("out")
        PackZipArchive.extract(file, entries, destination, limits)
        assertEquals("hello", File(destination, "main.js").readText())
        assertEquals("data", File(destination, "dir/data.txt").readText())
    }

    @Test
    fun traversalAndAbsoluteNamesAreRejected() {
        val names = listOf(
            "../evil.js",
            "a/../../evil.js",
            "/absolute.js",
            "C:/evil.js",
            "a\\b.js",
            "a/./b.js",
            "a//b.js",
        )
        names.forEachIndexed { index, name ->
            val file = archive(mapOf("pack.json" to "{}", name to "x"), "evil-$index.zip")
            expectFailure(file, PackErrorCode.MANIFEST_INVALID)
        }
    }

    @Test
    fun duplicateAndCaseAmbiguousNamesAreRejected() {
        val file = archive(
            mapOf(
                "pack.json" to "{}",
                "A.js" to "a",
                "a.js" to "b",
            ),
        )
        val error = expectFailure(file, PackErrorCode.MANIFEST_INVALID)
        assertTrue(error.diagnostic.message.contains("duplicate", ignoreCase = true))
    }

    @Test
    fun missingManifestIsRejected() {
        val file = temp.newFile("no-manifest.zip")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("main.js"))
            zip.write("x".toByteArray())
            zip.closeEntry()
        }
        expectFailure(file, PackErrorCode.MANIFEST_INVALID)
    }

    @Test
    fun fileCountLimitIsEnforced() {
        val small = limits.copy(maxFileCount = 2)
        val file = archive(
            mapOf(
                "pack.json" to "{}",
                "a.js" to "a",
                "b.js" to "b",
            ),
        )
        try {
            PackZipArchive.listEntries(file, small)
            fail("expected file count failure")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.IMPORT_LIMIT_EXCEEDED, e.diagnostic.code)
        }
    }

    @Test
    fun declaredSingleFileSizeLimitIsEnforcedBeforeExtraction() {
        val small = limits.copy(
            maxPackBytes = 1_024,
            maxUncompressedBytes = 1_024,
            maxSingleFileBytes = 8,
        )
        val file = archive(
            mapOf(
                "pack.json" to "{}",
                "big.js" to "x".repeat(32),
            ),
        )
        try {
            PackZipArchive.listEntries(file, small)
            fail("expected single file limit failure")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.IMPORT_LIMIT_EXCEEDED, e.diagnostic.code)
        }
    }

    @Test
    fun totalExpansionLimitIsEnforced() {
        // Limits must satisfy maxUncompressedBytes >= maxPackBytes; DEFLATE keeps
        // the archive itself far below 1_024 bytes while the entries expand to 1_202.
        val small = limits.copy(
            maxPackBytes = 1_024,
            maxUncompressedBytes = 1_024,
            maxSingleFileBytes = 700,
        )
        val file = archive(
            mapOf(
                "pack.json" to "{}",
                "a.js" to "x".repeat(600),
                "b.js" to "y".repeat(600),
            ),
        )
        try {
            PackZipArchive.listEntries(file, small)
            fail("expected expansion limit failure")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.IMPORT_LIMIT_EXCEEDED, e.diagnostic.code)
            // Pin the aggregate check so the test cannot pass for another limit.
            assertTrue(e.diagnostic.message.contains("expands beyond"))
        }
    }

    @Test
    fun symlinkEntryIsRejectedFromCentralDirectoryModeBits() {
        val file = archive(
            mapOf(
                "pack.json" to "{}",
                "link" to "/etc/passwd",
            ),
            "symlink.zip",
        )
        patchAsSymlink(file, "link")
        val error = expectFailure(file, PackErrorCode.MANIFEST_INVALID)
        assertTrue(error.diagnostic.message.contains("symbolic link", ignoreCase = true))
    }

    private fun patchAsSymlink(file: File, entryName: String) {
        val bytes = file.readBytes()
        val eocd = findSignatureBackwards(bytes, 0x06054b50)
        require(eocd >= 0)
        val totalEntries = u16(bytes, eocd + 10)
        val centralOffset = u32(bytes, eocd + 16).toInt()
        var position = centralOffset
        repeat(totalEntries) {
            require(u32(bytes, position) == 0x02014b50L)
            val nameLength = u16(bytes, position + 28)
            val extraLength = u16(bytes, position + 30)
            val commentLength = u16(bytes, position + 32)
            val name = String(
                bytes,
                position + 46,
                nameLength,
                StandardCharsets.UTF_8,
            )
            if (name == entryName) {
                // version made by: host 3 (Unix), spec 20
                bytes[position + 4] = 20.toByte()
                bytes[position + 5] = 3.toByte()
                // external attributes: S_IFLNK | 0777, shifted into the high word
                val mode = 0xA1FFL
                val external = (mode shl 16)
                for (i in 0 until 4) {
                    bytes[position + 38 + i] = ((external ushr (8 * i)) and 0xFF).toByte()
                }
            }
            position += 46 + nameLength + extraLength + commentLength
        }
        file.writeBytes(bytes)
    }

    private fun findSignatureBackwards(bytes: ByteArray, signature: Int): Int {
        val target = signature.toLong() and 0xFFFFFFFFL
        val start = maxOf(0, bytes.size - 22 - 0xFFFF)
        for (index in bytes.size - 22 downTo start) {
            if (u32(bytes, index) == target) return index
        }
        return -1
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
}