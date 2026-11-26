package io.legado.pack.storage

import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * File helpers for paths that must survive an interrupted write on a device
 * without `java.nio.file` (minSdk 23).
 *
 * Writes use `target.tmp` + fsync + rename. The previous target is moved to
 * `target.bak` during the swap and restored by [readBytes] when the process
 * died between the two renames. No helper follows a symlink when deleting or
 * walking a tree.
 */
internal object AtomicFiles {

    fun writeBytes(file: File, bytes: ByteArray, createParent: Boolean = true) {
        val parent = file.parentFile ?: throw ioFailure("file has no parent", file)
        if (!parent.exists()) {
            if (!createParent) throw ioFailure("parent directory is missing", parent)
            if (!parent.mkdirs() && !parent.isDirectory) {
                throw ioFailure("cannot create parent directory", parent)
            }
        }
        val temp = File(parent, file.name + ".tmp")
        val backup = File(parent, file.name + ".bak")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (file.exists()) {
                file.setWritable(true, false)
                if (backup.exists()) backup.delete()
                if (!file.renameTo(backup)) {
                    throw ioFailure("cannot move previous file to backup", file)
                }
            }
            if (!temp.renameTo(file)) {
                if (!file.exists() && backup.exists()) {
                    backup.renameTo(file)
                }
                throw ioFailure("cannot atomically replace file", file)
            }
            backup.delete()
        } catch (e: PackException) {
            temp.delete()
            throw e
        } catch (e: IOException) {
            temp.delete()
            throw ioFailure("cannot write file: ${e.message}", file, e)
        } finally {
            temp.delete()
        }
    }

    fun writeText(file: File, text: String, createParent: Boolean = true) {
        writeBytes(file, text.toByteArray(Charsets.UTF_8), createParent)
    }

    fun readBytes(file: File, maxBytes: Long, path: String = file.name): ByteArray {
        recoverIfNeeded(file)
        if (!file.isFile) throw ioFailure("file is missing", file)
        val length = file.length()
        if (length > maxBytes) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                    message = "file '$path' exceeds $maxBytes bytes",
                    path = path,
                ),
            )
        }
        return try {
            file.readBytes()
        } catch (e: IOException) {
            throw ioFailure("cannot read file: ${e.message}", file, e)
        }
    }

    fun readText(file: File, maxBytes: Long, path: String = file.name): String =
        String(readBytes(file, maxBytes, path), Charsets.UTF_8)

    fun recoverIfNeeded(file: File) {
        if (file.exists()) return
        val backup = File(file.parentFile, file.name + ".bak")
        if (backup.isFile) {
            backup.renameTo(file)
        }
    }

    /**
     * Deletes [file] and everything below it without following symlinks.
     * Returns true only when nothing remains at that path.
     */
    fun deleteRecursively(file: File): Boolean {
        if (!file.exists() && !isSymbolicLink(file)) return true
        if (isSymbolicLink(file) || !file.isDirectory) {
            file.setWritable(true, false)
            return file.delete() || !file.exists()
        }
        // The revision tree is deliberately read-only. Make the directory
        // writable before deleting its children, otherwise POSIX deletion
        // fails even though the files themselves are writable.
        file.setWritable(true, false)
        file.setReadable(true, false)
        file.setExecutable(true, false)
        val children = file.listFiles()
        if (children != null) {
            children.forEach { deleteRecursively(it) }
        }
        return file.delete() || !file.exists()
    }

    fun makeReadOnlyRecursively(file: File) {
        if (!file.exists()) return
        if (file.isDirectory && !isSymbolicLink(file)) {
            file.listFiles()?.forEach { makeReadOnlyRecursively(it) }
            file.setWritable(false, false)
        } else {
            file.setWritable(false, false)
        }
    }

    fun listRelativeFiles(root: File, excludeNames: Set<String> = emptySet()): List<Pair<String, File>> {
        if (!root.isDirectory) return emptyList()
        val result = ArrayList<Pair<String, File>>()
        fun walk(directory: File, prefix: String) {
            val children = directory.listFiles() ?: return
            children.sortedBy { it.name }.forEach { child ->
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (relative in excludeNames) return@forEach
                if (isSymbolicLink(child)) {
                    result += relative to child
                } else if (child.isDirectory) {
                    walk(child, relative)
                } else {
                    result += relative to child
                }
            }
        }
        walk(root, "")
        return result.sortedBy { it.first }
    }

    fun residualPaths(root: File, limit: Int = 50): List<String> {
        if (!root.exists() && !isSymbolicLink(root)) return emptyList()
        return listRelativeFiles(root).take(limit).map { it.first }
    }

    fun isSymbolicLink(file: File): Boolean {
        return try {
            val absolute = file.absoluteFile
            val canonical = file.canonicalFile
            if (File.separatorChar == '\\') {
                !absolute.path.equals(canonical.path, ignoreCase = true)
            } else {
                absolute.path != canonical.path
            }
        } catch (_: IOException) {
            false
        }
    }

    fun truncate(file: File): Long {
        val previous = if (file.exists()) file.length() else 0L
        try {
            if (file.exists()) {
                file.setWritable(true, false)
                FileOutputStream(file, false).use { it.fd.sync() }
            }
        } catch (_: IOException) {
            // A failed truncate is reported by the caller through remaining size.
        }
        return previous
    }

    fun copyBounded(input: InputStream, output: File, maxBytes: Long, path: String): Long {
        var total = 0L
        try {
            FileOutputStream(output).use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        throw PackException(
                            PackDiagnostic(
                                code = PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                                message = "entry '$path' exceeds $maxBytes bytes",
                                path = path,
                            ),
                        )
                    }
                    out.write(buffer, 0, read)
                }
                out.flush()
                out.fd.sync()
            }
        } catch (e: PackException) {
            output.delete()
            throw e
        } catch (e: IOException) {
            output.delete()
            throw ioFailure("cannot write entry '$path': ${e.message}", output, e)
        }
        return total
    }

    fun ioFailure(message: String, file: File, cause: Throwable? = null): PackException =
        PackException(
            PackDiagnostic(
                code = PackErrorCode.IO_FAILED,
                message = "$message (${file.path})",
            ),
            cause,
        )
}