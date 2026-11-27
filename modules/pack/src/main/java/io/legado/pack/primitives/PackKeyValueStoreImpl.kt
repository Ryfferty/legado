package io.legado.pack.primitives

import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import io.legado.pack.api.PackKeyValueStore
import io.legado.pack.api.PackResourceLimits
import io.legado.pack.api.PackStorageLayout
import io.legado.pack.storage.AtomicFiles
import io.legado.pack.storage.ContentHasher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Per-pack KV store backed by one atomically replaced file per key.
 *
 * Key names are hashed so arbitrary Unicode keys never become file names. The
 * file header keeps the original key so [keys] can rebuild the set and a hash
 * collision is detected instead of returning another key's value.
 *
 * Quotas: `maxKvKeyChars` per key, `maxKvValueBytes` per value and a 64 MiB
 * total implementation guard (the public limits model has no total field).
 */
public class PackKeyValueStoreImpl(
    private val layout: PackStorageLayout,
    private val id: PackId,
    private val limits: PackResourceLimits,
    private val guard: PackEpochGuard,
) : PackKeyValueStore {

    private val lock = Any()
    private var cachedTotalBytes: Long? = null

    override fun get(key: String): String? {
        guard.ensureActive()
        validateKey(key)
        synchronized(lock) {
            val file = fileForKey(key)
            val existed = file.isFile
            AtomicFiles.recoverIfNeeded(file)
            if (!existed && file.isFile) cachedTotalBytes = null
            if (!file.isFile) return null
            return readEntry(file, key).second
        }
    }

    override fun put(key: String, value: String) {
        guard.ensureActive()
        validateKey(key)
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        if (valueBytes.size > limits.maxKvValueBytes) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                    message = "KV value exceeds ${limits.maxKvValueBytes} bytes",
                    path = "kv/$key",
                ),
            )
        }
        val encoded = encodeEntry(key, valueBytes)
        synchronized(lock) {
            val directory = kvDirectory()
            val file = fileForKey(key)
            val previousSize = if (file.isFile) file.length() else 0L
            val total = cachedTotalBytes ?: scanTotalBytes()
            val nextTotal = total - previousSize + encoded.size
            if (nextTotal > MAX_TOTAL_KV_BYTES) {
                throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                        message = "KV store exceeds $MAX_TOTAL_KV_BYTES bytes",
                        path = "kv",
                    ),
                )
            }
            guard.ensureActive()
            AtomicFiles.writeBytes(file, encoded)
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                throw AtomicFiles.ioFailure("cannot create KV directory", directory)
            }
            cachedTotalBytes = nextTotal
        }
    }

    override fun remove(key: String) {
        guard.ensureActive()
        validateKey(key)
        synchronized(lock) {
            val file = fileForKey(key)
            val previousSize = if (file.isFile) file.length() else 0L
            if (!file.delete() && file.exists()) {
                throw AtomicFiles.ioFailure("cannot delete KV entry", file)
            }
            File(file.parentFile, file.name + ".tmp").delete()
            File(file.parentFile, file.name + ".bak").delete()
            if (previousSize > 0L) {
                val total = cachedTotalBytes ?: scanTotalBytes()
                cachedTotalBytes = (total - previousSize).coerceAtLeast(0L)
            }
        }
    }

    override fun keys(): Set<String> {
        guard.ensureActive()
        synchronized(lock) {
            val directory = kvDirectory()
            if (!directory.isDirectory) return emptySet()
            val result = LinkedHashSet<String>()
            directory.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                if (file.name.endsWith("$SUFFIX.bak")) {
                    val target = File(file.parentFile, file.name.removeSuffix(".bak"))
                    if (!target.exists()) {
                        // Same contract as get()/AtomicFiles.recoverIfNeeded:
                        // a backup that cannot be restored is an explicit
                        // IO_FAILED failure for the whole listing. Returning
                        // keys that get() cannot read back would silently
                        // mask the broken recovery.
                        if (!file.renameTo(target)) {
                            throw AtomicFiles.ioFailure("cannot recover KV entry from backup", file)
                        }
                        cachedTotalBytes = null
                        // The directory listing predates the rename, so the
                        // restored key must be surfaced here instead of being
                        // dropped from this result until the next listing.
                        val entry = readEntry(target, expectedKey = null)
                        result += entry.first
                    }
                    return@forEach
                }
                if (!file.name.endsWith(SUFFIX)) return@forEach
                val entry = readEntry(file, expectedKey = null)
                result += entry.first
            }
            return result
        }
    }

    private fun validateKey(key: String) {
        if (key.isEmpty()) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.INVALID_REQUEST,
                    message = "KV key must not be empty",
                    path = "kv",
                ),
            )
        }
        if (key.length > limits.maxKvKeyChars) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                    message = "KV key exceeds ${limits.maxKvKeyChars} characters",
                    path = "kv",
                ),
            )
        }
        if (key.contains('\u0000')) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.INVALID_REQUEST,
                    message = "KV key contains NUL",
                    path = "kv",
                ),
            )
        }
    }

    private fun kvDirectory(): File {
        val directory = layout.kvDir(id)
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw AtomicFiles.ioFailure("cannot create KV directory", directory)
        }
        return directory
    }

    private fun fileForKey(key: String): File {
        val hash = ContentHasher.sha256(key.toByteArray(Charsets.UTF_8))
        return File(layout.kvDir(id), "$hash$SUFFIX")
    }

    private fun encodeEntry(key: String, valueBytes: ByteArray): ByteArray {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream(14 + keyBytes.size + valueBytes.size)
        DataOutputStream(output).use { data ->
            data.write(MAGIC)
            data.writeInt(keyBytes.size)
            data.write(keyBytes)
            data.write(valueBytes)
        }
        return output.toByteArray()
    }

    private fun readEntry(file: File, expectedKey: String?): Pair<String, String> {
        if (file.length() > limits.maxKvValueBytes + limits.maxKvKeyChars.toLong() * 4L + 16L) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.IO_FAILED,
                    message = "KV entry file exceeds its quota",
                    path = "kv/${file.name}",
                ),
            )
        }
        return try {
            DataInputStream(FileInputStream(file)).use { data ->
                val magic = ByteArray(MAGIC.size)
                data.readFully(magic)
                if (!magic.contentEquals(MAGIC)) {
                    throw IllegalStateException("invalid KV magic")
                }
                val keyLength = data.readInt()
                if (keyLength <= 0 || keyLength > limits.maxKvKeyChars * 4) {
                    throw IllegalStateException("invalid KV key length")
                }
                val keyBytes = ByteArray(keyLength)
                data.readFully(keyBytes)
                val key = String(keyBytes, Charsets.UTF_8)
                if (expectedKey != null && key != expectedKey) {
                    throw IllegalStateException("KV hash collision or corrupted key")
                }
                val valueLength = file.length() - MAGIC.size - 4L - keyLength
                if (valueLength < 0L || valueLength > limits.maxKvValueBytes) {
                    throw IllegalStateException("invalid KV value length")
                }
                val valueBytes = ByteArray(valueLength.toInt())
                data.readFully(valueBytes)
                key to String(valueBytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.IO_FAILED,
                    message = "cannot read KV entry: ${e.message}",
                    path = "kv/${file.name}",
                ),
                e,
            )
        }
    }

    private fun scanTotalBytes(): Long {
        var total = 0L
        layout.kvDir(id).listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(SUFFIX)) total += file.length()
        }
        cachedTotalBytes = total
        return total
    }

    companion object {
        private const val SUFFIX = ".kv"
        private val MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
        const val MAX_TOTAL_KV_BYTES: Long = 64L * 1024 * 1024
    }
}