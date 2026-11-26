package io.legado.pack.storage

import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackResourceLimits
import io.legado.pack.api.RelativePath
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile

public data class ZipEntryInfo(
    val name: String,
    val relative: String?,
    val directory: Boolean,
    val method: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
)

/**
 * ZIP validation is deliberately independent from [ZipFile] extraction:
 *
 * * `java.util.zip` does not expose Unix mode bits, so symlink entries would
 *   otherwise look like ordinary files.
 * * Every name is validated with [RelativePath] before any file is created,
 *   which rejects `..`, absolute paths, backslashes, drive letters, NULs and
 *   empty segments instead of relying on string prefix checks.
 * * Entry count, declared single-file and total uncompressed sizes are bounded
 *   from the central directory *and* again while copying.
 */
public object PackZipArchive {

    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
    private const val ZIP64_EOCD_SIGNATURE = 0x06064b50L
    private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
    private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
    private const val UTF8_FLAG = 0x0800
    private const val MAX_CENTRAL_DIRECTORY_READ = 512L * 1024 * 1024

    fun listEntries(file: File, limits: PackResourceLimits): List<ZipEntryInfo> {
        if (!file.isFile) fail(PackErrorCode.IO_FAILED, "archive is missing", file.name)
        val archiveLength = file.length()
        if (archiveLength > limits.maxPackBytes) {
            fail(
                PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                "archive exceeds ${limits.maxPackBytes} bytes",
                file.name,
            )
        }
        return try {
            RandomAccessFile(file, "r").use { raf ->
            val eocd = readEndOfCentralDirectory(raf, archiveLength)
            if (eocd.diskNumber != 0L || eocd.centralDirectoryDisk != 0L) {
                fail(PackErrorCode.UNSUPPORTED, "multi-disk ZIP archives are not supported", file.name)
            }
            if (eocd.entries != eocd.entriesOnDisk) {
                fail(PackErrorCode.UNSUPPORTED, "multi-disk ZIP archives are not supported", file.name)
            }
            if (eocd.entries < 0L || eocd.entries > limits.maxFileCount.toLong()) {
                fail(
                    PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                    "archive entry count ${eocd.entries} is outside the allowed range",
                    file.name,
                )
            }
            if (eocd.centralDirectorySize > MAX_CENTRAL_DIRECTORY_READ) {
                fail(
                    PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                    "central directory is too large",
                    file.name,
                )
            }
            val cdEnd = eocd.centralDirectoryOffset + eocd.centralDirectorySize
            if (eocd.centralDirectoryOffset < 0L ||
                eocd.centralDirectorySize < 0L ||
                cdEnd > archiveLength
            ) {
                fail(PackErrorCode.MANIFEST_INVALID, "central directory is outside the archive", file.name)
            }

            val entries = ArrayList<ZipEntryInfo>(eocd.entries.toInt())
            val seenNames = HashSet<String>()
            val filePaths = HashSet<String>()
            var totalUncompressed = 0L
            raf.seek(eocd.centralDirectoryOffset)
            var index = 0L
            while (index < eocd.entries) {
                val fixed = ByteArray(46)
                raf.readFully(fixed)
                if (u32(fixed, 0) != CENTRAL_HEADER_SIGNATURE) {
                    fail(
                        PackErrorCode.MANIFEST_INVALID,
                        "invalid central directory entry $index",
                        file.name,
                    )
                }
                val versionMadeBy = u16(fixed, 4)
                val flags = u16(fixed, 8)
                val method = u16(fixed, 10)
                var uncompressed = u32(fixed, 24)
                var compressed = u32(fixed, 20)
                val nameLength = u16(fixed, 28)
                val extraLength = u16(fixed, 30)
                val commentLength = u16(fixed, 32)
                var startDisk = u16(fixed, 34)
                val externalAttributes = u32(fixed, 38)
                var localHeaderOffset = u32(fixed, 42)
                if (nameLength == 0) {
                    fail(PackErrorCode.MANIFEST_INVALID, "archive entry has an empty name", file.name)
                }
                val recordLength = 46 + nameLength + extraLength + commentLength
                if (raf.filePointer + recordLength - 46L > cdEnd) {
                    fail(PackErrorCode.MANIFEST_INVALID, "central directory entry overruns its bounds", file.name)
                }
                val nameBytes = ByteArray(nameLength)
                raf.readFully(nameBytes)
                val extra = ByteArray(extraLength)
                raf.readFully(extra)
                if (commentLength > 0) raf.skipBytes(commentLength)
                val afterRecordPosition = raf.filePointer

                if ((flags and 0x1) != 0) {
                    fail(PackErrorCode.MANIFEST_INVALID, "encrypted ZIP entries are not supported", file.name)
                }
                if (method != 0 && method != 8) {
                    fail(
                        PackErrorCode.UNSUPPORTED,
                        "ZIP compression method $method is not supported",
                        file.name,
                    )
                }

                var uncompressed64 = uncompressed
                var compressed64 = compressed
                var localOffset64 = localHeaderOffset
                var startDisk64 = startDisk.toLong()
                if (uncompressed == 0xFFFFFFFFL ||
                    compressed == 0xFFFFFFFFL ||
                    localHeaderOffset == 0xFFFFFFFFL ||
                    startDisk == 0xFFFF
                ) {
                    val zip64 = parseZip64Extra(
                        extra = extra,
                        needUncompressed = uncompressed == 0xFFFFFFFFL,
                        needCompressed = compressed == 0xFFFFFFFFL,
                        needOffset = localHeaderOffset == 0xFFFFFFFFL,
                        needDisk = startDisk == 0xFFFF,
                    ) ?: fail(
                        PackErrorCode.MANIFEST_INVALID,
                        "ZIP64 entry is missing its size/offset information",
                        file.name,
                    )
                    if (uncompressed == 0xFFFFFFFFL) {
                        uncompressed64 = zip64.uncompressed
                            ?: fail(PackErrorCode.MANIFEST_INVALID, "ZIP64 uncompressed size is missing", file.name)
                    }
                    if (compressed == 0xFFFFFFFFL) {
                        compressed64 = zip64.compressed
                            ?: fail(PackErrorCode.MANIFEST_INVALID, "ZIP64 compressed size is missing", file.name)
                    }
                    if (localHeaderOffset == 0xFFFFFFFFL) {
                        localOffset64 = zip64.localHeaderOffset
                            ?: fail(PackErrorCode.MANIFEST_INVALID, "ZIP64 local header offset is missing", file.name)
                    }
                    if (startDisk == 0xFFFF) {
                        startDisk64 = zip64.startDisk
                            ?: fail(PackErrorCode.MANIFEST_INVALID, "ZIP64 disk number is missing", file.name)
                    }
                }
                if (startDisk64 != 0L) {
                    fail(PackErrorCode.UNSUPPORTED, "multi-disk ZIP archives are not supported", file.name)
                }
                if (uncompressed64 < 0L || compressed64 < 0L || localOffset64 < 0L) {
                    fail(PackErrorCode.MANIFEST_INVALID, "negative ZIP size or offset", file.name)
                }
                if (localOffset64 + 4L > archiveLength) {
                    fail(PackErrorCode.MANIFEST_INVALID, "local header offset is outside the archive", file.name)
                }
                raf.seek(localOffset64)
                val localSignature = ByteArray(4)
                raf.readFully(localSignature)
                if (u32(localSignature, 0) != LOCAL_HEADER_SIGNATURE) {
                    fail(PackErrorCode.MANIFEST_INVALID, "invalid local file header", file.name)
                }
                raf.seek(afterRecordPosition)

                val nameUtf8 = (flags and UTF8_FLAG) != 0
                val name = decodeEntryName(nameBytes, nameUtf8, file.name)
                if (name.contains('\u0000')) {
                    fail(PackErrorCode.MANIFEST_INVALID, "archive entry name contains NUL", file.name)
                }
                val directory = name.endsWith("/")
                val pathPart = if (directory) name.dropLast(1) else name
                if (pathPart.isEmpty()) {
                    fail(PackErrorCode.MANIFEST_INVALID, "archive entry has an empty path", file.name)
                }
                val relative = RelativePath.parse(pathPart)
                    ?: fail(
                        PackErrorCode.MANIFEST_INVALID,
                        "unsafe archive entry name '$name'",
                        name,
                    )
                if (directory && uncompressed64 != 0L) {
                    fail(PackErrorCode.MANIFEST_INVALID, "directory entry '$name' has content", name)
                }
                val normalized = relative.value.lowercase(Locale.ROOT)
                if (!seenNames.add(normalized)) {
                    fail(
                        PackErrorCode.MANIFEST_INVALID,
                        "duplicate or case-ambiguous archive entry '$relative'",
                        relative.value,
                    )
                }
                validateEntryType(
                    directory = directory,
                    versionMadeBy = versionMadeBy,
                    externalAttributes = externalAttributes,
                    name = name,
                    archive = file.name,
                )
                if (!directory) {
                    if (uncompressed64 > limits.maxSingleFileBytes) {
                        fail(
                            PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                            "entry '$relative' exceeds ${limits.maxSingleFileBytes} bytes",
                            relative.value,
                        )
                    }
                    if (totalUncompressed > Long.MAX_VALUE - uncompressed64) {
                        fail(PackErrorCode.IMPORT_LIMIT_EXCEEDED, "archive size overflows", file.name)
                    }
                    totalUncompressed += uncompressed64
                    if (totalUncompressed > limits.maxUncompressedBytes) {
                        fail(
                            PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                            "archive expands beyond ${limits.maxUncompressedBytes} bytes",
                            file.name,
                        )
                    }
                    filePaths += normalized
                }
                entries += ZipEntryInfo(
                    name = name,
                    relative = if (directory) null else relative.value,
                    directory = directory,
                    method = method,
                    compressedSize = compressed64,
                    uncompressedSize = uncompressed64,
                    localHeaderOffset = localOffset64,
                )
                index++
            }

            // A file path must never also be a parent directory of another entry.
            entries.forEach { entry ->
                val relative = entry.relative ?: return@forEach
                val segments = relative.split('/')
                var prefix = StringBuilder()
                for (i in 0 until segments.size - 1) {
                    if (i > 0) prefix.append('/')
                    prefix.append(segments[i])
                    if (prefix.toString().lowercase(Locale.ROOT) in filePaths) {
                        fail(
                            PackErrorCode.MANIFEST_INVALID,
                            "archive entry '$relative' is nested below a file entry '$prefix'",
                            relative,
                        )
                    }
                }
            }

            if (entries.none { !it.directory && it.relative == "pack.json" }) {
                fail(
                    PackErrorCode.MANIFEST_INVALID,
                    "archive is missing ${io.legado.pack.api.PackArchiveSpec.ZIP_MANIFEST_ENTRY}",
                    io.legado.pack.api.PackArchiveSpec.ZIP_MANIFEST_ENTRY,
                )
            }
            entries
            }
        } catch (e: PackException) {
            throw e
        } catch (e: Exception) {
            fail(
                PackErrorCode.MANIFEST_INVALID,
                "cannot read ZIP archive: ${e.message ?: e.javaClass.simpleName}",
                file.name,
                e,
            )
        }
    }

    fun readManifest(file: File, maxChars: Int, limits: PackResourceLimits): String {
        val maxBytes = minOf(maxChars.toLong() * 4L + 4L, limits.maxSingleFileBytes)
        val bytes = try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(io.legado.pack.api.PackArchiveSpec.ZIP_MANIFEST_ENTRY)
                    ?: fail(
                        PackErrorCode.MANIFEST_INVALID,
                        "archive is missing ${io.legado.pack.api.PackArchiveSpec.ZIP_MANIFEST_ENTRY}",
                        io.legado.pack.api.PackArchiveSpec.ZIP_MANIFEST_ENTRY,
                    )
                zip.getInputStream(entry).use { input ->
                    readBounded(input, maxBytes, io.legado.pack.api.PackArchiveSpec.ZIP_MANIFEST_ENTRY)
                }
            }
        } catch (e: PackException) {
            throw e
        } catch (e: Exception) {
            fail(PackErrorCode.IO_FAILED, "cannot read archive manifest: ${e.message}", file.name, e)
        }
        val text = decodeUtf8(bytes, io.legado.pack.api.PackArchiveSpec.ZIP_MANIFEST_ENTRY)
        if (text.removePrefix("\uFEFF").length > maxChars) {
            fail(
                PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                "manifest exceeds $maxChars characters",
                io.legado.pack.api.PackArchiveSpec.ZIP_MANIFEST_ENTRY,
            )
        }
        return text
    }

    fun extract(
        archive: File,
        entries: List<ZipEntryInfo>,
        destination: File,
        limits: PackResourceLimits,
        checkCancelled: () -> Unit = {},
    ) {
        if (!destination.exists() && !destination.mkdirs() && !destination.isDirectory) {
            fail(PackErrorCode.IO_FAILED, "cannot create staging directory", destination.path)
        }
        val root = destination.canonicalFile
        var total = 0L
        val zip = try {
            ZipFile(archive)
        } catch (e: Exception) {
            fail(PackErrorCode.IO_FAILED, "cannot open archive: ${e.message}", archive.name, e)
        }
        zip.use { zipFile ->
            if (zipFile.size() != entries.size) {
                fail(PackErrorCode.MANIFEST_INVALID, "archive entry count changed while reading", archive.name)
            }
            entries.forEach { entry ->
                checkCancelled()
                if (entry.directory) {
                    val directory = resolveWithin(root, entry.name.dropLast(1))
                    if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
                        fail(PackErrorCode.IO_FAILED, "cannot create directory", entry.name)
                    }
                    return@forEach
                }
                val relative = entry.relative
                    ?: fail(PackErrorCode.MANIFEST_INVALID, "file entry has no path", entry.name)
                val target = resolveWithin(root, relative)
                val parent = target.parentFile
                    ?: fail(PackErrorCode.IO_FAILED, "entry has no parent directory", relative)
                if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                    fail(PackErrorCode.IO_FAILED, "cannot create parent directory", relative)
                }
                if (target.exists()) {
                    fail(PackErrorCode.MANIFEST_INVALID, "duplicate archive path", relative)
                }
                val zipEntry = zipFile.getEntry(entry.name)
                    ?: fail(PackErrorCode.IO_FAILED, "archive entry disappeared", entry.name)
                var written = 0L
                try {
                    zipFile.getInputStream(zipEntry).use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                checkCancelled()
                                val read = input.read(buffer)
                                if (read < 0) break
                                written += read
                                total += read
                                if (written > entry.uncompressedSize ||
                                    written > limits.maxSingleFileBytes ||
                                    total > limits.maxUncompressedBytes
                                ) {
                                    fail(
                                        PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                                        "entry '$relative' exceeded its declared size",
                                        relative,
                                    )
                                }
                                output.write(buffer, 0, read)
                            }
                            output.flush()
                            output.fd.sync()
                        }
                    }
                } catch (e: CancellationException) {
                    target.delete()
                    throw e
                } catch (e: PackException) {
                    target.delete()
                    throw e
                } catch (e: Exception) {
                    target.delete()
                    fail(
                        PackErrorCode.IO_FAILED,
                        "cannot extract '$relative': ${e.message}",
                        relative,
                        e,
                    )
                }
                if (written != entry.uncompressedSize) {
                    target.delete()
                    fail(
                        PackErrorCode.MANIFEST_INVALID,
                        "entry '$relative' size ${written} differs from declared ${entry.uncompressedSize}",
                        relative,
                    )
                }
            }
        }
    }

    private data class EndOfCentralDirectory(
        val diskNumber: Long,
        val centralDirectoryDisk: Long,
        val entriesOnDisk: Long,
        val entries: Long,
        val centralDirectorySize: Long,
        val centralDirectoryOffset: Long,
    )

    private data class Zip64Extra(
        val uncompressed: Long?,
        val compressed: Long?,
        val localHeaderOffset: Long?,
        val startDisk: Long?,
    )

    private fun readEndOfCentralDirectory(raf: RandomAccessFile, length: Long): EndOfCentralDirectory {
        if (length < 22L) {
            fail(PackErrorCode.MANIFEST_INVALID, "not a ZIP archive (file is too short)", "archive")
        }
        val tailLength = minOf(length, 22L + 0xFFFFL + 20L).toInt()
        val tail = ByteArray(tailLength)
        raf.seek(length - tailLength)
        raf.readFully(tail)
        var eocdIndex = -1
        for (i in tail.size - 22 downTo 0) {
            if (u32(tail, i) != EOCD_SIGNATURE) continue
            val commentLength = u16(tail, i + 20)
            if (i + 22 + commentLength == tail.size) {
                eocdIndex = i
                break
            }
        }
        if (eocdIndex < 0) {
            fail(PackErrorCode.MANIFEST_INVALID, "not a ZIP archive (missing end of central directory)", "archive")
        }
        val diskNumber = u16(tail, eocdIndex + 4).toLong()
        val cdDisk = u16(tail, eocdIndex + 6).toLong()
        val entriesOnDisk = u16(tail, eocdIndex + 8).toLong()
        var entries = u16(tail, eocdIndex + 10).toLong()
        var cdSize = u32(tail, eocdIndex + 12)
        var cdOffset = u32(tail, eocdIndex + 16)
        if (entries == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            val locatorPosition = length - tailLength + eocdIndex - 20
            if (locatorPosition < 0) {
                fail(PackErrorCode.MANIFEST_INVALID, "ZIP64 locator is missing", "archive")
            }
            raf.seek(locatorPosition)
            val locator = ByteArray(20)
            raf.readFully(locator)
            if (u32(locator, 0) != ZIP64_LOCATOR_SIGNATURE) {
                fail(PackErrorCode.MANIFEST_INVALID, "ZIP64 locator is missing", "archive")
            }
            val zip64Offset = i64(locator, 8)
            if (zip64Offset < 0L || zip64Offset + 56L > length) {
                fail(PackErrorCode.MANIFEST_INVALID, "ZIP64 record offset is invalid", "archive")
            }
            raf.seek(zip64Offset)
            val zip64 = ByteArray(56)
            raf.readFully(zip64)
            if (u32(zip64, 0) != ZIP64_EOCD_SIGNATURE) {
                fail(PackErrorCode.MANIFEST_INVALID, "ZIP64 record is missing", "archive")
            }
            val zip64Disk = u32(zip64, 16)
            val zip64CdDisk = u32(zip64, 20)
            val zip64EntriesOnDisk = i64(zip64, 24)
            entries = i64(zip64, 32)
            cdSize = i64(zip64, 40)
            cdOffset = i64(zip64, 48)
            return EndOfCentralDirectory(
                diskNumber = zip64Disk,
                centralDirectoryDisk = zip64CdDisk,
                entriesOnDisk = zip64EntriesOnDisk,
                entries = entries,
                centralDirectorySize = cdSize,
                centralDirectoryOffset = cdOffset,
            )
        }
        return EndOfCentralDirectory(
            diskNumber = diskNumber,
            centralDirectoryDisk = cdDisk,
            entriesOnDisk = entriesOnDisk,
            entries = entries,
            centralDirectorySize = cdSize,
            centralDirectoryOffset = cdOffset,
        )
    }

    private fun parseZip64Extra(
        extra: ByteArray,
        needUncompressed: Boolean,
        needCompressed: Boolean,
        needOffset: Boolean,
        needDisk: Boolean,
    ): Zip64Extra? {
        var position = 0
        while (position + 4 <= extra.size) {
            val id = u16(extra, position)
            val size = u16(extra, position + 2)
            val dataStart = position + 4
            val dataEnd = dataStart + size
            if (dataEnd > extra.size) return null
            if (id == 0x0001) {
                var cursor = dataStart
                var uncompressed: Long? = null
                var compressed: Long? = null
                var localHeaderOffset: Long? = null
                var startDisk: Long? = null
                if (needUncompressed) {
                    if (cursor + 8 > dataEnd) return null
                    uncompressed = i64(extra, cursor)
                    cursor += 8
                }
                if (needCompressed) {
                    if (cursor + 8 > dataEnd) return null
                    compressed = i64(extra, cursor)
                    cursor += 8
                }
                if (needOffset) {
                    if (cursor + 8 > dataEnd) return null
                    localHeaderOffset = i64(extra, cursor)
                    cursor += 8
                }
                if (needDisk) {
                    if (cursor + 4 > dataEnd) return null
                    startDisk = u32(extra, cursor)
                }
                return Zip64Extra(uncompressed, compressed, localHeaderOffset, startDisk)
            }
            position = dataEnd
        }
        return null
    }

    private fun validateEntryType(
        directory: Boolean,
        versionMadeBy: Int,
        externalAttributes: Long,
        name: String,
        archive: String,
    ) {
        val host = (versionMadeBy ushr 8) and 0xFF
        if (host != 3 && host != 19) return
        val modeType = ((externalAttributes ushr 16).toInt() and 0xF000)
        if (modeType == 0xA000) {
            fail(PackErrorCode.MANIFEST_INVALID, "symbolic link entry '$name' is not allowed", name)
        }
        if (modeType != 0 && modeType != 0x8000 && modeType != 0x4000) {
            fail(PackErrorCode.UNSUPPORTED, "unsupported ZIP entry type for '$name'", archive)
        }
        if (directory && modeType == 0x8000) {
            fail(PackErrorCode.MANIFEST_INVALID, "directory entry '$name' is marked as a regular file", name)
        }
        if (!directory && modeType == 0x4000) {
            fail(PackErrorCode.MANIFEST_INVALID, "file entry '$name' is marked as a directory", name)
        }
    }

    private fun resolveWithin(root: File, relative: String): File {
        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, relative).canonicalFile
        val rootPath = canonicalRoot.path
        val candidatePath = candidate.path
        if (candidatePath != rootPath &&
            !candidatePath.startsWith(rootPath + File.separator)
        ) {
            fail(
                PackErrorCode.MANIFEST_INVALID,
                "archive path escapes the staging directory: '$relative'",
                relative,
            )
        }
        return candidate
    }

    private fun decodeEntryName(bytes: ByteArray, utf8Flag: Boolean, archive: String): String {
        if (!utf8Flag && bytes.any { (it.toInt() and 0xff) > 0x7f }) {
            fail(
                PackErrorCode.MANIFEST_INVALID,
                "non-ASCII ZIP entry names require the UTF-8 flag",
                archive,
            )
        }
        return decodeUtf8(bytes, archive)
    }

    private fun decodeUtf8(bytes: ByteArray, path: String): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            fail(PackErrorCode.MANIFEST_INVALID, "invalid UTF-8 in archive", path, e)
        }
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Long, path: String): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                fail(PackErrorCode.IMPORT_LIMIT_EXCEEDED, "entry '$path' exceeds $maxBytes bytes", path)
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)

    private fun i64(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = result or ((bytes[offset + i].toLong() and 0xffL) shl (8 * i))
        }
        return result
    }

    private fun fail(
        code: PackErrorCode,
        message: String,
        path: String,
        cause: Throwable? = null,
    ): Nothing = throw PackException(PackDiagnostic(code = code, message = message, path = path), cause)
}