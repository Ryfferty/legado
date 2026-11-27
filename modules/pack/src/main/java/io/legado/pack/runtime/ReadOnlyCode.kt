package io.legado.pack.runtime

import android.os.Process
import android.system.Os
import android.system.OsConstants
import io.legado.pack.api.*
import io.legado.pack.storage.AtomicFiles
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * Materialize a private content-addressed copy without asking PackStorage for a
 * raw code path. The descriptor is opened O_EXCL with mode 0400 BEFORE writing
 * (Android 14+ dynamic-code-loading requirement). Only trusted DEX/native use it.
 */
internal object ReadOnlyCode {
    fun stage(context: PackContext, path: RelativePath, suffix: String, call: PackCallContext): File {
        val bytes = readResource(context, path, call, context.limits.maxSingleFileBytes)
        val digest = sha256(bytes)
        val relative = RelativePath.require("runtime-code/${context.ref.revision.value}/$digest.$suffix")
        val target = context.storage.dataFile(relative)
        try {
            val parent = target.parentFile ?: runtimeFailure(PackErrorCode.IO_FAILED, "missing runtime directory")
            if (!parent.isDirectory && !parent.mkdirs()) runtimeFailure(PackErrorCode.IO_FAILED, "cannot create private runtime directory")
            if (AtomicFiles.isSymbolicLink(target)) {
                runtimeFailure(PackErrorCode.PERMISSION_DENIED, "runtime code path contains a symlink")
            }
            if (!target.exists()) {
                val temporaryRelative = RelativePath.require("runtime-code/${context.ref.revision.value}/${UUID.randomUUID()}.part")
                val temporary = context.storage.dataFile(temporaryRelative)
                try {
                    val fd = Os.open(temporary.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 256 /* 0400 */)
                    FileOutputStream(fd).use { output ->
                        var offset = 0
                        while (offset < bytes.size) {
                            call.checkActive()
                            val length = minOf(8192, bytes.size - offset)
                            output.write(bytes, offset, length)
                            offset += length
                        }
                        output.fd.sync()
                    }
                    call.checkActive()
                    if (!temporary.renameTo(target)) runtimeFailure(PackErrorCode.IO_FAILED, "cannot publish read-only code")
                } finally {
                    temporary.delete()
                }
            }
            val stat = Os.lstat(target.absolutePath)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_mode and 146 /* 0222 */ != 0 || stat.st_uid != Process.myUid()) {
                runtimeFailure(PackErrorCode.PERMISSION_DENIED, "dynamic code must be an app-owned read-only regular file")
            }
            if (target.length() != bytes.size.toLong()) runtimeFailure(PackErrorCode.LOAD_FAILED, "staged code size mismatch")
            val existing = FileInputStream(target).use { input ->
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    call.checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > bytes.size) runtimeFailure(PackErrorCode.LOAD_FAILED, "staged code changed while verifying")
                    md.update(buffer, 0, count)
                }
                md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            if (existing != digest) runtimeFailure(PackErrorCode.LOAD_FAILED, "staged code digest mismatch")
            call.checkActive()
            return target
        } catch (e: android.system.ErrnoException) {
            runtimeFailure(PackErrorCode.IO_FAILED, "cannot stage read-only dynamic code: ${e.message}", e)
        } catch (e: java.io.IOException) {
            runtimeFailure(PackErrorCode.IO_FAILED, "cannot stage read-only dynamic code", e)
        }
    }
}
