package io.legado.pack.storage

import io.legado.pack.api.RevisionId
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

internal object PackHex {
    private const val ALPHABET = "0123456789abcdef"

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(ALPHABET[value ushr 4])
            append(ALPHABET[value and 0x0f])
        }
    }
}

/**
 * Deterministic SHA-256 over pack content.
 *
 * The digest is the [RevisionId] and the immutable code directory name. It
 * covers the manifest and every staged file so a permission change alone
 * produces a different revision. File names and lengths are framed to avoid
 * concatenation ambiguity.
 *
 * Userscript packs have no `pack.json` resource; their digest therefore adds
 * the canonical manifest bytes under a synthetic name before `main.js`.
 */
internal class ContentHasher {

    private val digest = MessageDigest.getInstance("SHA-256")
    private var finished = false

    init {
        updateBytes("pack-content/v1\n".toByteArray(Charsets.UTF_8))
    }

    fun addBytes(relativeName: String, bytes: ByteArray) {
        check(!finished) { "content hasher is already finished" }
        require(relativeName.isNotEmpty())
        updateBytes(relativeName.toByteArray(Charsets.UTF_8))
        updateBytes(byteArrayOf(0))
        updateBytes(bytes.size.toString().toByteArray(Charsets.UTF_8))
        updateBytes(byteArrayOf(0))
        updateBytes(bytes)
        updateBytes(byteArrayOf(0))
    }

    fun addFile(relativeName: String, file: File) {
        check(!finished) { "content hasher is already finished" }
        require(relativeName.isNotEmpty())
        updateBytes(relativeName.toByteArray(Charsets.UTF_8))
        updateBytes(byteArrayOf(0))
        updateBytes(file.length().toString().toByteArray(Charsets.UTF_8))
        updateBytes(byteArrayOf(0))
        try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        } catch (e: IOException) {
            throw AtomicFiles.ioFailure("cannot hash file: ${e.message}", file, e)
        }
        updateBytes(byteArrayOf(0))
    }

    fun finish(): RevisionId {
        check(!finished) { "content hasher is already finished" }
        finished = true
        return RevisionId.require(PackHex.encode(digest.digest()))
    }

    private fun updateBytes(bytes: ByteArray) {
        digest.update(bytes)
    }

    companion object {

        fun digestDirectory(root: File, excludeNames: Set<String> = emptySet()): RevisionId {
            val hasher = ContentHasher()
            AtomicFiles.listRelativeFiles(root, excludeNames).forEach { (relative, file) ->
                if (file.isDirectory) return@forEach
                hasher.addFile(relative, file)
            }
            return hasher.finish()
        }

        fun digestUserscript(manifestJson: String, body: String): RevisionId {
            val hasher = ContentHasher()
            hasher.addBytes("pack.manifest.json", manifestJson.toByteArray(Charsets.UTF_8))
            hasher.addBytes("main.js", body.toByteArray(Charsets.UTF_8))
            return hasher.finish()
        }

        fun sha256(bytes: ByteArray): String =
            PackHex.encode(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}