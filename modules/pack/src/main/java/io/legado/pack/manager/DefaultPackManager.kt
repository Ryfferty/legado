package io.legado.pack.manager

import io.legado.pack.api.CancellationSignal
import io.legado.pack.api.CommandSpec
import io.legado.pack.api.ComponentKind
import io.legado.pack.api.ComponentSpec
import io.legado.pack.api.DiagnosticEvent
import io.legado.pack.api.ImportPreview
import io.legado.pack.api.PackCallContext
import io.legado.pack.api.PackCallStatus
import io.legado.pack.api.PackCaller
import io.legado.pack.api.PackCancelledException
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackEntryPoint
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackExecutionPolicy
import io.legado.pack.api.PackGrant
import io.legado.pack.api.PackHostEnvironment
import io.legado.pack.api.PackId
import io.legado.pack.api.PackImportHint
import io.legado.pack.api.PackInfo
import io.legado.pack.api.PackInstance
import io.legado.pack.api.PackInvocation
import io.legado.pack.api.PackJsProtocol
import io.legado.pack.api.PackLibVersion
import io.legado.pack.api.PackLogger
import io.legado.pack.api.PackManifest
import io.legado.pack.api.PackMetrics
import io.legado.pack.api.PackManager
import io.legado.pack.api.PackRef
import io.legado.pack.api.PackResourceLimits
import io.legado.pack.api.PackResult
import io.legado.pack.api.PackRuntimeProvider
import io.legado.pack.api.PackState
import io.legado.pack.api.PackStorageLayout
import io.legado.pack.api.PackTimeoutException
import io.legado.pack.api.RelativePath
import io.legado.pack.api.RevisionId
import io.legado.pack.api.SelfTestCaseResult
import io.legado.pack.api.SelfTestReport
import io.legado.pack.api.SelfTestStatus
import io.legado.pack.api.UninstallReport
import io.legado.pack.api.RuntimeAvailability
import io.legado.pack.diagnostics.PackDiagnosticsStore
import io.legado.pack.diagnostics.PackLogEntry
import io.legado.pack.diagnostics.PackLogStore
import io.legado.pack.diagnostics.PackMetricsTracker
import io.legado.pack.diagnostics.SelfTestReportStore
import io.legado.pack.primitives.PackComponentInvokerImpl
import io.legado.pack.primitives.PackCryptoImpl
import io.legado.pack.primitives.PackEpochGuard
import io.legado.pack.primitives.PackHttpClientImpl
import io.legado.pack.primitives.PackKeyValueStoreImpl
import io.legado.pack.primitives.PackLoggerImpl
import io.legado.pack.primitives.PackPrimitivesContext
import io.legado.pack.registry.PackRegistration
import io.legado.pack.registry.PackRegistry
import io.legado.pack.registry.RegistrationResult
import io.legado.pack.runtime.isResidentLoadFailure
import io.legado.pack.storage.ContentHasher
import io.legado.pack.storage.ManifestJsonCodec
import io.legado.pack.storage.PackFormat
import io.legado.pack.storage.PackHex
import io.legado.pack.storage.PackInstallLedger
import io.legado.pack.storage.PackImporter
import io.legado.pack.storage.PackStore
import io.legado.pack.storage.PreparedPack
import io.legado.pack.storage.StoredPack
import io.legado.pack.storage.AtomicFiles
import io.legado.pack.manifest.ManifestValidationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Concrete pack lifecycle manager.
 *
 * Design invariants:
 * * import/commit/enable only publish metadata + registry entries; no runtime
 *   provider is touched until the first invoke/self-test;
 * * each pack has at most one daemon worker with a bounded queue, created
 *   lazily and reclaimed after idling;
 * * disable/uninstall first reject new calls and revoke registration, then
 *   cancel and wait for package code within the close budget;
 * * a call that times out and does not stop quarantines the pack
 *   (NEEDS_RESTART) instead of spawning replacement threads;
 * * every primitive and every result delivery re-checks the revision epoch.
 */
class DefaultPackManager(
    private val environment: PackHostEnvironment,
    private val registry: PackRegistry,
    private val limits: PackResourceLimits = PackResourceLimits(),
    private val executionPolicy: PackExecutionPolicy = PackExecutionPolicy(),
    /**
     * Optional host-owned dynamic write-tool authority. The import-time
     * [PackGrant] cannot observe later write toggles; a host that keeps a
     * separate write-grant store can pass a predicate here so manager
     * admission and the host UI agree. Defaults to no dynamic grant.
     */
    private val dynamicWriteToolGrant: ((PackRef, PackManifest, String) -> Boolean)? = null,
) : PackManager, AutoCloseable {

    private val layout = PackStorageLayout(environment.packsRoot)
    private val validationContext = ManifestValidationContext(
        frameworkPackLibVersion = environment.frameworkPackLibVersion,
        supportedSchemaVersion = PackManifest.SCHEMA_VERSION,
        maxContentChars = limits.maxManifestChars,
    )
    private val store = PackStore(layout, validationContext)
    private val importer = PackImporter(layout, limits, validationContext)

    /**
     * Admission epochs stay monotonic across an uninstall/reinstall of the
     * same pack id: host-side grant bookkeeping binds permissions to
     * (id, revision, epoch, hash), and a reinstall that restarts at epoch 1
     * would make a previously revoked grant match again after the revocation
     * write failed. The high-water mark lives outside the pack directory and
     * survives deletion; see [PackInstallLedger].
     */
    private val installLedger = PackInstallLedger(layout.packsRoot)

    private val stateLock = Any()
    private val persistLock = Any()
    private val mutationLock = ReentrantLock()
    private val random = SecureRandom()

    private val records = LinkedHashMap<PackId, PackRecord>()
    private val previews = LinkedHashMap<String, PreparedPack>()
    private val activeCalls = LinkedHashMap<String, ActiveCall>()

    /**
     * Load failures whose trusted code stayed mapped in this process (native
     * dlopen succeeded, a later step failed), keyed by pack id.
     *
     * This witness deliberately lives outside [records]: disable advances the
     * admission epoch and uninstall removes the record *before* either waits
     * for an in-flight load, so the record-scoped runtimeNeedsRestart mark is
     * rejected exactly when such a failure lands. The entry below survives
     * both mutations; disable/uninstall read it back after the pack's worker
     * drained and fold it into their residency verdict.
     */
    private val residentLoadFailures = LinkedHashMap<PackId, PackDiagnostic>()
    private val componentCallDepth = ThreadLocal<Int>()

    @Volatile
    private var closed = false

    private val diagnosticsStore = PackDiagnosticsStore()
    private val logStore = PackLogStore(layout, limits) { diagnostic ->
        diagnosticsStore.record(null, null, diagnostic.code, diagnostic.message, diagnostic.path, diagnostic.details)
    }
    private val selfTestStore = SelfTestReportStore(layout) { diagnostic ->
        diagnosticsStore.record(null, null, diagnostic.code, diagnostic.message, diagnostic.path, diagnostic.details)
    }

    init {
        store.ensureRoot()
        recoverFromDisk()
    }

    // ------------------------------------------------------------------
    // PackManager: import
    // ------------------------------------------------------------------

    override suspend fun prepareImport(
        input: InputStream,
        hint: PackImportHint,
    ): ImportPreview = withContext(Dispatchers.IO) {
        ensureOpen()
        val job = kotlinx.coroutines.currentCoroutineContext()[Job]
        val checkCancelled = {
            if (job != null && !job.isActive) {
                throw CancellationException("pack import cancelled")
            }
        }
        val raw = importer.prepare(
            input = input,
            hint = hint,
            baseRevision = null,
            baseEpoch = null,
            checkCancelled = checkCancelled,
        )
        val existing = synchronized(stateLock) { records[raw.preview.manifest.id]?.stored }
        val prepared = raw.copy(
            baseRevision = existing?.revision,
            baseEpoch = existing?.epoch,
            preExistingPack = existing != null,
        )
        val discarded = ArrayList<PreparedPack>()
        var publishRejected = false
        val now = System.currentTimeMillis()
        synchronized(stateLock) {
            // The closed check at the entry ran before the (long, unlocked)
            // staging work; close() may have drained and returned since. The
            // publication must re-validate under the same lock that close()
            // clears the map with, otherwise a manager that is already closed
            // would silently keep new staging alive.
            if (closed) {
                publishRejected = true
                // Reclaim the preparation that completed after close instead
                // of leaving its staging behind with no owner.
                discarded += prepared
            } else {
                previews[prepared.preview.token] = prepared
                val iterator = previews.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val expired = now - entry.value.createdAtEpochMs > PendingPreviewTtlMs
                    val overCapacity = previews.size > MaxPendingPreviews
                    if (!expired && !overCapacity) break
                    iterator.remove()
                    discarded += entry.value
                }
            }
        }
        discarded.forEach { evicted ->
            // Evicted/closed preparations are background cleanup: a discard
            // failure is reported as an IO red card instead of failing the
            // fresh import that triggered the eviction (or being swallowed).
            runCatching { importer.discard(evicted) }.onFailure { error ->
                diagnosticsStore.record(
                    null,
                    null,
                    PackErrorCode.IO_FAILED,
                    "cannot discard import staging: ${error.message}",
                    path = "staging/${evicted.preview.token}",
                )
            }
        }
        if (publishRejected) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.INTERNAL,
                    message = "pack manager is closed",
                ),
            )
        }
        prepared.preview
    }

    override suspend fun commitImport(previewToken: String, grant: PackGrant): PackInfo =
        withContext(Dispatchers.IO) {
            mutationLock.withLock { commitImportLocked(previewToken, grant) }
        }

    private fun commitImportLocked(previewToken: String, grant: PackGrant): PackInfo {
        ensureOpen()
        val prepared = synchronized(stateLock) { previews[previewToken] }
            ?: throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.INVALID_REQUEST,
                    message = "unknown or expired import preview; prepare the import again",
                    path = "import",
                ),
            )
        if (!prepared.stagingDir.isDirectory) {
            synchronized(stateLock) { previews.remove(previewToken) }
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.INVALID_REQUEST,
                    message = "staged import content is no longer available; prepare the import again",
                    path = "staging",
                ),
            )
        }
        val manifest = try {
            ManifestJsonCodec.decode(prepared.manifestJson, validationContext)
        } catch (e: Exception) {
            synchronized(stateLock) { previews.remove(previewToken) }
            discardPreparedStaging(prepared)
            throw e
        }
        val id = manifest.id
        if (id != prepared.preview.manifest.id || manifest != prepared.preview.manifest) {
            synchronized(stateLock) { previews.remove(previewToken) }
            discardPreparedStaging(prepared)
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.MANIFEST_INVALID,
                    message = "staged manifest changed after preview",
                    path = "manifest",
                ),
            )
        }
        val revision = importer.verifyStagedContent(prepared)

        if (!grant.approves(manifest.permissions)) {
            val permissions = manifest.permissions
            val diagnostic = if (permissions.requiresOpenNetworkApproval && !grant.networkOpenApproved) {
                PackDiagnostic(
                    code = PackErrorCode.NETWORK_OPEN_REQUIRES_CONSENT,
                    message = "open network access requires explicit approval for this permission hash",
                    path = "grant",
                )
            } else {
                PackDiagnostic(
                    code = PackErrorCode.PERMISSION_DENIED,
                    message = "grant does not match the manifest permission hash",
                    path = "grant",
                )
            }
            // Keep the preview so the host can show permissions and retry with
            // the exact hash / network approval.
            throw PackException(diagnostic)
        }

        var current: PackRecord? = null
        synchronized(stateLock) {
            current = records[id]
            if (prepared.baseRevision != current?.stored?.revision ||
                prepared.baseEpoch != current?.stored?.epoch
            ) {
                throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.INVALID_REQUEST,
                        message = "pack changed since preview; prepare the import again",
                        path = "import",
                    ),
                )
            }
            if (current?.runtimeNeedsRestart == true) {
                throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.NEEDS_RESTART,
                        message = "pack has resident code; restart before upgrading it",
                    ),
                )
            }
            if (current?.transition == true) {
                throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.INVALID_REQUEST,
                        message = "pack is already being updated",
                    ),
                )
            }
            current?.transition = true
        }

        val oldStored = current?.stored
        val oldRevision = oldStored?.revision
        val oldScope = current?.scope
        val worker = current?.worker
        var published = false
        try {
            store.ensurePackDirectories(id)
            // Reserve the admission epoch before any content is moved. The
            // ledger's per-pack high-water mark lives outside the pack
            // directory, so an uninstall followed by a reinstall of identical
            // content cannot fall back to a previously used epoch and make
            // host-side grant bookkeeping match a revoked entry again.
            val nextEpoch = maxOf(
                oldStored?.epoch ?: 0L,
                installLedger.highestEpoch(id),
            ) + 1L
            installLedger.reserve(id, nextEpoch)
            if (current?.stored?.revision == revision && store.revisionExists(id, revision)) {
                val installedDigest = ContentHasher.digestDirectory(store.revisionDir(id, revision))
                if (installedDigest != revision) {
                    throw PackException(
                        PackDiagnostic(
                            code = PackErrorCode.IO_FAILED,
                            message = "the installed revision is corrupted; uninstall before reinstalling",
                            path = "code/${revision.value}",
                        ),
                    )
                }
            }
            store.installRevision(id, revision, prepared.stagingDir)
            val now = System.currentTimeMillis()
            val stored = StoredPack(
                schemaVersion = 1,
                id = id,
                manifest = manifest,
                manifestJson = prepared.manifestJson,
                revision = revision,
                contentSha256 = revision,
                epoch = nextEpoch,
                enabled = oldStored?.enabled ?: true,
                installedAtEpochMs = oldStored?.installedAtEpochMs ?: now,
                needsRestart = false,
                broken = null,
                grant = grant,
                lastSelfTestRunId = oldStored?.lastSelfTestRunId,
                format = prepared.format,
            )
            if (oldStored != null && oldStored.grant.permissionHash != manifest.permissions.digest()) {
                diagnosticsStore.record(
                    packRef = stored.ref,
                    requestId = null,
                    code = PackErrorCode.INVALID_REQUEST,
                    message = "pack permissions changed and were re-approved by a new grant",
                )
            }
            synchronized(persistLock) { store.writeMetadata(stored) }
            val registration = replaceRegistrations(
                currentRegistrations(extra = stored, excludeId = id),
            )
            if (registration is RegistrationResult.Rejected) {
                throw PackException(registration.diagnostics.first())
            }
            recordSinkFailures(registration as RegistrationResult.Applied)
            published = true
            synchronized(stateLock) {
                if (current == null) {
                    records[id] = PackRecord(stored)
                } else {
                    current!!.stored = stored
                    // The new epoch was already written to current.json above;
                    // bump the snapshot generation so a markBroken/markHealthy
                    // snapshot taken before this replacement is recognized as
                    // stale even though it may carry the same epoch.
                    current!!.persistSeq += 1L
                    current!!.runtimeBroken = null
                    current!!.runtimeNeedsRestart = false
                    current!!.scope = null
                    current!!.metrics = PackMetricsTracker()
                }
            }
            synchronized(stateLock) { previews.remove(previewToken) }

            var drainFailed = false
            if (current != null) {
                cancelActiveCalls(id, oldStored?.ref)
                val drainWorker = worker
                if (drainWorker != null && !drainWorker.awaitIdle(executionPolicy.closeTimeoutMs)) {
                    drainFailed = true
                }
                if (!drainFailed) {
                    val closeOutcome = closeRuntimeScope(id, oldScope, drainWorker)
                    if (!closeOutcome.released) {
                        val diagnostic = closeOutcome.failure ?: PackDiagnostic(
                            code = PackErrorCode.NEEDS_RESTART,
                            message = "previous revision code did not close; restart required",
                        )
                        markNeedsRestart(id, stored.ref, diagnostic)
                    } else if (oldRevision != null && oldRevision != revision) {
                        runCatching { store.deleteRevision(id, oldRevision) }
                            .onFailure { error ->
                                diagnosticsStore.record(
                                    stored.ref,
                                    null,
                                    PackErrorCode.RESIDUAL_FILES,
                                    "cannot delete previous revision: ${error.message}",
                                    path = "code/${oldRevision.value}",
                                )
                            }
                    }
                } else {
                    markNeedsRestart(
                        id,
                        stored.ref,
                        PackDiagnostic(
                            code = PackErrorCode.NEEDS_RESTART,
                            message = "previous pack calls did not stop within ${executionPolicy.closeTimeoutMs} ms",
                        ),
                    )
                }
                synchronized(stateLock) { current!!.transition = false }
                // Persist outside stateLock: persistMetadata re-validates the
                // epoch under persistLock and must never be nested inside
                // stateLock (which would invert the lock order).
                synchronized(stateLock) { records[id] }?.let { persistRecord(it) }
            }
            return synchronized(stateLock) { records[id]!!.toInfo() }
        } catch (e: Exception) {
            if (!published) {
                rollbackFailedCommit(id, current, oldStored, revision, prepared)
                synchronized(stateLock) { previews.remove(previewToken) }
            }
            throw e
        }
    }

    private fun rollbackFailedCommit(
        id: PackId,
        current: PackRecord?,
        oldStored: StoredPack?,
        newRevision: RevisionId,
        prepared: PreparedPack,
    ) {
        runCatching {
            if (oldStored != null) {
                synchronized(persistLock) { store.writeMetadata(oldStored) }
                if (oldStored.revision != newRevision) {
                    store.deleteRevision(id, newRevision)
                }
            } else {
                store.deleteMetadata(id)
                store.deletePack(id)
            }
        }.onFailure { error ->
            diagnosticsStore.record(
                oldStored?.ref,
                null,
                PackErrorCode.IO_FAILED,
                "rollback after failed import could not complete: ${error.message}",
                path = id.value,
            )
        }
        synchronized(stateLock) { current?.transition = false }
        // The rollback's own IO failure must not mask the original commit
        // error: a failed staging discard is reported as its own red card.
        discardPreparedStaging(prepared)
    }

    /**
     * Discards a preview's staging with a typed failure contract: residue
     * left behind by a failed delete is reported as an IO red card diagnostic
     * instead of being swallowed (AtomicFiles.deleteRecursively reports
     * residue through its Boolean, not through an exception).
     */
    private fun discardPreparedStaging(prepared: PreparedPack) {
        runCatching { importer.discard(prepared) }.onFailure { error ->
            diagnosticsStore.record(
                null,
                null,
                PackErrorCode.IO_FAILED,
                "cannot discard import staging: ${error.message}",
                path = "staging/${prepared.preview.token}",
            )
        }
    }

    // ------------------------------------------------------------------
    // PackManager: lifecycle
    // ------------------------------------------------------------------

    override suspend fun setEnabled(id: PackId, enabled: Boolean): PackResult =
        withContext(Dispatchers.IO) {
            mutationLock.withLock {
                val start = System.nanoTime()
                val requestId = newRequestId()
                if (enabled) enablePack(id, requestId, start) else disablePack(id, requestId, start)
            }
        }

    private fun enablePack(id: PackId, requestId: String, startNanos: Long): PackResult {
        ensureOpen()
        val record = synchronized(stateLock) { records[id] }
            ?: return failure(requestId, PACK_NOT_FOUND, "pack is not installed", startNanos)
        val oldStored = synchronized(stateLock) { record.stored }
        synchronized(stateLock) {
            if (record.runtimeNeedsRestart) {
                return failure(requestId, NEEDS_RESTART, "pack requires a process restart", startNanos)
            }
        }
        if (oldStored.enabled) return PackResult.ok(requestId, null, elapsed(startNanos))
        synchronized(stateLock) {
            if (record.transition) return failure(requestId, INVALID_REQUEST, "pack is being updated", startNanos)
        }
        if (!store.revisionExists(id, oldStored.revision)) {
            markBroken(
                id,
                oldStored.ref,
                PackDiagnostic(
                    code = PackErrorCode.COMPONENT_MISSING,
                    message = "installed revision is missing",
                    path = "code/${oldStored.revision.value}",
                ),
            )
            return failure(requestId, COMPONENT_MISSING, "installed revision is missing", startNanos)
        }
        if (!oldStored.grantMatchesPermissions || !oldStored.grant.approves(oldStored.manifest.permissions)) {
            val code = if (oldStored.manifest.permissions.requiresOpenNetworkApproval &&
                !oldStored.grant.networkOpenApproved
            ) {
                NETWORK_OPEN_REQUIRES_CONSENT
            } else {
                PERMISSION_DENIED
            }
            return failure(requestId, code, "stored grant no longer matches the manifest", startNanos)
        }
        val newStored = oldStored.copy(enabled = true, epoch = oldStored.epoch + 1L)
        // The new epoch is the identity host-side grants bind to, so it must
        // reach the durable ledger before it becomes visible: a reinstall
        // after an uninstall must never land on an epoch a previous enable
        // already used for authorization.
        try {
            installLedger.reserve(id, newStored.epoch)
        } catch (e: PackException) {
            return failure(requestId, IO_FAILED, "cannot persist the admission epoch: ${e.diagnostic.message}", startNanos)
        }
        synchronized(stateLock) {
            record.stored = newStored
            record.transition = true
            record.runtimeNeedsRestart = false
        }
        if (!persistRecord(record)) {
            synchronized(stateLock) {
                record.stored = oldStored
                record.transition = false
            }
            return failure(requestId, IO_FAILED, "cannot persist enabled state", startNanos)
        }
        val registration = replaceRegistrations(currentRegistrations())
        if (registration is RegistrationResult.Rejected) {
            synchronized(stateLock) {
                record.stored = oldStored
                record.transition = false
            }
            persistRecord(record)
            val diagnostic = registration.diagnostics.first()
            return PackResult.failure(requestId, diagnostic, elapsed(startNanos))
        }
        recordSinkFailures(registration as RegistrationResult.Applied)
        synchronized(stateLock) { record.transition = false }
        return PackResult.ok(requestId, null, elapsed(startNanos))
    }

    private fun disablePack(id: PackId, requestId: String, startNanos: Long): PackResult {
        ensureOpen()
        val record = synchronized(stateLock) { records[id] }
            ?: return failure(requestId, PACK_NOT_FOUND, "pack is not installed", startNanos)
        val oldStored = synchronized(stateLock) { record.stored }
        if (!oldStored.enabled) {
            return if (record.runtimeNeedsRestart) {
                failure(requestId, NEEDS_RESTART, "pack requires a process restart", startNanos)
            } else {
                PackResult.ok(requestId, null, elapsed(startNanos))
            }
        }
        synchronized(stateLock) {
            if (record.transition) return failure(requestId, INVALID_REQUEST, "pack is being updated", startNanos)
        }
        val newStored = oldStored.copy(enabled = false, epoch = oldStored.epoch + 1L)
        // Same contract as enable: the disable epoch must be durable before it
        // is used, otherwise a reinstall could reuse it after an uninstall.
        try {
            installLedger.reserve(id, newStored.epoch)
        } catch (e: PackException) {
            return failure(requestId, IO_FAILED, "cannot persist the admission epoch: ${e.diagnostic.message}", startNanos)
        }
        synchronized(stateLock) {
            record.stored = newStored
            record.transition = true
        }
        if (!persistRecord(record)) {
            synchronized(stateLock) {
                record.stored = oldStored
                record.transition = false
            }
            return failure(requestId, IO_FAILED, "cannot persist disabled state", startNanos)
        }
        cancelActiveCalls(id, null)
        val registration = replaceRegistrations(currentRegistrations())
        var registrationFailure: PackDiagnostic? = null
        if (registration is RegistrationResult.Rejected) {
            registrationFailure = registration.diagnostics.first()
            diagnosticsStore.recordDiagnostic(newStored.ref, requestId, registrationFailure)
        } else {
            recordSinkFailures(registration as RegistrationResult.Applied)
        }
        val worker = synchronized(stateLock) { record.worker }
        val scope = synchronized(stateLock) { record.scope }
        // A load that failed after mapping trusted code set this mark; no
        // instance exists to close, so the close outcome alone would claim a
        // clean release while the code is still resident.
        val residentLoadMarked = synchronized(stateLock) { record.runtimeNeedsRestart }
        val drained = worker?.awaitIdle(executionPolicy.closeTimeoutMs) ?: true
        val closeOutcome = if (drained) {
            closeRuntimeScope(id, scope, worker)
        } else {
            CloseSummary.resident("pack calls did not stop within ${executionPolicy.closeTimeoutMs} ms")
        }
        // A load that failed while this disable was in flight could not write
        // the record mark (the admission epoch already advanced above); read
        // the manager-scoped witness only now that the worker drained and
        // every in-flight failure has landed.
        val residentFailure = peekResidentLoadFailure(id)
        var needsRestart = !drained || !closeOutcome.released || closeOutcome.needsRestart ||
            residentLoadMarked || residentFailure != null
        if (closeOutcome.released) {
            synchronized(stateLock) { record.scope = null }
        }
        if (needsRestart) {
            markNeedsRestart(
                id,
                newStored.ref,
                residentFailure ?: closeOutcome.failure ?: PackDiagnostic(
                    code = PackErrorCode.NEEDS_RESTART,
                    message = "pack code could not be released; restart required",
                ),
            )
        } else if (worker != null) {
            val stopped = worker.shutdownBounded(executionPolicy.closeTimeoutMs)
            if (stopped) {
                synchronized(stateLock) {
                    if (record.worker === worker) record.worker = null
                }
            } else {
                needsRestart = true
                markNeedsRestart(
                    id,
                    newStored.ref,
                    PackDiagnostic(
                        code = PackErrorCode.NEEDS_RESTART,
                        message = "pack worker did not stop within ${executionPolicy.closeTimeoutMs} ms",
                    ),
                )
            }
        }
        synchronized(stateLock) { record.transition = false }
        persistRecord(record)
        val failure = when {
            needsRestart -> residentFailure ?: closeOutcome.failure ?: PackDiagnostic(
                code = PackErrorCode.NEEDS_RESTART,
                message = "pack disabled but code is still resident; restart required",
            )

            closeOutcome.failure != null -> closeOutcome.failure!!
            registrationFailure != null -> registrationFailure
            else -> null
        }
        return if (failure != null) {
            PackResult.failure(requestId, failure, elapsed(startNanos))
        } else {
            PackResult.ok(requestId, null, elapsed(startNanos))
        }
    }

    override suspend fun uninstall(id: PackId): UninstallReport = withContext(Dispatchers.IO) {
        mutationLock.withLock { uninstallLocked(id) }
    }

    private fun uninstallLocked(id: PackId): UninstallReport {
        ensureOpen()
        val record = synchronized(stateLock) { records[id] }
        if (record == null) {
            val packDir = store.packDir(id)
            if (!packDir.exists()) {
                return UninstallReport(
                    packId = id,
                    registrationRevoked = false,
                    residualPaths = emptyList(),
                    loadedCodeStillResident = false,
                    needsRestart = false,
                    failure = PackDiagnostic(
                        code = PackErrorCode.PACK_NOT_FOUND,
                        message = "pack is not installed",
                    ),
                )
            }
            val registration = replaceRegistrations(currentRegistrations())
            var registrationFailure: PackDiagnostic? = null
            var sinkFailure: PackDiagnostic? = null
            if (registration is RegistrationResult.Rejected) {
                registrationFailure = registration.diagnostics.first()
                diagnosticsStore.recordDiagnostic(null, null, registrationFailure)
            } else {
                val applied = registration as RegistrationResult.Applied
                recordSinkFailures(applied)
                // Same contract as the installed-pack branch: a sink failure
                // means a live projector may still show this pack's tools.
                sinkFailure = applied.sinkFailures.firstOrNull()
            }
            val residual = store.deletePack(id)
            // Decrement the log budget only after the pack's files are really
            // gone; PackLogStore.onPackRemoved subtracts unconditionally and
            // would under-report while residual files keep the log on disk.
            if (residual.isEmpty()) {
                logStore.onPackRemoved(id)
            }
            diagnosticsStore.onPackRemoved(id)
            return UninstallReport(
                packId = id,
                registrationRevoked = registration is RegistrationResult.Applied,
                residualPaths = residual,
                loadedCodeStillResident = false,
                needsRestart = false,
                failure = when {
                    residual.isNotEmpty() -> PackDiagnostic(
                        code = PackErrorCode.RESIDUAL_FILES,
                        message = "pack files remain after uninstall",
                        path = id.value,
                    )
                    registrationFailure != null -> registrationFailure
                    sinkFailure != null -> sinkFailure
                    else -> null
                },
            )
        }
        synchronized(stateLock) {
            if (record.transition) {
                return UninstallReport(
                    packId = id,
                    registrationRevoked = false,
                    residualPaths = emptyList(),
                    loadedCodeStillResident = false,
                    needsRestart = false,
                    failure = PackDiagnostic(
                        code = PackErrorCode.INVALID_REQUEST,
                        message = "pack is being updated",
                    ),
                )
            }
        }
        val oldStored = synchronized(stateLock) { record.stored }
        val disabled = oldStored.copy(enabled = false, epoch = oldStored.epoch + 1L)
        // The final epoch increment must be durable BEFORE the pack content is
        // deleted: an uninstall whose ledger write fails aborts with the pack
        // intact instead of allowing a later reinstall to reuse an epoch that
        // already authorized host-side grants.
        try {
            installLedger.reserve(id, disabled.epoch)
        } catch (e: PackException) {
            return UninstallReport(
                packId = id,
                registrationRevoked = false,
                residualPaths = emptyList(),
                loadedCodeStillResident = false,
                needsRestart = false,
                failure = PackDiagnostic(
                    code = PackErrorCode.IO_FAILED,
                    message = "cannot persist the admission epoch: ${e.diagnostic.message}",
                ),
            )
        }
        synchronized(stateLock) {
            record.stored = disabled
            record.transition = true
        }
        if (!persistRecord(record)) {
            synchronized(stateLock) {
                record.stored = oldStored
                record.transition = false
            }
            return UninstallReport(
                packId = id,
                registrationRevoked = false,
                residualPaths = emptyList(),
                loadedCodeStillResident = false,
                needsRestart = false,
                failure = PackDiagnostic(
                    code = PackErrorCode.IO_FAILED,
                    message = "cannot persist uninstall state",
                ),
            )
        }
        synchronized(stateLock) { records.remove(id) }
        cancelActiveCalls(id, null)
        val registration = replaceRegistrations(currentRegistrations())
        var registrationFailure: PackDiagnostic? = null
        var sinkFailure: PackDiagnostic? = null
        if (registration is RegistrationResult.Rejected) {
            registrationFailure = registration.diagnostics.first()
            diagnosticsStore.recordDiagnostic(disabled.ref, null, registrationFailure)
        } else {
            val applied = registration as RegistrationResult.Applied
            recordSinkFailures(applied)
            // A sink failure means a live projector (e.g. the MCP bridge) may
            // still show this pack's tools; the uninstall report must say so.
            sinkFailure = applied.sinkFailures.firstOrNull()
        }
        val worker = synchronized(stateLock) { record.worker }
        val scope = synchronized(stateLock) { record.scope }
        // A load that failed after mapping trusted code (e.g. native dlopen
        // succeeded and a later step failed) set this mark: no instance was
        // registered, so closeRuntimeScope cannot see the residency and would
        // report a clean release. The mark only covers failures that landed
        // while the record still matched the failing ref.
        val residentLoadMarked = synchronized(stateLock) { record.runtimeNeedsRestart }
        val drained = worker?.awaitIdle(executionPolicy.closeTimeoutMs) ?: true
        val closeOutcome = if (drained) {
            closeRuntimeScope(id, scope, worker)
        } else {
            CloseSummary.resident("pack calls did not stop within ${executionPolicy.closeTimeoutMs} ms")
        }
        // The record was removed above, so a load that failed while this
        // uninstall was draining could only record the residency in the
        // manager-scoped witness. Consume it now that the worker drained: the
        // mapping it describes is pinned until a process restart, and a
        // reinstall of the same id must not inherit this installation's
        // failure report.
        val residentFailure = takeResidentLoadFailure(id)
        // Aligned with disablePack: a worker that did not stop within the
        // budget keeps the process marked for restart instead of letting the
        // uninstall report claim a clean state.
        val workerStopped = worker?.shutdownBounded(executionPolicy.closeTimeoutMs) ?: true
        val residual = store.deletePack(id)
        if (residual.isEmpty()) {
            logStore.onPackRemoved(id)
        }
        diagnosticsStore.onPackRemoved(id)
        val loadedResident = !drained || !closeOutcome.released || residentLoadMarked || residentFailure != null
        val needsRestart = loadedResident || closeOutcome.needsRestart || !workerStopped
        val failure = when {
            residual.isNotEmpty() -> PackDiagnostic(
                code = PackErrorCode.RESIDUAL_FILES,
                message = "pack files remain after uninstall",
                path = id.value,
            )

            closeOutcome.failure != null -> closeOutcome.failure!!
            residentLoadMarked || residentFailure != null -> residentFailure ?: PackDiagnostic(
                code = PackErrorCode.NEEDS_RESTART,
                message = "pack code from a failed load remains mapped in this process; restart required",
                path = id.value,
            )

            registrationFailure != null -> registrationFailure
            sinkFailure != null -> sinkFailure
            else -> null
        }
        if (residual.isNotEmpty()) {
            // Keep a disabled record so `info` reflects the files that remain
            // and a later uninstall/restart can finish the job.
            val retained = PackRecord(
                stored = disabled,
                runtimeNeedsRestart = needsRestart,
                runtimeBroken = record.runtimeBroken,
            )
            synchronized(stateLock) { records[id] = retained }
            persistRecord(retained)
        }
        return UninstallReport(
            packId = id,
            registrationRevoked = registration is RegistrationResult.Applied,
            residualPaths = residual,
            loadedCodeStillResident = loadedResident,
            needsRestart = needsRestart,
            failure = failure,
        )
    }

    // ------------------------------------------------------------------
    // PackManager: invocation
    // ------------------------------------------------------------------

    override suspend fun invoke(
        id: PackId,
        command: String,
        argJson: String,
        caller: PackCaller,
        timeoutMs: Long?,
        cancellation: CancellationSignal,
    ): PackResult {
        // The worker-thread guard must run before the dispatch hop: inside
        // withContext(Dispatchers.IO) the thread belongs to the IO pool, so
        // invokeDirect's name-based check is vacuously false there.
        if (isOnOwnWorker(id)) {
            return PackResult.failure(
                newRequestId(),
                PackDiagnostic(
                    code = PackErrorCode.INTERNAL,
                    message = "blocking pack invocation cannot run on its own pack worker",
                ),
                0L,
            )
        }
        return withContext(Dispatchers.IO) {
            invokeDirect(id, command, argJson, caller, timeoutMs, cancellation)
        }
    }

    override fun invokeBlocking(
        id: PackId,
        command: String,
        argJson: String,
        caller: PackCaller,
        timeoutMs: Long?,
        cancellation: CancellationSignal,
    ): PackResult = invokeDirect(id, command, argJson, caller, timeoutMs, cancellation)

    private fun invokeDirect(
        id: PackId,
        command: String,
        argJson: String,
        caller: PackCaller,
        timeoutMs: Long?,
        cancellation: CancellationSignal,
    ): PackResult {
        if (caller !is PackCaller.Source && caller !is PackCaller.Host) {
            return PackResult.failure(
                newRequestId(),
                PackDiagnostic(
                    code = PackErrorCode.PERMISSION_DENIED,
                    message = "this overload only accepts Source or Host callers; " +
                        "registered MCP/Web callbacks must use PackInvocation",
                ),
                0L,
            )
        }
        if (isOnOwnWorker(id)) {
            return PackResult.failure(
                newRequestId(),
                PackDiagnostic(
                    code = PackErrorCode.INTERNAL,
                    message = "blocking pack invocation cannot run on its own pack worker",
                ),
                0L,
            )
        }
        return when (val admission = admit(
            id = id,
            expectedRef = null,
            entry = null,
            directCommand = command,
            argJson = argJson,
            caller = caller,
            timeoutMs = timeoutMs,
            cancellation = cancellation,
            allowSelfTestDirect = false,
        )) {
            is Admission.Rejected -> PackResult.failure(admission.requestId, admission.diagnostic, 0L)
            is Admission.Ready -> executePlanBlocking(admission.plan)
        }
    }

    override suspend fun invoke(invocation: PackInvocation): PackResult {
        // Same guard as the other entry points, again before the dispatch hop:
        // a pack worker must not block waiting for its own queue.
        if (isOnOwnWorker(invocation.expectedRef.id)) {
            return PackResult.failure(
                newRequestId(),
                PackDiagnostic(
                    code = PackErrorCode.INTERNAL,
                    message = "blocking pack invocation cannot run on its own pack worker",
                ),
                0L,
            )
        }
        return withContext(Dispatchers.IO) {
            val admission = admit(
                id = invocation.expectedRef.id,
                expectedRef = invocation.expectedRef,
                entry = invocation.entry,
                directCommand = null,
                argJson = invocation.argJson,
                caller = invocation.caller,
                timeoutMs = invocation.timeoutMs,
                cancellation = invocation.cancellation,
                allowSelfTestDirect = false,
            )
            when (admission) {
                is Admission.Rejected ->
                    PackResult.failure(admission.requestId, admission.diagnostic, 0L)

                is Admission.Ready -> executePlanBlocking(admission.plan)
            }
        }
    }

    override fun invokeBlocking(invocation: PackInvocation): PackResult {
        if (isOnOwnWorker(invocation.expectedRef.id)) {
            return PackResult.failure(
                newRequestId(),
                PackDiagnostic(
                    code = PackErrorCode.INTERNAL,
                    message = "blocking pack invocation cannot run on its own pack worker",
                ),
                0L,
            )
        }
        val admission = admit(
            id = invocation.expectedRef.id,
            expectedRef = invocation.expectedRef,
            entry = invocation.entry,
            directCommand = null,
            argJson = invocation.argJson,
            caller = invocation.caller,
            timeoutMs = invocation.timeoutMs,
            cancellation = invocation.cancellation,
            allowSelfTestDirect = false,
        )
        return when (admission) {
            is Admission.Rejected -> PackResult.failure(admission.requestId, admission.diagnostic, 0L)
            is Admission.Ready -> executePlanBlocking(admission.plan)
        }
    }

    private fun admit(
        id: PackId,
        expectedRef: PackRef?,
        entry: PackEntryPoint?,
        directCommand: String?,
        argJson: String,
        caller: PackCaller,
        timeoutMs: Long?,
        cancellation: CancellationSignal,
        allowSelfTestDirect: Boolean,
    ): Admission {
        val requestId = newRequestId()
        if (argJson.length > PackJsProtocol.MAX_JSON_CHARS) {
            return Admission.Rejected(
                requestId,
                PackDiagnostic(
                    code = PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                    message = "pack argument exceeds ${PackJsProtocol.MAX_JSON_CHARS} characters",
                    path = "arg",
                ),
            )
        }
        val effectiveTimeout = try {
            executionPolicy.timeoutFor(timeoutMs)
        } catch (e: IllegalArgumentException) {
            return Admission.Rejected(
                requestId,
                PackDiagnostic(
                    code = PackErrorCode.INVALID_REQUEST,
                    message = e.message ?: "invalid timeout",
                    path = "timeoutMs",
                ),
            )
        }
        if (cancellation.isCancelled) {
            return Admission.Rejected(
                requestId,
                PackDiagnostic(
                    code = PackErrorCode.CANCELLED,
                    message = "pack call was cancelled before it started",
                ),
            )
        }
        synchronized(stateLock) {
            if (closed) {
                return Admission.Rejected(
                    requestId,
                    PackDiagnostic(PackErrorCode.INTERNAL, "pack manager is closed"),
                )
            }
            val record = records[id] ?: return Admission.Rejected(
                requestId,
                PackDiagnostic(PackErrorCode.PACK_NOT_FOUND, "pack '${id.value}' is not installed"),
            )
            val currentRef = record.ref
            if (expectedRef != null && expectedRef != currentRef) {
                return Admission.Rejected(
                    requestId,
                    PackDiagnostic(
                        code = PackErrorCode.INVALID_REQUEST,
                        message = "stale pack reference; entry belongs to another revision or epoch",
                        details = mapOf(
                            "expectedRevision" to expectedRef.revision.value,
                            "expectedEpoch" to expectedRef.epoch.toString(),
                            "currentRevision" to currentRef.revision.value,
                            "currentEpoch" to currentRef.epoch.toString(),
                        ),
                    ),
                )
            }
            if (!record.stored.enabled) {
                return Admission.Rejected(
                    requestId,
                    PackDiagnostic(PackErrorCode.PACK_DISABLED, "pack '${id.value}' is disabled"),
                )
            }
            if (record.runtimeNeedsRestart) {
                return Admission.Rejected(
                    requestId,
                    PackDiagnostic(
                        code = PackErrorCode.NEEDS_RESTART,
                        message = "pack requires a process restart after a call could not be stopped",
                    ),
                )
            }
            if (record.transition) {
                return Admission.Rejected(
                    requestId,
                    PackDiagnostic(
                        code = PackErrorCode.INVALID_REQUEST,
                        message = "pack is being enabled, disabled or upgraded",
                    ),
                )
            }
            val manifest = record.manifest
            val resolution = resolveEntry(
                ref = currentRef,
                manifest = manifest,
                grant = record.stored.grant,
                entry = entry,
                directCommand = directCommand,
                caller = caller,
                allowSelfTestDirect = allowSelfTestDirect,
            )
            val commandSpec = when (resolution) {
                null -> return Admission.Rejected(
                    requestId,
                    commandNotFound(directCommand ?: entry?.name ?: "unknown"),
                )

                is EntryResolution.Failure -> return Admission.Rejected(requestId, resolution.diagnostic)
                is EntryResolution.Success -> resolution.command
            }
            val component = manifest.componentById(commandSpec.componentId)
                ?: return Admission.Rejected(
                    requestId,
                    PackDiagnostic(
                        code = PackErrorCode.COMPONENT_MISSING,
                        message = "command '${commandSpec.name}' references missing component '${commandSpec.componentId}'",
                        path = "commands.${commandSpec.name}",
                    ),
                )
            val worker = record.worker ?: PackWorker(id, executionPolicy).also { record.worker = it }
            val start = System.nanoTime()
            activeCalls[requestId] = ActiveCall(id, currentRef, cancellation)
            return Admission.Ready(
                InvocationPlan(
                    packId = id,
                    ref = currentRef,
                    manifest = manifest,
                    command = commandSpec.name,
                    componentId = component.id,
                    argJson = argJson,
                    caller = caller,
                    requestId = requestId,
                    timeoutMs = effectiveTimeout,
                    deadlineNanos = start + effectiveTimeout * 1_000_000L,
                    cancellation = cancellation,
                    worker = worker,
                    startNanos = start,
                ),
            )
        }
    }

    private sealed interface EntryResolution {
        data class Success(val command: CommandSpec) : EntryResolution
        data class Failure(val diagnostic: PackDiagnostic) : EntryResolution
    }

    private fun resolveEntry(
        ref: PackRef,
        manifest: PackManifest,
        grant: PackGrant,
        entry: PackEntryPoint?,
        directCommand: String?,
        caller: PackCaller,
        allowSelfTestDirect: Boolean,
    ): EntryResolution? {
        return when (entry) {
            is PackEntryPoint.SourceCommand -> {
                if (caller !is PackCaller.Source) {
                    EntryResolution.Failure(permissionDenied("source command requires a Source caller"))
                } else {
                    val command = manifest.commandByName(entry.name)
                        ?: return EntryResolution.Failure(commandNotFound(entry.name))
                    if (!command.sourceExported) {
                        EntryResolution.Failure(
                            permissionDenied("command '${entry.name}' is not exported to book sources"),
                        )
                    } else {
                        EntryResolution.Success(command)
                    }
                }
            }

            is PackEntryPoint.McpTool -> {
                if (caller !is PackCaller.Mcp) {
                    EntryResolution.Failure(permissionDenied("MCP tool requires an MCP caller"))
                } else {
                    val tool = manifest.mcpTools.firstOrNull { it.name == entry.name }
                        ?: return EntryResolution.Failure(commandNotFound("mcp tool '${entry.name}'"))
                    // Write tools follow the host's dynamic grant whenever one
                    // is installed: an explicit dynamic false rejects the call
                    // even when the static import grant contained the tool.
                    // This mirrors the first-layer check in PackMcpBridge, so
                    // manager admission and the host UI agree. Read tools and
                    // hosts without a dynamic authority keep using the static
                    // grant.
                    val dynamicGrant = dynamicWriteToolGrant
                    val granted = if (tool.isWrite && dynamicGrant != null) {
                        dynamicGrant.invoke(ref, manifest, tool.name)
                    } else {
                        grant.permitsTool(tool.name)
                    }
                    if (!granted) {
                        EntryResolution.Failure(
                            permissionDenied("MCP tool '${tool.name}' is not granted to this pack"),
                        )
                    } else {
                        val command = manifest.commandByName(tool.command)
                            ?: return EntryResolution.Failure(commandNotFound(tool.command))
                        EntryResolution.Success(command)
                    }
                }
            }

            is PackEntryPoint.WebRoute -> {
                if (caller !is PackCaller.Web) {
                    EntryResolution.Failure(permissionDenied("Web route requires a Web caller"))
                } else {
                    val route = manifest.apiRoutes.firstOrNull { it.name == entry.name }
                        ?: return EntryResolution.Failure(commandNotFound("route '${entry.name}'"))
                    if (!grant.permitsRoute(route.name)) {
                        EntryResolution.Failure(
                            permissionDenied("Web route '${route.name}' is not granted to this pack"),
                        )
                    } else {
                        val command = manifest.commandByName(route.command)
                            ?: return EntryResolution.Failure(commandNotFound(route.command))
                        EntryResolution.Success(command)
                    }
                }
            }

            is PackEntryPoint.DirectCommand -> {
                if (caller !is PackCaller.Host) {
                    EntryResolution.Failure(permissionDenied("direct command requires a Host caller"))
                } else {
                    val command = manifest.commandByName(entry.name)
                        ?: return EntryResolution.Failure(commandNotFound(entry.name))
                    EntryResolution.Success(command)
                }
            }

            null -> {
                val commandName = directCommand ?: return null
                val command = manifest.commandByName(commandName)
                    ?: return EntryResolution.Failure(commandNotFound(commandName))
                when (caller) {
                    is PackCaller.Source -> if (!command.sourceExported) {
                        EntryResolution.Failure(
                            permissionDenied("command '$commandName' is not exported to book sources"),
                        )
                    } else {
                        EntryResolution.Success(command)
                    }

                    is PackCaller.Host -> EntryResolution.Success(command)
                    is PackCaller.SelfTest -> if (allowSelfTestDirect) {
                        EntryResolution.Success(command)
                    } else {
                        EntryResolution.Failure(permissionDenied("self-test caller is not allowed here"))
                    }

                    else -> EntryResolution.Failure(
                        permissionDenied("this invocation path only accepts Source, Host or SelfTest callers"),
                    )
                }
            }
        }
    }

    private data class InvocationPlan(
        val packId: PackId,
        val ref: PackRef,
        val manifest: PackManifest,
        val command: String,
        val componentId: String,
        val argJson: String,
        val caller: PackCaller,
        val requestId: String,
        val timeoutMs: Long,
        val deadlineNanos: Long,
        val cancellation: CancellationSignal,
        val worker: PackWorker,
        val startNanos: Long,
    )

    private sealed interface Admission {
        data class Ready(val plan: InvocationPlan) : Admission
        data class Rejected(val requestId: String, val diagnostic: PackDiagnostic) : Admission
    }

    private class ActiveCall(
        val packId: PackId,
        val ref: PackRef,
        val cancellation: CancellationSignal,
    )

    private fun executePlanBlocking(plan: InvocationPlan): PackResult {
        val job: PackWorker.JobHandle<TaskOutcome> = try {
            plan.worker.submit { runPlan(plan) }
        } catch (e: RejectedExecutionException) {
            synchronized(stateLock) { activeCalls.remove(plan.requestId) }
            return PackResult.failure(
                plan.requestId,
                PackDiagnostic(
                    code = PackErrorCode.INVALID_REQUEST,
                    message = "pack worker queue is full or closed",
                ),
                elapsed(plan.startNanos),
            )
        }
        try {
            val remaining = plan.deadlineNanos - System.nanoTime()
            val outcome = try {
                job.get(remaining.coerceAtLeast(0L), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                return handleTimeout(plan, job)
            } catch (e: InterruptedException) {
                plan.cancellation.cancel("caller interrupted")
                job.interruptWorker()
                return PackResult.failure(
                    plan.requestId,
                    PackDiagnostic(
                        code = PackErrorCode.CANCELLED,
                        message = "pack call was interrupted",
                    ),
                    elapsed(plan.startNanos),
                )
            } catch (e: CancellationException) {
                return PackResult.failure(
                    plan.requestId,
                    PackDiagnostic(
                        code = PackErrorCode.CANCELLED,
                        message = "pack call future was cancelled",
                    ),
                    elapsed(plan.startNanos),
                )
            } catch (e: ExecutionException) {
                val diagnostic = PackDiagnostic(
                    code = PackErrorCode.INTERNAL,
                    message = "pack worker failed: ${e.cause?.javaClass?.simpleName ?: "unknown"}",
                )
                recordExecutionDiagnostic(plan, diagnostic)
                return PackResult.failure(plan.requestId, diagnostic, elapsed(plan.startNanos))
            }
            return deliver(plan, outcome)
        } finally {
            synchronized(stateLock) { activeCalls.remove(plan.requestId) }
        }
    }

    private fun handleTimeout(plan: InvocationPlan, job: PackWorker.JobHandle<TaskOutcome>): PackResult {
        plan.cancellation.cancel("timed out after ${plan.timeoutMs} ms")
        val elapsedMs = elapsed(plan.startNanos)
        trackerAddElapsed(plan, elapsedMs)
        if (job.cancelIfQueued()) {
            val diagnostic = PackDiagnostic(
                code = PackErrorCode.TIMEOUT,
                message = "pack call timed out after ${plan.timeoutMs} ms before it started",
                details = mapOf("requestId" to plan.requestId),
            )
            return PackResult.failure(plan.requestId, diagnostic, elapsedMs)
        }
        job.interruptWorker()
        val stopped = job.awaitStopped(executionPolicy.closeTimeoutMs)
        return if (stopped) {
            val diagnostic = PackDiagnostic(
                code = PackErrorCode.TIMEOUT,
                message = "pack call timed out after ${plan.timeoutMs} ms",
                details = mapOf("requestId" to plan.requestId),
            )
            applyFailureSideEffects(plan, diagnostic)
            PackResult.failure(plan.requestId, diagnostic, elapsedMs)
        } else {
            val diagnostic = PackDiagnostic(
                code = PackErrorCode.NEEDS_RESTART,
                message = "pack call timed out and did not stop within " +
                    "${executionPolicy.closeTimeoutMs} ms; restart required",
                details = mapOf("requestId" to plan.requestId),
            )
            markNeedsRestart(plan.packId, plan.ref, diagnostic)
            PackResult.failure(plan.requestId, diagnostic, elapsedMs)
        }
    }

    private fun deliver(plan: InvocationPlan, outcome: TaskOutcome): PackResult {
        val elapsedMs = elapsed(plan.startNanos)
        trackerAddElapsed(plan, elapsedMs)
        return when (outcome) {
            is TaskOutcome.Failure -> {
                recordExecutionDiagnostic(plan, outcome.diagnostic)
                applyFailureSideEffects(plan, outcome.diagnostic)
                PackResult.failure(plan.requestId, outcome.diagnostic, elapsedMs)
            }

            is TaskOutcome.Success -> {
                plan.cancellation.reason?.let { reason ->
                    val diagnostic = PackDiagnostic(
                        code = PackErrorCode.CANCELLED,
                        message = "pack call was cancelled: $reason",
                        details = mapOf("requestId" to plan.requestId),
                    )
                    return PackResult.failure(plan.requestId, diagnostic, elapsedMs)
                }
                if (System.nanoTime() - plan.deadlineNanos >= 0L) {
                    val diagnostic = PackDiagnostic(
                        code = PackErrorCode.TIMEOUT,
                        message = "pack result arrived after its deadline",
                        details = mapOf("requestId" to plan.requestId),
                    )
                    applyFailureSideEffects(plan, diagnostic)
                    return PackResult.failure(plan.requestId, diagnostic, elapsedMs)
                }
                val deliveryFailure = synchronized(stateLock) {
                    val record = records[plan.packId]
                    when {
                        record == null || record.ref != plan.ref -> PackDiagnostic(
                            code = PackErrorCode.CANCELLED,
                            message = "pack revision changed before result delivery",
                            details = mapOf("requestId" to plan.requestId),
                        )

                        !record.stored.enabled -> PackDiagnostic(
                            code = PackErrorCode.CANCELLED,
                            message = "pack was disabled before result delivery",
                            details = mapOf("requestId" to plan.requestId),
                        )

                        record.runtimeNeedsRestart -> PackDiagnostic(
                            code = PackErrorCode.NEEDS_RESTART,
                            message = "pack was quarantined before result delivery",
                            details = mapOf("requestId" to plan.requestId),
                        )

                        record.transition -> PackDiagnostic(
                            code = PackErrorCode.CANCELLED,
                            message = "pack changed revision before result delivery",
                            details = mapOf("requestId" to plan.requestId),
                        )

                        else -> null
                    }
                }
                if (deliveryFailure != null) {
                    return PackResult.failure(plan.requestId, deliveryFailure, elapsedMs)
                }
                markHealthy(plan.packId, plan.ref)
                PackResult.ok(plan.requestId, outcome.dataJson, elapsedMs)
            }
        }
    }

    private fun runPlan(plan: InvocationPlan): TaskOutcome {
        return try {
            val call = PackCallContext(
                requestId = plan.requestId,
                packRef = plan.ref,
                caller = plan.caller,
                deadlineNanos = plan.deadlineNanos,
                cancellation = plan.cancellation,
            )
            call.checkActive()
            val record = synchronized(stateLock) { records[plan.packId] }
                ?: return TaskOutcome.Failure(cancelledDiagnostic("pack was uninstalled"))
            if (record.ref != plan.ref || !record.stored.enabled || record.transition) {
                return TaskOutcome.Failure(cancelledDiagnostic("pack revision is no longer active"))
            }
            val component = plan.manifest.componentById(plan.componentId)
                ?: return TaskOutcome.Failure(
                    PackDiagnostic(
                        code = PackErrorCode.COMPONENT_MISSING,
                        message = "component '${plan.componentId}' is not declared",
                        path = "components/${plan.componentId}",
                    ),
                )
            if (!plan.worker.isWorkerThread()) {
                return TaskOutcome.Failure(
                    PackDiagnostic(
                        code = PackErrorCode.INTERNAL,
                        message = "pack code attempted to run outside its worker",
                    ),
                )
            }
            val instance = getOrLoad(record, plan.manifest, plan.ref, component, call)
            call.checkActive()
            val result = instance.invoke(plan.command, plan.argJson, call)
            call.checkActive()
            if (result.length > PackJsProtocol.MAX_JSON_CHARS) {
                return TaskOutcome.Failure(
                    PackDiagnostic(
                        code = PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                        message = "pack result exceeds ${PackJsProtocol.MAX_JSON_CHARS} characters",
                    ),
                )
            }
            TaskOutcome.Success(result)
        } catch (e: PackException) {
            TaskOutcome.Failure(e.diagnostic)
        } catch (e: PackCancelledException) {
            TaskOutcome.Failure(e.diagnostic)
        } catch (e: PackTimeoutException) {
            TaskOutcome.Failure(e.diagnostic)
        } catch (e: CancellationException) {
            TaskOutcome.Failure(cancelledDiagnostic(e.message ?: "cancelled"))
        } catch (t: Throwable) {
            TaskOutcome.Failure(
                PackDiagnostic(
                    code = PackErrorCode.INTERNAL,
                    message = "pack component failed: ${t.javaClass.simpleName}",
                    details = mapOf("requestId" to plan.requestId),
                ),
            )
        }
    }

    private fun getOrLoad(
        record: PackRecord,
        manifest: PackManifest,
        ref: PackRef,
        component: ComponentSpec,
        call: PackCallContext,
    ): PackInstance {
        if (!isRefActive(ref)) throw PackCancelledException("pack revision is no longer active")
        val scope = scopeFor(record, manifest, ref)
        synchronized(stateLock) {
            scope.instances[component.id]?.let { return it }
        }
        val provider = environment.runtimeProvider(component.kind)
            ?: throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.COMPONENT_MISSING,
                    message = "no runtime provider is installed for ${component.kind.wire} components",
                    path = "components/${component.id}",
                    details = mapOf("kind" to component.kind.wire),
                ),
            )
        val availability = try {
            provider.probe(component, environment.platform)
        } catch (e: Exception) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.LOAD_FAILED,
                    message = "runtime probe failed: ${e.javaClass.simpleName}",
                    path = "components/${component.id}",
                ),
                e,
            )
        }
        if (availability is RuntimeAvailability.Unavailable) {
            throw PackException(availability.failure)
        }
        componentPath(component)?.let { relative ->
            val file = store.componentFile(record.id, ref.revision, relative)
            if (!file.isFile) {
                throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.COMPONENT_MISSING,
                        message = "component '${component.id}' file '${relative.value}' is missing",
                        path = relative.value,
                        details = mapOf("component" to component.id),
                    ),
                )
            }
        }
        call.checkActive()
        val instance = try {
            provider.load(ref, component, scope.primitives, call)
        } catch (e: PackException) {
            // A load that failed after mapping trusted code (native dlopen
            // succeeded, a later step failed) leaves no closable instance, so
            // closeRuntimeScope would otherwise report a clean release. Mark
            // the pack NEEDS_RESTART durably; disable/uninstall fold this
            // mark into their residency reporting. The record mark alone is
            // not enough while this load races a lifecycle transition: a
            // disable that already advanced the epoch or an uninstall that
            // already removed the record rejects the mark, so the
            // manager-scoped witness (which survives both) is recorded first
            // and aggregated once disable/uninstall drained this worker.
            if (e.diagnostic.isResidentLoadFailure()) {
                val diagnostic = PackDiagnostic(
                    code = PackErrorCode.NEEDS_RESTART,
                    message = "component '${component.id}' failed to load but its code remains mapped in this process; restart required",
                    path = "components/${component.id}",
                )
                recordResidentLoadFailure(record.id, diagnostic)
                markNeedsRestart(record.id, ref, diagnostic)
            }
            throw e
        } catch (e: Exception) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.LOAD_FAILED,
                    message = "runtime provider failed to load '${component.id}': ${e.javaClass.simpleName}",
                    path = "components/${component.id}",
                ),
                e,
            )
        }
        if (instance.ref != ref || instance.componentId != component.id) {
            runCatching { instance.close() }
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.LOAD_FAILED,
                    message = "runtime returned an instance for another pack revision or component",
                    path = "components/${component.id}",
                ),
            )
        }
        synchronized(stateLock) {
            if (!isRefActiveLocked(ref)) {
                runCatching { instance.close() }
                throw PackCancelledException("pack revision is no longer active")
            }
            scope.instances[component.id] = instance
        }
        return instance
    }

    private fun scopeFor(
        record: PackRecord,
        manifest: PackManifest,
        ref: PackRef,
    ): PackRuntimeScope {
        val existing = synchronized(stateLock) {
            if (!isRefActiveLocked(ref)) throw PackCancelledException("pack revision is no longer active")
            record.scope?.takeIf { it.ref == ref }
        }
        if (existing != null) return existing
        val grant = synchronized(stateLock) { record.stored.grant }
        val tracker = synchronized(stateLock) { record.metrics }
        val guard = PackEpochGuard { isRefActive(ref) }
        val storage = io.legado.pack.storage.PackStorageImpl(layout, record.id, ref.revision, guard)
        val kv = PackKeyValueStoreImpl(layout, record.id, limits, guard)
        val http = PackHttpClientImpl(
            policy = manifest.network,
            openNetworkApproved = grant.networkOpenApproved,
            cookieFile = File(layout.cookiesDir(record.id), "cookies.json"),
            limits = limits,
            guard = guard,
            tracker = tracker,
            onDiagnostic = { diagnostic -> diagnosticsStore.recordDiagnostic(ref, null, diagnostic) },
        )
        val crypto = PackCryptoImpl(guard)
        val logger: PackLogger = PackLoggerImpl(logStore, record.id, ref, guard, tracker)
        val worker = synchronized(stateLock) { record.worker }
        if (worker == null) {
            http.close()
            throw PackCancelledException("pack worker is unavailable")
        }
        val components = PackComponentInvokerImpl(ref, guard) { componentId, command, argJson, call ->
            invokeComponentOnWorker(record, manifest, ref, worker, componentId, command, argJson, call)
        }
        val primitives = PackPrimitivesContext(
            ref = ref,
            manifest = manifest,
            platform = environment.platform,
            storage = storage,
            kv = kv,
            http = http,
            crypto = crypto,
            logger = logger,
            components = components,
            limits = limits,
            httpClient = http,
        )
        val created = PackRuntimeScope(ref, primitives)
        val selected = synchronized(stateLock) {
            if (!isRefActiveLocked(ref)) {
                created.primitives.close()
                throw PackCancelledException("pack revision is no longer active")
            }
            val current = record.scope
            if (current != null && current.ref == ref) {
                current
            } else {
                record.scope = created
                created
            }
        }
        if (selected !== created) created.primitives.close()
        return selected
    }

    private fun invokeComponentOnWorker(
        record: PackRecord,
        manifest: PackManifest,
        ref: PackRef,
        worker: PackWorker,
        componentId: String,
        command: String,
        argJson: String,
        call: PackCallContext,
    ): String {
        if (!worker.isWorkerThread()) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.INTERNAL,
                    message = "component invocation must run on the pack worker",
                ),
            )
        }
        val depth = componentCallDepth.get() ?: 0
        if (depth >= MaxComponentCallDepth) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.INVALID_REQUEST,
                    message = "component call depth exceeds $MaxComponentCallDepth",
                ),
            )
        }
        componentCallDepth.set(depth + 1)
        try {
            call.checkActive()
            if (!isRefActive(ref)) throw PackCancelledException("pack revision is no longer active")
            val commandSpec = manifest.commandByName(command)
                ?: throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.COMMAND_NOT_FOUND,
                        message = "unknown component command",
                        path = "commands/$command",
                    ),
                )
            if (commandSpec.componentId != componentId) {
                throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.PERMISSION_DENIED,
                        message = "command '$command' belongs to component '${commandSpec.componentId}'",
                        path = "commands/$command",
                    ),
                )
            }
            val component = manifest.componentById(componentId)
                ?: throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.COMPONENT_MISSING,
                        message = "component '$componentId' is not declared",
                    ),
                )
            val instance = getOrLoad(record, manifest, ref, component, call)
            val result = instance.invoke(command, argJson, call)
            call.checkActive()
            if (result.length > PackJsProtocol.MAX_JSON_CHARS) {
                throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                        message = "component result exceeds ${PackJsProtocol.MAX_JSON_CHARS} characters",
                    ),
                )
            }
            return result
        } finally {
            if (depth == 0) componentCallDepth.remove() else componentCallDepth.set(depth)
        }
    }

    private fun componentPath(component: ComponentSpec): RelativePath? = when (component) {
        is ComponentSpec.Js -> component.entry
        is ComponentSpec.Dex -> component.path
        is ComponentSpec.Native -> component.path
    }

    // ------------------------------------------------------------------
    // PackManager: self-test
    // ------------------------------------------------------------------

    override suspend fun selfTest(id: PackId): SelfTestReport = withContext(Dispatchers.IO) {
        selfTestBlocking(id)
    }

    private fun selfTestBlocking(id: PackId): SelfTestReport {
        ensureOpen()
        val startedAt = System.currentTimeMillis()
        val runId = newRunId()
        val snapshot = synchronized(stateLock) {
            records[id]?.let { Triple(it.ref, it.manifest, it.state()) }
        } ?: throw PackException(
            PackDiagnostic(
                code = PackErrorCode.PACK_NOT_FOUND,
                message = "pack '${id.value}' is not installed",
            ),
        )
        val ref = snapshot.first
        val manifest = snapshot.second
        val state = snapshot.third
        val notes = trustNotes(manifest)
        val before = synchronized(stateLock) { records[id]?.metrics }?.snapshot(notes) ?: PackMetrics(notes = notes)
        val cases = ArrayList<SelfTestCaseResult>()
        val diagnostics = ArrayList<PackDiagnostic>()
        if (state == PackState.DISABLED || state == PackState.NEEDS_RESTART) {
            cases += SelfTestCaseResult(
                name = "pack-state",
                status = SelfTestStatus.SKIP,
                message = "pack is $state",
            )
            diagnostics += if (state == PackState.DISABLED) {
                PackDiagnostic(PackErrorCode.PACK_DISABLED, "pack is disabled")
            } else {
                PackDiagnostic(PackErrorCode.NEEDS_RESTART, "pack requires a process restart")
            }
        } else if (manifest.selfTests.isEmpty()) {
            cases += SelfTestCaseResult(
                name = "no-declared-self-tests",
                status = SelfTestStatus.SKIP,
                message = "manifest declares no self-tests",
            )
            diagnostics += PackDiagnostic(
                code = PackErrorCode.INVALID_REQUEST,
                message = "manifest declares no self-tests",
                path = "selfTests",
            )
        } else {
            manifest.selfTests.forEach { spec ->
                val admission = admit(
                    id = id,
                    expectedRef = ref,
                    entry = null,
                    directCommand = spec.command,
                    argJson = "",
                    caller = PackCaller.SelfTest,
                    timeoutMs = spec.timeoutMs,
                    cancellation = CancellationSignal(),
                    allowSelfTestDirect = true,
                )
                when (admission) {
                    is Admission.Rejected -> {
                        cases += SelfTestCaseResult(
                            name = spec.name,
                            status = if (admission.diagnostic.code == PackErrorCode.NEEDS_RESTART) {
                                SelfTestStatus.ERROR
                            } else {
                                SelfTestStatus.SKIP
                            },
                            message = admission.diagnostic.message,
                        )
                        diagnostics += admission.diagnostic
                        if (admission.diagnostic.code == PackErrorCode.NEEDS_RESTART ||
                            admission.diagnostic.code == PackErrorCode.PACK_DISABLED
                        ) {
                            return@forEach
                        }
                    }

                    is Admission.Ready -> {
                        val result = executePlanBlocking(admission.plan)
                        val status = when (result.status) {
                            PackCallStatus.OK -> SelfTestStatus.PASS
                            PackCallStatus.TIMEOUT -> SelfTestStatus.TIMEOUT
                            PackCallStatus.CANCELLED -> SelfTestStatus.FAIL
                            PackCallStatus.NEEDS_RESTART -> SelfTestStatus.ERROR
                            PackCallStatus.ERROR -> SelfTestStatus.ERROR
                        }
                        cases += SelfTestCaseResult(
                            name = spec.name,
                            status = status,
                            message = result.message,
                            dataJson = result.dataJson,
                            elapsedMs = result.elapsedMs,
                        )
                        result.error?.let {
                            diagnostics += it
                        }
                    }
                }
            }
        }
        val finishedAt = System.currentTimeMillis()
        val after = synchronized(stateLock) { records[id]?.metrics }?.snapshot(notes) ?: PackMetrics(notes = notes)
        var report = SelfTestReport(
            runId = runId,
            packRef = ref,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = maxOf(startedAt, finishedAt),
            cases = cases,
            metrics = PackMetricsTracker.delta(before, after),
            diagnostics = diagnostics,
        )
        val persistence = mutationLock.withLock {
            val stillCurrent = synchronized(stateLock) { records[id]?.ref == ref }
            if (!stillCurrent) {
                PackDiagnostic(
                    code = PackErrorCode.CANCELLED,
                    message = "self-test report was not persisted because the pack changed or was removed",
                )
            } else {
                selfTestStore.save(report)
            }
        }
        if (persistence != null) {
            report = report.copy(diagnostics = report.diagnostics + persistence)
        }
        val red = report.redCards
        if (red.isNotEmpty()) {
            markBroken(id, ref, red.first())
        } else if (report.cases.any { it.status == SelfTestStatus.PASS || it.status == SelfTestStatus.SKIP }) {
            markHealthy(id, ref)
        }
        return report
    }

    override fun selfTestReport(id: PackId, runId: String?): SelfTestReport? {
        val exists = synchronized(stateLock) { records.containsKey(id) }
        if (!exists) return null
        return selfTestStore.load(id, runId)
    }

    // ------------------------------------------------------------------
    // PackManager: read-only state
    // ------------------------------------------------------------------

    override fun info(id: PackId): PackInfo? = synchronized(stateLock) { records[id]?.toInfo() }

    override fun list(): List<PackInfo> = synchronized(stateLock) {
        records.values.map { it.toInfo() }.sortedBy { it.ref.id.value }
    }

    // ------------------------------------------------------------------
    // Manager-local diagnostics snapshots (read-only)
    // ------------------------------------------------------------------

    fun logEvents(id: PackId, limit: Int = 200): List<PackLogEntry> = logStore.snapshot(id, limit)

    fun diagnosticEvents(id: PackId? = null, limit: Int = 200): List<DiagnosticEvent> =
        diagnosticsStore.snapshot(id, limit)

    fun selfTestReports(id: PackId, limit: Int = 20): List<SelfTestReport> {
        val exists = synchronized(stateLock) { records.containsKey(id) }
        if (!exists) return emptyList()
        return selfTestStore.recent(id, limit)
    }

    fun logBytes(id: PackId): Long = logStore.diskBytes(id)

    fun metrics(id: PackId): PackMetrics = synchronized(stateLock) {
        records[id]?.let { record ->
            record.metrics.snapshot(trustNotes(record.manifest))
        } ?: PackMetrics()
    }

    // ------------------------------------------------------------------
    // Close
    // ------------------------------------------------------------------

    override fun close() {
        mutationLock.withLock {
            if (closed) return
            closed = true
            // Uncommitted staging is discarded now (same discard path as
            // prepareImport eviction) instead of waiting for the next
            // construction's scanAndRecover to reclaim it.
            val pendingPreviews = synchronized(stateLock) {
                val pending = previews.values.toList()
                previews.clear()
                pending
            }
            pendingPreviews.forEach { prepared ->
                runCatching { importer.discard(prepared) }.onFailure { error ->
                    diagnosticsStore.record(
                        null,
                        null,
                        PackErrorCode.IO_FAILED,
                        "cannot discard pending import staging during close: ${error.message}",
                        path = "staging/${prepared.preview.token}",
                    )
                }
            }
            val snapshot = synchronized(stateLock) { records.values.toList() }
            snapshot.forEach { cancelActiveCalls(it.id, null) }
            snapshot.forEach { record ->
                val worker = synchronized(stateLock) { record.worker }
                if (worker != null) {
                    val drained = worker.awaitIdle(executionPolicy.closeTimeoutMs)
                    if (drained) {
                        closeRuntimeScope(record.id, synchronized(stateLock) { record.scope }, worker)
                    }
                    worker.shutdownBounded(executionPolicy.closeTimeoutMs)
                }
            }
            logStore.close()
        }
    }

    // ------------------------------------------------------------------
    // Recovery / registration
    // ------------------------------------------------------------------

    private fun recoverFromDisk() {
        val recovery = try {
            store.scanAndRecover()
        } catch (e: PackException) {
            throw e
        } catch (e: Exception) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.IO_FAILED,
                    message = "cannot scan the packs directory: ${e.message}",
                ),
                e,
            )
        }
        recovery.diagnostics.forEach { diagnostic ->
            diagnosticsStore.record(null, null, diagnostic.code, diagnostic.message, diagnostic.path, diagnostic.details)
        }
        recovery.packs.forEach { stored ->
            var fixed = stored
            var runtimeBroken = stored.broken
            // Migration for installations that predate the durable ledger (no
            // .epochs.json on disk): raise the high-water mark to the stored
            // epoch before the pack can be uninstalled, so a reinstall of the
            // same content cannot fall back onto an epoch this installation
            // already used for host-side grant binding. A failed migration
            // fails closed (constructor) instead of restarting the numbering.
            installLedger.reserve(fixed.id, fixed.epoch)
            if (!stored.grantMatchesPermissions ||
                (stored.enabled && !stored.grant.approves(stored.manifest.permissions))
            ) {
                fixed = fixed.copy(enabled = false)
                runtimeBroken = runtimeBroken ?: PackDiagnostic(
                    code = PackErrorCode.PERMISSION_DENIED,
                    message = "stored grant no longer matches the manifest",
                    path = "grant",
                )
            }
            val record = PackRecord(
                stored = fixed,
                runtimeNeedsRestart = false,
                runtimeBroken = runtimeBroken,
            )
            records[fixed.id] = record
            if (fixed != stored) {
                persistRecord(record)
            }
        }
        publishInitialRegistrations()
    }

    private fun publishInitialRegistrations() {
        val candidates = currentRegistrations()
        val full = replaceRegistrations(candidates)
        if (full is RegistrationResult.Applied) {
            recordSinkFailures(full)
            return
        }
        val rejected = full as RegistrationResult.Rejected
        rejected.diagnostics.forEach { diagnostic ->
            diagnosticsStore.record(null, null, diagnostic.code, diagnostic.message, diagnostic.path, diagnostic.details)
        }
        // One conflicting pack must not prevent every other pack from being
        // registered; fall back to incremental acceptance.
        replaceRegistrations(emptyList())
        val accepted = ArrayList<PackRegistration>()
        candidates.forEach { candidate ->
            val attempt = replaceRegistrations(accepted + candidate)
            if (attempt is RegistrationResult.Applied) {
                accepted += candidate
            } else {
                (attempt as RegistrationResult.Rejected).diagnostics.forEach { diagnostic ->
                    diagnosticsStore.record(
                        candidate.ref,
                        null,
                        diagnostic.code,
                        diagnostic.message,
                        diagnostic.path,
                        diagnostic.details,
                    )
                }
            }
        }
    }

    private fun currentRegistrations(
        extra: StoredPack? = null,
        excludeId: PackId? = null,
    ): List<PackRegistration> {
        val result = ArrayList<PackRegistration>()
        synchronized(stateLock) {
            records.values.forEach { record ->
                if (record.id == excludeId) return@forEach
                val stored = record.stored
                if (stored.enabled) {
                    result += PackRegistration(stored.ref, stored.manifest)
                }
            }
        }
        if (extra != null && extra.enabled) {
            result.removeAll { it.ref.id == extra.id }
            result += PackRegistration(extra.ref, extra.manifest)
        }
        return result.sortedBy { it.ref.id.value }
    }

    private fun replaceRegistrations(candidates: List<PackRegistration>): RegistrationResult {
        return try {
            registry.replaceAll(candidates)
        } catch (e: Exception) {
            RegistrationResult.Rejected(
                listOf(
                    PackDiagnostic(
                        code = PackErrorCode.INTERNAL,
                        message = "registration update failed: ${e.javaClass.simpleName}",
                    ),
                ),
                runCatching { registry.snapshot() }
                    .getOrElse { io.legado.pack.registry.PackRegistrationSnapshot(0L, emptyMap()) },
            )
        }
    }

    private fun recordSinkFailures(applied: RegistrationResult.Applied) {
        applied.sinkFailures.forEach { diagnostic ->
            diagnosticsStore.record(null, null, diagnostic.code, diagnostic.message, diagnostic.path, diagnostic.details)
        }
    }

    // ------------------------------------------------------------------
    // Runtime release
    // ------------------------------------------------------------------

    private data class CloseSummary(
        val released: Boolean,
        val stillResident: Boolean,
        val needsRestart: Boolean,
        val failure: PackDiagnostic?,
    ) {
        companion object {
            fun released(): CloseSummary = CloseSummary(true, false, false, null)

            fun resident(message: String): CloseSummary = CloseSummary(
                released = false,
                stillResident = true,
                needsRestart = true,
                failure = PackDiagnostic(PackErrorCode.NEEDS_RESTART, message),
            )
        }
    }

    private fun closeRuntimeScope(
        id: PackId,
        scope: PackRuntimeScope?,
        worker: PackWorker?,
    ): CloseSummary {
        if (scope == null) return CloseSummary.released()
        if (worker == null) return CloseSummary.resident("pack worker is unavailable")
        if (!worker.awaitIdle(executionPolicy.closeTimeoutMs)) {
            return CloseSummary.resident("pack calls did not stop within ${executionPolicy.closeTimeoutMs} ms")
        }
        val future = try {
            worker.submit { closeScopeBlocking(scope) }
        } catch (_: RejectedExecutionException) {
            return CloseSummary.resident("pack worker refused its close task")
        }
        return try {
            future.get(executionPolicy.closeTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.interruptWorker()
            CloseSummary.resident("component close did not finish within ${executionPolicy.closeTimeoutMs} ms")
        } catch (e: ExecutionException) {
            CloseSummary(
                released = false,
                stillResident = true,
                needsRestart = true,
                failure = PackDiagnostic(
                    code = PackErrorCode.LOAD_FAILED,
                    message = "component close failed: ${e.cause?.javaClass?.simpleName ?: "unknown"}",
                ),
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            CloseSummary.resident("interrupted while closing pack code")
        } catch (e: CancellationException) {
            CloseSummary.resident("pack close task was cancelled")
        }
    }

    private fun closeScopeBlocking(scope: PackRuntimeScope): CloseSummary {
        var released = true
        var resident = false
        var needsRestart = false
        var failure: PackDiagnostic? = null
        scope.instances.values.toList().forEach { instance ->
            try {
                val outcome = instance.close()
                if (!outcome.released) released = false
                if (outcome.stillResident) resident = true
                if (outcome.needsRestart) needsRestart = true
                if (outcome.failure != null && failure == null) failure = outcome.failure
            } catch (e: Exception) {
                released = false
                resident = true
                needsRestart = true
                if (failure == null) {
                    failure = PackDiagnostic(
                        code = PackErrorCode.LOAD_FAILED,
                        message = "component close threw ${e.javaClass.simpleName}",
                    )
                }
            }
        }
        scope.instances.clear()
        try {
            scope.primitives.close()
        } catch (e: Exception) {
            if (failure == null) {
                failure = PackDiagnostic(
                    code = PackErrorCode.LOAD_FAILED,
                    message = "primitive close failed: ${e.javaClass.simpleName}",
                )
            }
        }
        return if (released && !resident && !needsRestart) {
            CloseSummary.released()
        } else {
            CloseSummary(
                released = false,
                stillResident = true,
                needsRestart = true,
                failure = failure ?: PackDiagnostic(
                    code = PackErrorCode.NEEDS_RESTART,
                    message = "pack code did not report a clean release",
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // Epoch / persistence helpers
    // ------------------------------------------------------------------

    private fun isRefActive(ref: PackRef): Boolean = synchronized(stateLock) { isRefActiveLocked(ref) }

    private fun isRefActiveLocked(ref: PackRef): Boolean {
        if (closed) return false
        val record = records[ref.id] ?: return false
        return record.ref == ref &&
            record.stored.enabled &&
            !record.transition &&
            !record.runtimeNeedsRestart
    }

    private fun isOnOwnWorker(id: PackId): Boolean {
        val worker = synchronized(stateLock) { records[id]?.worker } ?: return false
        return worker.isWorkerThread()
    }

    private fun cancelActiveCalls(id: PackId, ref: PackRef?) {
        val calls = synchronized(stateLock) {
            activeCalls.values.filter { call ->
                call.packId == id && (ref == null || call.ref == ref)
            }
        }
        calls.forEach { it.cancellation.cancel("pack state changed") }
    }

    private fun trackerAddElapsed(plan: InvocationPlan, elapsedMs: Long) {
        synchronized(stateLock) {
            records[plan.packId]?.takeIf { it.ref == plan.ref }?.metrics?.addElapsed(elapsedMs)
        }
    }

    private fun applyFailureSideEffects(plan: InvocationPlan, diagnostic: PackDiagnostic) {
        if (!diagnostic.isRedCard) return
        val fatal = diagnostic.code == PackErrorCode.COMPONENT_MISSING ||
            diagnostic.code == PackErrorCode.LOAD_FAILED ||
            diagnostic.code == PackErrorCode.NATIVE_PLATFORM ||
            diagnostic.code == PackErrorCode.TIMEOUT ||
            plan.caller is PackCaller.SelfTest
        if (fatal) {
            markBroken(plan.packId, plan.ref, diagnostic)
        }
    }

    private fun recordExecutionDiagnostic(plan: InvocationPlan, diagnostic: PackDiagnostic) {
        if (diagnostic.severity == io.legado.pack.api.PackSeverity.INFO) return
        diagnosticsStore.recordDiagnostic(plan.ref, plan.requestId, diagnostic)
    }

    private fun markBroken(id: PackId, ref: PackRef, diagnostic: PackDiagnostic) {
        val captured = synchronized(stateLock) {
            val record = records[id]?.takeIf { it.ref == ref } ?: return
            if (record.runtimeBroken == diagnostic) return
            record.runtimeBroken = diagnostic
            record.persisted() to record.persistSeq
        }
        persistMetadata(captured.first, captured.second)
        diagnosticsStore.recordDiagnostic(ref, null, diagnostic)
    }

    private fun markHealthy(id: PackId, ref: PackRef) {
        val captured = synchronized(stateLock) {
            val record = records[id]?.takeIf { it.ref == ref } ?: return
            if (record.runtimeBroken == null) return
            record.runtimeBroken = null
            record.persisted() to record.persistSeq
        }
        persistMetadata(captured.first, captured.second)
    }

    private fun markNeedsRestart(id: PackId, ref: PackRef, diagnostic: PackDiagnostic) {
        val captured = synchronized(stateLock) {
            val record = records[id]?.takeIf { it.ref == ref } ?: return
            record.runtimeNeedsRestart = true
            record.persisted() to record.persistSeq
        }
        persistMetadata(captured.first, captured.second)
        diagnosticsStore.recordDiagnostic(ref, null, diagnostic)
    }

    /**
     * Records the manager-scoped witness for a load failure whose trusted
     * code stayed mapped in this process. Unlike [markNeedsRestart] this
     * write is unconditional: it must succeed while a concurrent disable has
     * already advanced the admission epoch or a concurrent uninstall has
     * already removed the record — exactly the interleavings in which the
     * record-scoped mark is rejected.
     */
    private fun recordResidentLoadFailure(id: PackId, diagnostic: PackDiagnostic) {
        synchronized(stateLock) { residentLoadFailures[id] = diagnostic }
    }

    /**
     * Non-destructive read of the resident-load witness; disable is a
     * transient transition, so the entry stays available to the eventual
     * uninstall (and to a disable retry) until the record is really gone.
     */
    private fun peekResidentLoadFailure(id: PackId): PackDiagnostic? =
        synchronized(stateLock) { residentLoadFailures[id] }

    /**
     * Reads and clears the resident-load witness. Uninstall is the terminal
     * aggregation point: the restart it demands is the only real cleanup for
     * a pinned mapping, and consuming the witness here keeps a reinstall of
     * the same id from inheriting the previous installation's failure report
     * (a fresh load of the same path re-reports through the runtime anyway).
     */
    private fun takeResidentLoadFailure(id: PackId): PackDiagnostic? =
        synchronized(stateLock) { residentLoadFailures.remove(id) }

    private fun persistRecord(record: PackRecord): Boolean {
        val captured = synchronized(stateLock) { record.persisted() to record.persistSeq }
        return persistMetadata(captured.first, captured.second)
    }

    /**
     * Writes one record snapshot to `current.json` under the persist lock.
     *
     * Visible for the persistence-ordering white-box test only; production
     * callers go through [persistRecord] / the mark* helpers.
     *
     * Ordering guard: deliver-side markBroken/markHealthy run on their
     * callers' coroutines, so two snapshots of the same epoch can reach this
     * lock in reverse snapshot order (the older one persisted last). A
     * generation older than the live record — or an epoch below the live
     * epoch, which covers snapshots taken before a commit replacement — must
     * not reach the file; its state is superseded by the newer snapshot
     * derived from the same mutable record, so skipping it can only leave the
     * file at a state that the newer snapshot already wrote or will write.
     * A skipped write is not a failure: the lifecycle flows that roll back on
     * `false` must not revert an in-memory state that a newer snapshot has
     * already persisted. A record that no longer exists (uninstalled) also
     * skips: writing would resurrect metadata for a deleted pack.
     */
    internal fun persistMetadata(stored: StoredPack, seq: Long): Boolean {
        synchronized(persistLock) {
            val live = synchronized(stateLock) { records[stored.id] }
            if (live == null ||
                live.persistSeq > seq ||
                stored.epoch < live.stored.epoch
            ) {
                return true
            }
            try {
                store.writeMetadata(stored)
            } catch (e: Exception) {
                diagnosticsStore.record(
                    stored.ref,
                    null,
                    PackErrorCode.IO_FAILED,
                    "cannot persist pack metadata: ${e.message}",
                    path = "current.json",
                )
                return false
            }
        }
        return true
    }

    private fun ensureOpen() {
        if (closed) {
            throw PackException(PackDiagnostic(PackErrorCode.INTERNAL, "pack manager is closed"))
        }
    }

    private fun elapsed(startNanos: Long): Long =
        ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)

    private fun newRequestId(): String = "req-" + randomHex(12)

    private fun newRunId(): String =
        System.currentTimeMillis().toString(16).padStart(16, '0') + "-" + randomHex(8)

    private fun randomHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return PackHex.encode(buffer)
    }

    private fun trustNotes(manifest: PackManifest): List<String> {
        val notes = ArrayList<String>()
        if (manifest.components.any { it.kind != ComponentKind.JS }) {
            notes += "DEX/native components are trusted in-process code; metrics only cover framework primitives"
        }
        return notes
    }

    private fun cancelledDiagnostic(reason: String): PackDiagnostic =
        PackDiagnostic(PackErrorCode.CANCELLED, "pack call cancelled: $reason")

    private fun commandNotFound(name: String): PackDiagnostic =
        PackDiagnostic(
            code = PackErrorCode.COMMAND_NOT_FOUND,
            message = "pack command '$name' is not declared",
            path = "commands/$name",
        )

    private fun permissionDenied(message: String): PackDiagnostic =
        PackDiagnostic(code = PackErrorCode.PERMISSION_DENIED, message = message)

    private fun failure(
        requestId: String,
        code: PackErrorCode,
        message: String,
        startNanos: Long,
    ): PackResult = PackResult.failure(
        requestId,
        PackDiagnostic(code = code, message = message),
        elapsed(startNanos),
    )

    private companion object {
        const val MaxPendingPreviews = 16
        const val PendingPreviewTtlMs = 60L * 60 * 1_000
        const val MaxComponentCallDepth = 16
        val PACK_NOT_FOUND = PackErrorCode.PACK_NOT_FOUND
        val NEEDS_RESTART = PackErrorCode.NEEDS_RESTART
        val INVALID_REQUEST = PackErrorCode.INVALID_REQUEST
        val IO_FAILED = PackErrorCode.IO_FAILED
        val COMPONENT_MISSING = PackErrorCode.COMPONENT_MISSING
        val PERMISSION_DENIED = PackErrorCode.PERMISSION_DENIED
        val NETWORK_OPEN_REQUIRES_CONSENT = PackErrorCode.NETWORK_OPEN_REQUIRES_CONSENT
    }
}

private sealed interface TaskOutcome {
    data class Success(val dataJson: String) : TaskOutcome
    data class Failure(val diagnostic: PackDiagnostic) : TaskOutcome
}