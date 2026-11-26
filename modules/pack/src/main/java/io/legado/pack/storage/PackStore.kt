package io.legado.pack.storage

import io.legado.pack.api.JsonValue
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackGrant
import io.legado.pack.api.PackId
import io.legado.pack.api.PackManifest
import io.legado.pack.api.PackRef
import io.legado.pack.api.RevisionId
import io.legado.pack.manifest.ManifestValidationContext
import java.io.File
import java.util.Locale

internal enum class PackFormat(val wire: String) {
    ZIP("zip"),
    USERSCRIPT("userscript");

    companion object {
        fun parse(raw: String?): PackFormat? =
            entries.firstOrNull { it.wire == raw?.lowercase(Locale.ROOT) }
    }
}

/**
 * Durable installation record. It contains parsed metadata and the canonical
 * manifest JSON, but no executable objects and no pack code.
 */
internal data class StoredPack(
    val schemaVersion: Int,
    val id: PackId,
    val manifest: PackManifest,
    val manifestJson: String,
    val revision: RevisionId,
    val contentSha256: RevisionId,
    val epoch: Long,
    val enabled: Boolean,
    val installedAtEpochMs: Long,
    val needsRestart: Boolean,
    val broken: PackDiagnostic?,
    val grant: PackGrant,
    val lastSelfTestRunId: String?,
    val format: PackFormat,
) {
    init {
        require(schemaVersion >= 1)
        require(manifest.id == id) { "stored manifest id mismatch" }
        require(epoch >= 0L)
        require(installedAtEpochMs >= 0L)
        require(revision == contentSha256) { "revision and content hash must match" }
    }

    val ref: PackRef get() = PackRef(id, revision, epoch)

    val grantMatchesPermissions: Boolean
        get() = grant.permissionHash == manifest.permissions.digest()
}

internal object StoredPackCodec {

    private const val SCHEMA_VERSION = 1
    private const val MAX_METADATA_CHARS = 8 * 1024 * 1024

    fun encode(stored: StoredPack): String = buildString(4096) {
        append('{')
        append("\"schemaVersion\":").append(SCHEMA_VERSION)
        append(",\"id\":").append(PackJsonText.quote(stored.id.value))
        append(",\"manifestJson\":").append(PackJsonText.quote(stored.manifestJson))
        append(",\"revision\":").append(PackJsonText.quote(stored.revision.value))
        append(",\"contentSha256\":").append(PackJsonText.quote(stored.contentSha256.value))
        append(",\"epoch\":").append(stored.epoch)
        append(",\"enabled\":").append(stored.enabled)
        append(",\"installedAtEpochMs\":").append(stored.installedAtEpochMs)
        append(",\"needsRestart\":").append(stored.needsRestart)
        append(",\"broken\":")
        appendDiagnostic(stored.broken)
        append(",\"grant\":{")
        append("\"permissionHash\":").append(PackJsonText.quote(stored.grant.permissionHash))
        append(",\"networkOpenApproved\":").append(stored.grant.networkOpenApproved)
        append(",\"toolPermissions\":")
        appendBooleanMap(stored.grant.toolPermissions)
        append(",\"routePermissions\":")
        appendBooleanMap(stored.grant.routePermissions)
        append('}')
        append(",\"lastSelfTestRunId\":")
        val runId = stored.lastSelfTestRunId
        if (runId == null) append("null") else append(PackJsonText.quote(runId))
        append(",\"format\":").append(PackJsonText.quote(stored.format.wire))
        append('}')
    }

    fun decode(
        json: String,
        context: ManifestValidationContext,
    ): StoredPack {
        val root = PackJsonText.parseObject(json, MAX_METADATA_CHARS, "current")
        val schema = root.requiredInt("schemaVersion", "current")
        if (schema != SCHEMA_VERSION) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.MANIFEST_SCHEMA_UNSUPPORTED,
                    message = "installation metadata schema $schema is not supported",
                    path = "current.schemaVersion",
                ),
            )
        }
        val id = PackId.parse(root.requiredString("id", "current"))
            ?: throw invalidMetadata("current.id", "invalid pack id")
        val manifestJson = root.requiredString("manifestJson", "current")
        val manifest = ManifestJsonCodec.decode(manifestJson, context)
        if (manifest.id != id) {
            throw invalidMetadata("current.id", "pack id does not match its manifest")
        }
        val revisionText = root.requiredString("revision", "current")
        val revision = RevisionId.parse(revisionText)
            ?: throw invalidMetadata("current.revision", "invalid revision")
        val contentSha = RevisionId.parse(root.requiredString("contentSha256", "current"))
            ?: throw invalidMetadata("current.contentSha256", "invalid content hash")
        if (revision != contentSha) {
            throw invalidMetadata("current.revision", "revision and content hash differ")
        }
        val epoch = root.requiredLong("epoch", "current")
        if (epoch < 0L) throw invalidMetadata("current.epoch", "epoch must be >= 0")
        val enabled = root.optionalBoolean("enabled", "current") ?: false
        val installedAt = root.requiredLong("installedAtEpochMs", "current")
        if (installedAt < 0L) {
            throw invalidMetadata("current.installedAtEpochMs", "installedAtEpochMs must be >= 0")
        }
        val needsRestart = root.optionalBoolean("needsRestart", "current") ?: false
        val broken = decodeDiagnostic(root.optionalObject("broken", "current"))
        val grantObj = root.requiredObject("grant", "current")
        val grant = PackGrant(
            permissionHash = grantObj.requiredString("permissionHash", "current.grant"),
            networkOpenApproved = grantObj.optionalBoolean("networkOpenApproved", "current.grant") ?: false,
            toolPermissions = readBooleanMap(
                grantObj.optionalObject("toolPermissions", "current.grant"),
                "current.grant.toolPermissions",
            ),
            routePermissions = readBooleanMap(
                grantObj.optionalObject("routePermissions", "current.grant"),
                "current.grant.routePermissions",
            ),
        )
        val runId = root.optionalString("lastSelfTestRunId", "current")
        val format = PackFormat.parse(root.requiredString("format", "current"))
            ?: throw invalidMetadata("current.format", "unknown pack format")
        return StoredPack(
            schemaVersion = schema,
            id = id,
            manifest = manifest,
            manifestJson = manifestJson,
            revision = revision,
            contentSha256 = contentSha,
            epoch = epoch,
            enabled = enabled,
            installedAtEpochMs = installedAt,
            needsRestart = needsRestart,
            broken = broken,
            grant = grant,
            lastSelfTestRunId = runId,
            format = format,
        )
    }

    private fun StringBuilder.appendBooleanMap(values: Map<String, Boolean>) {
        append('{')
        values.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append(PackJsonText.quote(entry.key)).append(':').append(entry.value)
        }
        append('}')
    }

    private fun StringBuilder.appendDiagnostic(diagnostic: PackDiagnostic?) {
        if (diagnostic == null) {
            append("null")
            return
        }
        append('{')
        append("\"code\":").append(PackJsonText.quote(diagnostic.code.wire))
        append(",\"message\":").append(PackJsonText.quote(diagnostic.message))
        diagnostic.path?.let { append(",\"path\":").append(PackJsonText.quote(it)) }
        append(",\"details\":").append(PackJsonText.writeStringMap(diagnostic.details))
        append('}')
    }

    private fun decodeDiagnostic(obj: JsonValue.Obj?): PackDiagnostic? {
        if (obj == null) return null
        val wire = obj.requiredString("code", "current.broken")
        val code = PackErrorCode.entries.firstOrNull { it.wire == wire }
            ?: throw invalidMetadata("current.broken.code", "unknown diagnostic code '$wire'")
        return PackDiagnostic(
            code = code,
            message = obj.requiredString("message", "current.broken"),
            path = obj.optionalString("path", "current.broken"),
            details = readStringMap(obj.optionalObject("details", "current.broken"), "current.broken.details"),
        )
    }

    private fun invalidMetadata(path: String, message: String): PackException =
        PackException(PackDiagnostic(PackErrorCode.MANIFEST_INVALID, message, path))
}

internal data class RecoveryResult(
    val packs: List<StoredPack>,
    val diagnostics: List<PackDiagnostic>,
)

/**
 * Owns the on-disk installation tree under `packs/<id>/`.
 *
 * It never instantiates a runtime and never reads pack entry points. Startup
 * recovery removes interrupted staging/code directories and rebuilds state
 * from `current.json` (falling back to the atomic backup).
 */
internal class PackStore(
    val layout: io.legado.pack.api.PackStorageLayout,
    private val validationContext: ManifestValidationContext,
) {

    val packsRoot: File get() = layout.packsRoot

    fun ensureRoot() {
        val root = packsRoot
        if (!root.exists() && !root.mkdirs() && !root.isDirectory) {
            throw AtomicFiles.ioFailure("cannot create packs root", root)
        }
    }

    fun readMetadata(id: PackId): StoredPack? {
        val current = layout.currentFile(id)
        AtomicFiles.recoverIfNeeded(current)
        if (!current.isFile) return null
        return try {
            val stored = decodeCurrent(current)
            File(current.parentFile, current.name + ".bak").delete()
            stored
        } catch (first: Exception) {
            val backup = File(current.parentFile, current.name + ".bak")
            if (!backup.isFile) throw first
            val recovered = try {
                decodeCurrent(backup)
            } catch (_: Exception) {
                throw first
            }
            current.setWritable(true, false)
            current.delete()
            backup.renameTo(current)
            recovered
        }
    }

    fun writeMetadata(stored: StoredPack) {
        AtomicFiles.writeText(
            layout.currentFile(stored.id),
            StoredPackCodec.encode(stored),
            createParent = false,
        )
    }

    fun deleteMetadata(id: PackId) {
        val current = layout.currentFile(id)
        current.delete()
        File(current.parentFile, current.name + ".bak").delete()
        File(current.parentFile, current.name + ".tmp").delete()
    }

    fun scanAndRecover(): RecoveryResult {
        ensureRoot()
        val diagnostics = mutableListOf<PackDiagnostic>()
        cleanupIncoming(diagnostics)
        val records = mutableListOf<StoredPack>()
        val children = packsRoot.listFiles()
        if (children == null) {
            diagnostics += PackDiagnostic(
                code = PackErrorCode.IO_FAILED,
                message = "cannot list packs root",
                path = packsRoot.path,
            )
            return RecoveryResult(emptyList(), diagnostics)
        }
        children.sortedBy { it.name }.forEach { child ->
            if (!child.isDirectory) return@forEach
            if (child.name.startsWith(".")) return@forEach
            val id = PackId.parse(child.name)
            if (id == null) {
                diagnostics += PackDiagnostic(
                    code = PackErrorCode.MANIFEST_INVALID,
                    message = "directory '${child.name}' is not a valid pack id",
                    path = child.name,
                )
                return@forEach
            }
            cleanupStaging(id)
            val stored = try {
                readMetadata(id)
            } catch (e: PackException) {
                diagnostics += e.diagnostic.copy(path = e.diagnostic.path ?: id.value)
                null
            } catch (e: Exception) {
                diagnostics += PackDiagnostic(
                    code = PackErrorCode.IO_FAILED,
                    message = "cannot read installation metadata: ${e.message}",
                    path = id.value,
                )
                null
            }
            if (stored == null) {
                if (layout.codeRoot(id).listFiles()?.isNotEmpty() == true) {
                    diagnostics += PackDiagnostic(
                        code = PackErrorCode.RESIDUAL_FILES,
                        message = "pack directory has code but no usable current.json",
                        path = id.value,
                    )
                }
                return@forEach
            }
            var recovered = stored
            val revisionDir = layout.revisionDir(id, stored.revision)
            if (!revisionDir.isDirectory) {
                recovered = recovered.copy(
                    broken = PackDiagnostic(
                        code = PackErrorCode.COMPONENT_MISSING,
                        message = "installed revision ${stored.revision.value} is missing",
                        path = "code/${stored.revision.value}",
                    ),
                )
                diagnostics += recovered.broken!!
            }
            if (recovered.needsRestart) {
                recovered = recovered.copy(needsRestart = false)
                runCatching { writeMetadata(recovered) }
            }
            cleanupCode(id, stored.revision, diagnostics)
            records += recovered
        }
        return RecoveryResult(records, diagnostics)
    }

    fun stagingDir(id: PackId, token: String): File = layout.stagingDir(id, token)

    fun packDir(id: PackId): File = layout.packDir(id)

    fun revisionDir(id: PackId, revision: RevisionId): File = layout.revisionDir(id, revision)

    fun revisionExists(id: PackId, revision: RevisionId): Boolean =
        layout.revisionDir(id, revision).isDirectory

    fun componentFile(id: PackId, revision: RevisionId, relative: io.legado.pack.api.RelativePath): File =
        layout.resolveInRevision(id, revision, relative)

    fun dataDir(id: PackId): File = layout.dataDir(id)

    fun logsDir(id: PackId): File = layout.logsDir(id)

    fun cookiesDir(id: PackId): File = layout.cookiesDir(id)

    fun ensurePackDirectories(id: PackId) {
        listOf(
            layout.packDir(id),
            layout.codeRoot(id),
            layout.dataDir(id),
            layout.kvDir(id),
            layout.logsDir(id),
            layout.cookiesDir(id),
            layout.optDir(id),
        ).forEach { directory ->
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
                throw AtomicFiles.ioFailure("cannot create pack directory", directory)
            }
        }
    }

    /**
     * Moves validated staging content to `code/<revision>`. An existing
     * directory is reused only when its digest still matches.
     */
    fun installRevision(id: PackId, revision: RevisionId, staging: File): File {
        val target = layout.revisionDir(id, revision)
        val codeRoot = layout.codeRoot(id)
        if (!codeRoot.exists() && !codeRoot.mkdirs() && !codeRoot.isDirectory) {
            throw AtomicFiles.ioFailure("cannot create code directory", codeRoot)
        }
        if (target.isDirectory) {
            val digest = ContentHasher.digestDirectory(target)
            if (digest == revision) {
                AtomicFiles.deleteRecursively(staging)
                AtomicFiles.makeReadOnlyRecursively(target)
                return target
            }
            if (!AtomicFiles.deleteRecursively(target)) {
                throw AtomicFiles.ioFailure("cannot replace corrupted revision directory", target)
            }
        } else if (target.exists()) {
            if (!AtomicFiles.deleteRecursively(target)) {
                throw AtomicFiles.ioFailure("cannot replace revision path", target)
            }
        }
        if (!staging.renameTo(target)) {
            throw AtomicFiles.ioFailure("cannot move staged content into the code revision", target)
        }
        AtomicFiles.makeReadOnlyRecursively(target)
        return target
    }

    fun deleteRevision(id: PackId, revision: RevisionId) {
        val directory = layout.revisionDir(id, revision)
        if (directory.exists() && !AtomicFiles.deleteRecursively(directory)) {
            throw AtomicFiles.ioFailure("cannot delete old revision", directory)
        }
    }

    fun deletePack(id: PackId): List<String> {
        val directory = layout.packDir(id)
        val deleted = AtomicFiles.deleteRecursively(directory)
        val residual = AtomicFiles.residualPaths(directory)
        if (!deleted && residual.isEmpty()) {
            return listOf(id.value)
        }
        return residual
    }

    fun cleanupStaging(id: PackId) {
        val stagingRoot = layout.stagingRoot(id)
        if (stagingRoot.isDirectory) {
            stagingRoot.listFiles()?.forEach { AtomicFiles.deleteRecursively(it) }
        }
    }

    fun cleanupAllStaging() {
        cleanupIncoming(mutableListOf())
        packsRoot.listFiles()?.forEach { child ->
            if (child.isDirectory && !child.name.startsWith(".")) {
                PackId.parse(child.name)?.let { cleanupStaging(it) }
            }
        }
    }

    fun selfTestDir(id: PackId): File = File(layout.packDir(id), "self-tests")

    private fun decodeCurrent(file: File): StoredPack {
        val text = AtomicFiles.readText(file, MAX_CURRENT_BYTES, file.name)
        return StoredPackCodec.decode(text, validationContext)
    }

    private fun cleanupIncoming(diagnostics: MutableList<PackDiagnostic>) {
        val incoming = File(packsRoot, INCOMING_DIR)
        if (!incoming.isDirectory) return
        incoming.listFiles()?.forEach { file ->
            if (!AtomicFiles.deleteRecursively(file)) {
                diagnostics += PackDiagnostic(
                    code = PackErrorCode.IO_FAILED,
                    message = "cannot remove interrupted import file",
                    path = file.name,
                )
            }
        }
    }

    private fun cleanupCode(
        id: PackId,
        current: RevisionId,
        diagnostics: MutableList<PackDiagnostic>,
    ) {
        val root = layout.codeRoot(id)
        root.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name == current.value) return@forEach
            if (!AtomicFiles.deleteRecursively(child)) {
                diagnostics += PackDiagnostic(
                    code = PackErrorCode.IO_FAILED,
                    message = "cannot remove unreferenced code revision",
                    path = "code/${child.name}",
                )
            }
        }
    }

    companion object {
        const val INCOMING_DIR: String = ".incoming"
        private const val MAX_CURRENT_BYTES = 16L * 1024 * 1024
    }
}