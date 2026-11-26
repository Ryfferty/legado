package io.legado.pack.storage

import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import io.legado.pack.api.PackStorage
import io.legado.pack.api.PackStorageLayout
import io.legado.pack.api.RelativePath
import io.legado.pack.api.RevisionId
import io.legado.pack.primitives.PackEpochGuard
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Optional capability implemented by the manager's [PackStorage]. DEX/native
 * runtime providers need a real file to hand to DexClassLoader/dlopen; they
 * must check for this interface instead of guessing paths or extracting files
 * to a shared directory.
 *
 * The returned path is inside the current read-only code revision and has
 * passed canonical containment checks. Callers must re-check the call context
 * before loading and must never expose the path to pack JS.
 */
interface PackRevisionFileAccess {
    fun revisionFile(relative: RelativePath): File?
}

public class PackStorageImpl(
    private val layout: PackStorageLayout,
    private val id: PackId,
    private val revision: RevisionId,
    private val guard: PackEpochGuard,
) : PackStorage, PackRevisionFileAccess {

    override fun openResource(relative: RelativePath): InputStream {
        guard.ensureActive()
        val file = resolveRevision(relative)
        if (!file.isFile) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.COMPONENT_MISSING,
                    message = "pack resource '${relative.value}' is missing",
                    path = relative.value,
                ),
            )
        }
        return try {
            FileInputStream(file)
        } catch (e: IOException) {
            throw AtomicFiles.ioFailure("cannot open pack resource: ${e.message}", file, e)
        }
    }

    override fun resourceExists(relative: RelativePath): Boolean {
        guard.ensureActive()
        return resolveRevision(relative).isFile
    }

    override fun dataFile(relative: RelativePath): File {
        guard.ensureActive()
        val dataRoot = layout.dataDir(id)
        if (!dataRoot.exists() && !dataRoot.mkdirs() && !dataRoot.isDirectory) {
            throw AtomicFiles.ioFailure("cannot create pack data directory", dataRoot)
        }
        val file = try {
            layout.resolveInData(id, relative)
        } catch (e: IllegalArgumentException) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.PERMISSION_DENIED,
                    message = "pack data path escapes its private directory",
                    path = relative.value,
                ),
                e,
            )
        }
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw AtomicFiles.ioFailure("cannot create pack data directory", parent)
        }
        // Re-canonicalize after creating parents so a symlinked parent cannot
        // redirect a previously contained path outside the pack.
        val contained = try {
            layout.resolveInData(id, relative)
        } catch (e: IllegalArgumentException) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.PERMISSION_DENIED,
                    message = "pack data path escapes its private directory",
                    path = relative.value,
                ),
                e,
            )
        }
        if (!contained.path.startsWith(dataRoot.canonicalFile.path + File.separator) &&
            contained.path != dataRoot.canonicalFile.path
        ) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.PERMISSION_DENIED,
                    message = "pack data path escapes its private directory",
                    path = relative.value,
                ),
            )
        }
        return contained
    }

    override fun revisionFile(relative: RelativePath): File? {
        guard.ensureActive()
        val file = resolveRevision(relative)
        return if (file.isFile) file else null
    }

    private fun resolveRevision(relative: RelativePath): File {
        return try {
            layout.resolveInRevision(id, revision, relative)
        } catch (e: IllegalArgumentException) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.PERMISSION_DENIED,
                    message = "pack resource path escapes its revision",
                    path = relative.value,
                ),
                e,
            )
        }
    }
}