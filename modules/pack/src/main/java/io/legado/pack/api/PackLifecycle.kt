package io.legado.pack.api

import java.io.File
import java.io.InputStream

/**
 * Immutable identity of one loaded revision. [epoch] is the admission epoch
 * used to invalidate late results after disable/upgrade/uninstall.
 */
data class PackRef(
    val id: PackId,
    val revision: RevisionId,
    val epoch: Long,
) {
    init {
        require(epoch >= 0L) { "epoch must be >= 0" }
    }
}

enum class PackState {
    DISABLED,
    ENABLED,

    /** Some loaded code could not be released; a process restart is required. */
    NEEDS_RESTART,

    /** Last load/self-test failed with a red-card error. */
    BROKEN,
}

data class PackInfo(
    val ref: PackRef,
    val manifest: PackManifest,
    val state: PackState,
    val installedAtEpochMs: Long,
) {
    init {
        require(ref.id == manifest.id) { "PackRef id must match the manifest id" }
        require(installedAtEpochMs >= 0L) { "installedAtEpochMs must be >= 0" }
    }
}

/**
 * Result of staging and validating an import. No package code has run at this
 * point. [token] is bound to the staged content hash; commit must not re-read a
 * mutable external file.
 */
class ImportPreview(
    val token: String,
    val manifest: PackManifest,
    val contentSha256: RevisionId,
    warnings: List<PackDiagnostic> = emptyList(),
) {
    val warnings: List<PackDiagnostic> = immutableList(warnings)

    init {
        require(token.isNotBlank() && token.length <= 256) { "invalid import preview token" }
    }

    val permissions: PackPermissionSet get() = manifest.permissions
    val permissionHash: String get() = permissions.digest()
    val requiresOpenNetworkApproval: Boolean get() = permissions.requiresOpenNetworkApproval

    fun copy(
        token: String = this.token,
        manifest: PackManifest = this.manifest,
        contentSha256: RevisionId = this.contentSha256,
        warnings: List<PackDiagnostic> = this.warnings,
    ): ImportPreview = ImportPreview(token, manifest, contentSha256, warnings)

    override fun equals(other: Any?): Boolean =
        other is ImportPreview &&
            token == other.token &&
            manifest == other.manifest &&
            contentSha256 == other.contentSha256 &&
            warnings == other.warnings

    override fun hashCode(): Int {
        var result = token.hashCode()
        result = 31 * result + manifest.hashCode()
        result = 31 * result + contentSha256.hashCode()
        result = 31 * result + warnings.hashCode()
        return result
    }

    override fun toString(): String = "ImportPreview(pack=${manifest.id.value}, token=$token)"
}

data class UninstallReport(
    val packId: PackId,
    val registrationRevoked: Boolean,
    val residualPaths: List<String>,
    val loadedCodeStillResident: Boolean,
    val needsRestart: Boolean,
    val failure: PackDiagnostic? = null,
) {
    val clean: Boolean
        get() = registrationRevoked &&
            residualPaths.isEmpty() &&
            !loadedCodeStillResident &&
            !needsRestart &&
            failure == null
}

/** Optional hint from a file picker; content is authoritative. */
data class PackImportHint(
    val fileName: String? = null,
    val singleFile: Boolean? = null,
) {
    init {
        require(fileName == null || (fileName.length <= 255 &&
            !fileName.contains('/') &&
            !fileName.contains('\\') &&
            fileName.none { it.isISOControl() })) {
            "invalid import file name hint"
        }
    }
}

/**
 * Host-facing pack lifecycle facade.
 *
 * Contract:
 * * `prepareImport` only reads/stages/validates; it never compiles JS, loads
 *   DEX classes, dlopen()s native code or calls a package entry point.
 * * `commitImport` and `setEnabled` only publish metadata/registration.
 * * The first `invoke`/`selfTest` initializes the component on its own worker.
 * * `setEnabled(false)` and `uninstall` reject new calls, revoke grants and
 *   wait for in-flight calls within a bounded close budget.
 * * Failures are returned as typed [PackDiagnostic] / [PackException], never
 *   silent fallbacks.
 *
 * Implementations are supplied by a later host slice; this module only fixes
 * the contract.
 */
interface PackManager {

    suspend fun prepareImport(
        input: InputStream,
        hint: PackImportHint = PackImportHint(),
    ): ImportPreview

    suspend fun commitImport(previewToken: String, grant: PackGrant): PackInfo

    suspend fun setEnabled(id: PackId, enabled: Boolean): PackResult

    suspend fun uninstall(id: PackId): UninstallReport

    /**
     * Host/source convenience invoke. Only [PackCaller.Source] or
     * [PackCaller.Host] is valid; implementations must reject MCP/Web callers
     * because this overload has no expected PackRef and would leave a
     * stale-registration window. Registered callbacks use [PackInvocation].
     */
    suspend fun invoke(
        id: PackId,
        command: String,
        argJson: String,
        caller: PackCaller,
        timeoutMs: Long? = null,
        cancellation: CancellationSignal = CancellationSignal(),
    ): PackResult

/**
     * Synchronous bridge used by book-source JS. It must dispatch the actual
     * work to the pack worker, enforce the timeout and propagate
     * [cancellation]; it must never execute package code on the caller/main
     * thread. Thread interruption is mapped to cooperative cancellation and
     * is not a hard native kill.
     *
     * This overload has no expected PackRef and is only for
     * [PackCaller.Source] / [PackCaller.Host]; implementations must reject
     * MCP/Web callers. Registered MCP/Web callbacks must use the
     * [PackInvocation] overload.
     */
    fun invokeBlocking(
        id: PackId,
        command: String,
        argJson: String,
        caller: PackCaller,
        timeoutMs: Long? = null,
        cancellation: CancellationSignal = CancellationSignal(),
    ): PackResult

    /**
     * Entry-aware invocation. The manager must validate expectedRef, epoch,
     * enabled state, entry-to-command mapping and entry-level grant atomically
     * before enqueuing; a stale entry must fail and never dispatch to a newer
     * revision.
     */
    suspend fun invoke(invocation: PackInvocation): PackResult

    /** Blocking form of the entry-aware invocation; same atomic admission rules. */
    fun invokeBlocking(invocation: PackInvocation): PackResult

    suspend fun selfTest(id: PackId): SelfTestReport

    /**
     * Reads a previously produced self-test report without executing package
     * code. `runId == null` means the latest report for that pack; returns
     * null when no such report exists (including after uninstall).
     */
    fun selfTestReport(id: PackId, runId: String? = null): SelfTestReport?

    fun info(id: PackId): PackInfo?

    fun list(): List<PackInfo>
}

/**
 * Host-injected environment. The framework never hardcodes an app package
 * name or application id; [packsRoot] must come from the current
 * `Context.filesDir` of the running app.
 */
interface PackHostEnvironment {
    val packsRoot: File
    val platform: PackPlatform
    val frameworkPackLibVersion: PackLibVersion
    val defaultNativeLoadMode: NativeLoadMode

    /** Must return null when a runtime kind is not installed/supported. */
    fun runtimeProvider(kind: ComponentKind): PackRuntimeProvider?
}