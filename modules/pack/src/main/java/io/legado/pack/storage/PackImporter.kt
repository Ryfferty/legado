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

    fun discard(prepared: PreparedPack) {
        AtomicFiles.deleteRecursively(prepared.stagingDir)
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
        val warnings = when (parsed) {
            is ManifestParseResult.Valid -> parsed.warnings
            is ManifestParseResult.Invalid -> emptyList()
        }
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
            cleanupEmptyPackDir(manifest.id)
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
        val body = when (parsed) {
            is ManifestParseResult.Valid -> parsed.singleFileBody
            is ManifestParseResult.Invalid -> null
        } ?: throw PackException(
            PackDiagnostic(
                code = PackErrorCode.MANIFEST_INVALID,
                message = "single-file pack has no JavaScript body",
                path = PackArchiveSpec.SINGLE_FILE_JS_ENTRY,
            ),
        )
        val warnings = when (parsed) {
            is ManifestParseResult.Valid -> parsed.warnings
            is ManifestParseResult.Invalid -> emptyList()
        }
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
            cleanupEmptyPackDir(manifest.id)
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
        id?.let { cleanupEmptyPackDir(it) }
    }

    private fun cleanupEmptyPackDir(id: io.legado.pack.api.PackId) {
        if (layout.currentFile(id).exists()) return
        val packDir = layout.packDir(id)
        if (!packDir.isDirectory) return
        val dataDir = layout.dataDir(id)
        if (dataDir.exists()) return
        val codeRoot = layout.codeRoot(id)
        if (codeRoot.exists() && codeRoot.listFiles()?.isNotEmpty() == true) return
        val stagingRoot = layout.stagingRoot(id)
        if (stagingRoot.exists()) {
            stagingRoot.listFiles()?.forEach { AtomicFiles.deleteRecursively(it) }
        }
        AtomicFiles.deleteRecursively(packDir)
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
}