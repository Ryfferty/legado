package io.legado.pack.storage

import io.legado.pack.api.ComponentSpec
import io.legado.pack.api.ImportPreview
import io.legado.pack.api.PackArchiveSpec
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackImportHint
import io.legado.pack.api.PackManifest
import io.legado.pack.api.PackResourceLimits
import io.legado.pack.api.RevisionId
import io.legado.pack.manifest.ManifestParseResult
import io.legado.pack.manifest.ManifestValidationContext
import io.legado.pack.manifest.PackManifestParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.CancellationException

internal data class PreparedPack(
    val preview: ImportPreview,
    val stagingDir: File,
    val manifestJson: String,
    val format: PackFormat,
    val baseRevision: RevisionId?,
    val baseEpoch: Long?,
    val preExistingPack: Boolean,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

/**
 * Streams an untrusted import into the host-private staging area.
 *
 * No code is executed, no runtime provider is consulted and no registration is
 * touched. The preview token is random; the content hash is recomputed from the
 * staged tree at commit time, so an external file cannot change between
 * `prepareImport` and `commitImport`.
 */
internal class PackImporter(
    private val layout: io.legado.pack.api.PackStorageLayout,
    private val limits: PackResourceLimits,
    private val validationContext: ManifestValidationContext,
) {

    private val random = SecureRandom()

    fun prepare(
        input: InputStream,
        hint: PackImportHint,
        baseRevision: RevisionId?,
        baseEpoch: Long?,
        checkCancelled: () -> Unit = {},
    ): PreparedPack {
        ensureRoots()
        val token = newToken()
        val incoming = File(layout.packsRoot, PackStore.INCOMING_DIR)
        if (!incoming.exists() && !incoming.mkdirs() && !incoming.isDirectory) {
            throw AtomicFiles.ioFailure("cannot create import directory", incoming)
        }
        val tempFile = File(incoming, "$token.import")
        var stagingDir: File? = null
        var packId: io.legado.pack.api.PackId? = null
        try {
            copyInput(input, tempFile, checkCancelled)
            checkCancelled()
            val format = detectFormat(tempFile)
            val parsed = when (format) {
                PackFormat.ZIP -> prepareZip(tempFile, token, checkCancelled)
                PackFormat.USERSCRIPT -> prepareUserScript(tempFile, token, checkCancelled)
            }
            packId = parsed.manifest.id
            stagingDir = parsed.stagingDir
            val manifest = parsed.manifest
            validateComponentFiles(manifest, parsed.stagingDir)
            val preview = ImportPreview(
                token = token,
                manifest = manifest,
                contentSha256 = parsed.contentSha256,
                warnings = parsed.warnings,
            )
            return PreparedPack(
                preview = preview,
                stagingDir = parsed.stagingDir,
                manifestJson = parsed.manifestJson,
                format = format,
                baseRevision = baseRevision,
                baseEpoch = baseEpoch,
                preExistingPack = layout.currentFile(manifest.id).isFile,
            )
        } catch (e: CancellationException) {
            cleanupStaging(packId, stagingDir)
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            cleanupStaging(packId, stagingDir)
            tempFile.delete()
            throw e
        } finally {
            tempFile.delete()
        }
    }

    fun verifyStagedContent(prepared: PreparedPack): RevisionId {
        val computed = when (prepared.format) {
            PackFormat.ZIP -> ContentHasher.digestDirectory(prepared.stagingDir)
            PackFormat.USERSCRIPT -> {
                val body = AtomicFiles.readText(
                    File(prepared.stagingDir, PackArchiveSpec.SINGLE_FILE_JS_ENTRY),
                    limits.maxPackBytes,
                    PackArchiveSpec.SINGLE_FILE_JS_ENTRY,
                )
                ContentHasher.digestUserscript(prepared.manifestJson, body)
            }
        }
        if (computed != prepared.preview.contentSha256) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.IO_FAILED,
                    message = "staged content changed after preview; import again",
                    path = "staging",
                ),
            )
        }
        return computed
    }

    /**
     * Removes a preview's staging. Failure is typed, not silent: when the
     * staging directory cannot be fully deleted the residue is reported as an
     * IO_FAILED exception (AtomicFiles.deleteRecursively signals residue via
     * its Boolean return, not via an exception), so callers such as manager
     * close() can surface the red card instead of leaving orphaned files.
     *
     * The same contract covers the empty pack skeleton: a rmdir that fails
     * while the directory is genuinely empty (open handle/permission) is an
     * IO red card, while content that legitimately appeared after the
     * emptiness checks (another preview's token, a rogue writer) keeps the
     * skeleton in place silently.
     */
    fun discard(prepared: PreparedPack) {
        val staging = prepared.stagingDir
        if (staging.exists() && !AtomicFiles.deleteRecursively(staging)) {
            val residual = AtomicFiles.residualPaths(staging)
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.IO_FAILED,
                    message = "cannot discard import staging; ${residual.size} entries remain",
                    path = "staging/${prepared.preview.token}",
                    details = mapOf(
                        "residualCount" to residual.size.toString(),
                        "residual" to residual.take(ResidualDetailLimit).joinToString(","),
                    ),
                ),
            )
        }
        if (!prepared.preExistingPack) {
            cleanupEmptyPackDir(prepared.preview.manifest.id)
        }
    }

    private data class ParsedStaging(
        val manifest: PackManifest,
        val manifestJson: String,
        val contentSha256: RevisionId,
        val stagingDir: File,
        val warnings: List<PackDiagnostic>,
    )

    private fun prepareZip(
        archive: File,
        token: String,
        checkCancelled: () -> Unit,
    ): ParsedStaging {
        val entries = PackZipArchive.listEntries(archive, limits)
        val manifestText = PackZipArchive.readManifest(archive, limits.maxManifestChars, limits)
        val parsed = PackManifestParser(validationContext).parseJson(manifestText)
        val manifest = when (parsed) {
            is ManifestParseResult.Valid -> parsed.manifest
            is ManifestParseResult.Invalid -> throw PackException(parsed.diagnostics.first())
        }
        // The when above throws on Invalid, so `parsed` is Valid here.
        val warnings = parsed.warnings
        val staging = createStagingDir(manifest.id, token)
        try {
            PackZipArchive.extract(archive, entries, staging, limits, checkCancelled)
            checkCancelled()
            val digest = ContentHasher.digestDirectory(staging)
            return ParsedStaging(
                manifest = manifest,
                manifestJson = ManifestJsonCodec.encode(manifest),
                contentSha256 = digest,
                stagingDir = staging,
                warnings = warnings,
            )
        } catch (e: Exception) {
            AtomicFiles.deleteRecursively(staging)
            cleanupEmptyPackDirBestEffort(manifest.id)
            throw e
        }
    }

    private fun prepareUserScript(
        sourceFile: File,
        token: String,
        checkCancelled: () -> Unit,
    ): ParsedStaging {
        val source = try {
            decodeUtf8(AtomicFiles.readBytes(sourceFile, limits.maxPackBytes, sourceFile.name))
        } catch (e: CharacterCodingException) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.MANIFEST_INVALID,
                    message = "single-file pack is not valid UTF-8",
                    path = PackArchiveSpec.SINGLE_FILE_JS_ENTRY,
                ),
                e,
            )
        }
        checkCancelled()
        val parsed = PackManifestParser(validationContext).parseUserScript(source)
        val manifest = when (parsed) {
            is ManifestParseResult.Valid -> parsed.manifest
            is ManifestParseResult.Invalid -> throw PackException(parsed.diagnostics.first())
        }
        // The when above throws on Invalid, so `parsed` is Valid here.
        val body = parsed.singleFileBody ?: throw PackException(
            PackDiagnostic(
                code = PackErrorCode.MANIFEST_INVALID,
                message = "single-file pack has no JavaScript body",
                path = PackArchiveSpec.SINGLE_FILE_JS_ENTRY,
            ),
        )
        val warnings = parsed.warnings
        val staging = createStagingDir(manifest.id, token)
        try {
            val main = File(staging, PackArchiveSpec.SINGLE_FILE_JS_ENTRY)
            AtomicFiles.writeBytes(main, body.toByteArray(Charsets.UTF_8))
            val manifestJson = ManifestJsonCodec.encode(manifest)
            val digest = ContentHasher.digestUserscript(manifestJson, body)
            checkCancelled()
            return ParsedStaging(
                manifest = manifest,
                manifestJson = manifestJson,
                contentSha256 = digest,
                stagingDir = staging,
                warnings = warnings,
            )
        } catch (e: Exception) {
            AtomicFiles.deleteRecursively(staging)
            cleanupEmptyPackDirBestEffort(manifest.id)
            throw e
        }
    }

    private fun validateComponentFiles(manifest: PackManifest, staging: File) {
        val root = staging.canonicalFile
        manifest.components.forEach { component ->
            val relative = when (component) {
                is ComponentSpec.Js -> component.entry
                is ComponentSpec.Dex -> component.path
                is ComponentSpec.Native -> component.path
            }
            val file = File(root, relative.value).canonicalFile
            val rootPath = root.path
            if (file.path != rootPath && !file.path.startsWith(rootPath + File.separator)) {
                throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.COMPONENT_MISSING,
                        message = "component '${component.id}' path escapes the pack",
                        path = relative.value,
                    ),
                )
            }
            if (!file.isFile) {
                throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.COMPONENT_MISSING,
                        message = "component '${component.id}' file '${relative.value}' is missing",
                        path = relative.value,
                        details = mapOf("component" to component.id, "kind" to component.kind.wire),
                    ),
                )
            }
        }
    }

    private fun createStagingDir(id: io.legado.pack.api.PackId, token: String): File {
        val staging = layout.stagingDir(id, token)
        val parent = staging.parentFile
            ?: throw AtomicFiles.ioFailure("staging directory has no parent", staging)
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw AtomicFiles.ioFailure("cannot create staging directory", parent)
        }
        if (!staging.mkdirs() && !staging.isDirectory) {
            throw AtomicFiles.ioFailure("cannot create staging directory", staging)
        }
        return staging
    }

    private fun copyInput(input: InputStream, target: File, checkCancelled: () -> Unit) {
        var total = 0L
        try {
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkCancelled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limits.maxPackBytes) {
                        throw PackException(
                            PackDiagnostic(
                                code = PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                                message = "import exceeds ${limits.maxPackBytes} bytes",
                                path = "import",
                            ),
                        )
                    }
                    output.write(buffer, 0, read)
                }
                output.flush()
                output.fd.sync()
            }
        } catch (e: PackException) {
            target.delete()
            throw e
        } catch (e: IOException) {
            target.delete()
            throw AtomicFiles.ioFailure("cannot stage import: ${e.message}", target, e)
        }
        if (total == 0L) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.MANIFEST_INVALID,
                    message = "import is empty",
                    path = "import",
                ),
            )
        }
    }

    private fun detectFormat(file: File): PackFormat {
        val first = ByteArray(4)
        val read = file.inputStream().use { it.read(first) }
        if (read >= 2 && first[0] == 'P'.code.toByte() && first[1] == 'K'.code.toByte()) {
            return PackFormat.ZIP
        }
        return PackFormat.USERSCRIPT
    }

    private fun cleanupStaging(id: io.legado.pack.api.PackId?, staging: File?) {
        staging?.let { AtomicFiles.deleteRecursively(it) }
        id?.let { cleanupEmptyPackDirBestEffort(it) }
    }

    /**
     * Failure-path variant of [cleanupEmptyPackDir]: the original import error
     * must remain the reported one, so a skeleton-reclaim failure during error
     * cleanup is not allowed to mask it. The residue stays on disk for the
     * next discard or the startup scan to report.
     */
    private fun cleanupEmptyPackDirBestEffort(id: io.legado.pack.api.PackId) {
        runCatching { cleanupEmptyPackDir(id) }
    }

    /**
     * Reclaims the pack skeleton directory only when nothing of the pack
     * remains. Callers delete their own staging token directory first; this
     * must never wipe other tokens: an in-flight preview of the same pack
     * still owns them (prepareImport eviction and commit run concurrently
     * with other previews).
     *
     * The reclaim itself is rmdir-only: every delete is a plain
     * non-recursive `File.delete()`, which succeeds only on an empty
     * directory. The emptiness checks above can race with another prepare
     * creating a new staging token (or any writer adding content) between
     * the check and the delete; recursive deletion would destroy that new
     * content, while a rmdir that fails on a directory which now has content
     * just leaves the skeleton behind for the next reclaim or startup scan.
     *
     * A rmdir that fails on a directory that is still empty, however, is a
     * real IO failure (open handle/permission) and is reported as a typed
     * IO red card instead of letting the caller assume the skeleton is gone.
     */
    private fun cleanupEmptyPackDir(id: io.legado.pack.api.PackId) {
        if (layout.currentFile(id).exists()) return
        val packDir = layout.packDir(id)
        if (!packDir.isDirectory) return
        if (hasEntries(layout.dataDir(id))) return
        if (hasEntries(layout.codeRoot(id))) return
        if (hasEntries(layout.stagingRoot(id))) return
        val undeletable = ArrayList<File>()
        deleteSkeletonDir(layout.stagingRoot(id))?.let { undeletable += it }
        deleteSkeletonDir(layout.dataDir(id))?.let { undeletable += it }
        deleteSkeletonDir(layout.codeRoot(id))?.let { undeletable += it }
        deleteSkeletonDir(layout.kvDir(id))?.let { undeletable += it }
        deleteSkeletonDir(layout.logsDir(id))?.let { undeletable += it }
        deleteSkeletonDir(layout.cookiesDir(id))?.let { undeletable += it }
        deleteSkeletonDir(layout.optDir(id))?.let { undeletable += it }
        if (!packDir.delete() && !hasEntries(packDir)) {
            // The pack dir itself is empty but could not be removed; the
            // children were either reclaimed or kept for a legitimate reason
            // already accounted for above.
            undeletable += packDir
        }
        if (undeletable.isNotEmpty()) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.IO_FAILED,
                    message = "cannot reclaim the empty pack skeleton; " +
                        "${undeletable.size} empty directories remain",
                    path = id.value,
                    details = mapOf(
                        "residualCount" to undeletable.size.toString(),
                        "residual" to undeletable.joinToString(",") { it.name },
                    ),
                ),
            )
        }
    }

    /**
     * One rmdir attempt with failure classification. Returns null when the
     * directory is gone (or never existed) or when a failed rmdir was saved
     * by new content appearing under it (another preview's token: the caller
     * keeps the skeleton silently). Returns the directory itself when a
     * still-empty directory could not be removed — an IO failure to report.
     */
    private fun deleteSkeletonDir(directory: File): File? {
        if (deleteDirIfEmpty(directory)) return null
        return if (hasEntries(directory)) null else directory
    }

    /** Unknown directory content counts as "not empty"; the pack dir is kept. */
    private fun hasEntries(directory: File): Boolean {
        if (!directory.exists()) return false
        val children = directory.listFiles() ?: return true
        return children.isNotEmpty()
    }

    /**
     * Non-recursive rmdir: deletes [directory] only when it is an empty
     * directory. A directory that does not exist counts as reclaimed; a
     * non-directory entry is left untouched for the startup scan to judge.
     */
    private fun deleteDirIfEmpty(directory: File): Boolean {
        if (!directory.exists()) return true
        if (!directory.isDirectory) return false
        return directory.delete()
    }

    private fun ensureRoots() {
        val root = layout.packsRoot
        if (!root.exists() && !root.mkdirs() && !root.isDirectory) {
            throw AtomicFiles.ioFailure("cannot create packs root", root)
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return PackHex.encode(bytes)
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    companion object {
        /** Cap for residual paths inlined into a discard failure diagnostic. */
        private const val ResidualDetailLimit = 8
    }
}