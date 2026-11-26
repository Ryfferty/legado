package io.legado.pack.api

import java.io.File

/**
 * Pure path calculator for the per-pack private directory tree.
 *
 * The host supplies the `packs` root (normally `File(context.filesDir,
 * "packs")`); this class never references an application id or package name.
 * It performs no I/O, creates no directories and executes no package code.
 *
 * Layout:
 * ```
 * <packsRoot>/<packId>/
 *   current.json
 *   .staging/<token>/
 *   code/<revision>/
 *   data/
 *   kv/
 *   logs/
 *   cookies/
 *   opt/
 * ```
 */
class PackStorageLayout(
    val packsRoot: File,
) {
    init {
        require(packsRoot.isAbsolute) { "packsRoot must be absolute" }
    }

    fun packDir(id: PackId): File = File(packsRoot, id.value)

    fun codeRoot(id: PackId): File = File(packDir(id), "code")

    fun revisionDir(id: PackId, revision: RevisionId): File =
        File(codeRoot(id), revision.value)

    fun dataDir(id: PackId): File = File(packDir(id), "data")

    fun kvDir(id: PackId): File = File(packDir(id), "kv")

    fun logsDir(id: PackId): File = File(packDir(id), "logs")

    fun cookiesDir(id: PackId): File = File(packDir(id), "cookies")

    fun optDir(id: PackId): File = File(packDir(id), "opt")

    /** AtomicFile target that points at the immutable current revision metadata. */
    fun currentFile(id: PackId): File = File(packDir(id), "current.json")

    fun stagingRoot(id: PackId): File = File(packDir(id), ".staging")

    fun stagingDir(id: PackId, token: String): File {
        require(STAGING_TOKEN.matches(token)) {
            "staging token must be 8..64 URL-safe characters"
        }
        return File(stagingRoot(id), token)
    }

    /** Resolves a read-only resource inside one immutable code revision. */
    fun resolveInRevision(
        id: PackId,
        revision: RevisionId,
        relative: RelativePath,
    ): File = resolveWithin(revisionDir(id, revision), relative)

    /** Resolves a writable file inside the pack's private data directory. */
    fun resolveInData(id: PackId, relative: RelativePath): File =
        resolveWithin(dataDir(id), relative)

    private fun resolveWithin(root: File, relative: RelativePath): File {
        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, relative.value).canonicalFile
        val rootPath = canonicalRoot.path
        val candidatePath = candidate.path
        require(candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)) {
            "path escapes pack directory: '${relative.value}'"
        }
        return candidate
    }

    companion object {
        const val PACKS_DIR_NAME: String = "packs"
        private val STAGING_TOKEN = Regex("[A-Za-z0-9_-]{8,64}")

        fun fromHostFilesDir(filesDir: File): PackStorageLayout =
            PackStorageLayout(File(filesDir, PACKS_DIR_NAME))
    }
}