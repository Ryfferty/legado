package io.legado.app.help.pack

import io.legado.pack.api.CancellationSignal
import io.legado.pack.api.ImportPreview
import io.legado.pack.api.PackCaller
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackGrant
import io.legado.pack.api.PackId
import io.legado.pack.api.PackImportHint
import io.legado.pack.api.PackInfo
import io.legado.pack.api.PackInvocation
import io.legado.pack.api.PackManager
import io.legado.pack.api.PackResult
import io.legado.pack.api.PackState
import io.legado.pack.api.SelfTestReport
import io.legado.pack.api.UninstallReport
import java.io.InputStream

/**
 * Test double that mirrors the manager's epoch admission rules: an invocation
 * carrying an old [io.legado.pack.api.PackRef] is rejected instead of being
 * redirected to the current revision.
 */
class FakePackManager(initial: List<PackInfo> = emptyList()) : PackManager {

    val infos = LinkedHashMap<PackId, PackInfo>()
    val reports = LinkedHashMap<String, SelfTestReport>()
    val latestReports = LinkedHashMap<PackId, SelfTestReport>()

    var selfTestCount: Int = 0
        private set
    var lastInvocation: PackInvocation? = null
        private set
    var lastBlockingInvocation: PackInvocation? = null
        private set

    var resultFactory: (PackInvocation) -> PackResult = { invocation ->
        PackResult.ok(invocation.expectedRef.id.value, "{\"ok\":true}", 1L)
    }

    init {
        initial.forEach { info ->
            infos[info.ref.id] = info
        }
    }

    fun add(info: PackInfo) {
        infos[info.ref.id] = info
    }

    fun putReport(report: SelfTestReport) {
        reports[report.packRef.id.value + ":" + report.runId] = report
        latestReports[report.packRef.id] = report
    }

    override suspend fun prepareImport(
        input: InputStream,
        hint: PackImportHint,
    ): ImportPreview = throw UnsupportedOperationException("not used in this test")

    override suspend fun commitImport(
        previewToken: String,
        grant: PackGrant,
    ): PackInfo = throw UnsupportedOperationException("not used in this test")

    override suspend fun setEnabled(id: PackId, enabled: Boolean): PackResult {
        val current = infos[id] ?: return failure(PackErrorCode.PACK_NOT_FOUND, "missing pack")
        infos[id] = current.copy(
            state = if (enabled) PackState.ENABLED else PackState.DISABLED,
        )
        return PackResult.ok("set-enabled", null, 0L)
    }

    override suspend fun uninstall(id: PackId): UninstallReport {
        val existed = infos.remove(id) != null
        reports.keys.removeAll { it.startsWith(id.value + ":") }
        latestReports.remove(id)
        return UninstallReport(
            packId = id,
            registrationRevoked = existed,
            residualPaths = emptyList(),
            loadedCodeStillResident = false,
            needsRestart = false,
        )
    }

    override suspend fun invoke(
        id: PackId,
        command: String,
        argJson: String,
        caller: PackCaller,
        timeoutMs: Long?,
        cancellation: CancellationSignal,
    ): PackResult = invokeBlocking(id, command, argJson, caller, timeoutMs, cancellation)

    override fun invokeBlocking(
        id: PackId,
        command: String,
        argJson: String,
        caller: PackCaller,
        timeoutMs: Long?,
        cancellation: CancellationSignal,
    ): PackResult {
        val info = infos[id] ?: return failure(PackErrorCode.PACK_NOT_FOUND, "missing pack")
        info.state.admissionDiagnostic()?.let { return failure(it.code, it.message) }
        return PackResult.ok("direct", "{\"id\":\"${id.value}\"}", 1L)
    }

    override suspend fun invoke(invocation: PackInvocation): PackResult =
        invokeBlocking(invocation)

    override fun invokeBlocking(invocation: PackInvocation): PackResult {
        lastInvocation = invocation
        lastBlockingInvocation = invocation
        val info = infos[invocation.expectedRef.id]
            ?: return PackResult.failure(
                invocation.expectedRef.id.value,
                PackDiagnostic(
                    code = PackErrorCode.PACK_NOT_FOUND,
                    message = "missing pack",
                ),
                0L,
            )
        if (info.ref != invocation.expectedRef) {
            return PackResult.failure(
                invocation.expectedRef.id.value,
                PackDiagnostic(
                    code = PackErrorCode.PACK_DISABLED,
                    message = "stale pack epoch",
                ),
                0L,
            )
        }
        info.state.admissionDiagnostic()?.let {
            return PackResult.failure(invocation.expectedRef.id.value, it, 0L)
        }
        return resultFactory(invocation)
    }

    override suspend fun selfTest(id: PackId): SelfTestReport {
        selfTestCount++
        return latestReports[id] ?: throw PackException(
            PackDiagnostic(
                code = PackErrorCode.PACK_NOT_FOUND,
                message = "no report",
            )
        )
    }

    override fun selfTestReport(id: PackId, runId: String?): SelfTestReport? =
        if (runId == null) {
            latestReports[id]
        } else {
            reports[id.value + ":" + runId]
        }

    override fun info(id: PackId): PackInfo? = infos[id]

    override fun list(): List<PackInfo> = infos.values.toList()

    // Mirrors DefaultPackManager.admit: BROKEN packs are admitted so a
    // successful call can self-heal (markHealthy); only DISABLED and
    // NEEDS_RESTART are rejected before dispatch.
    private fun PackState.admissionDiagnostic(): PackDiagnostic? = when (this) {
        PackState.DISABLED -> PackDiagnostic(PackErrorCode.PACK_DISABLED, "disabled pack")
        PackState.NEEDS_RESTART -> PackDiagnostic(PackErrorCode.NEEDS_RESTART, "restart required")
        PackState.BROKEN, PackState.ENABLED -> null
    }

    private fun failure(code: PackErrorCode, message: String): PackResult =
        PackResult.failure(
            "fake",
            PackDiagnostic(code = code, message = message),
            0L,
        )
}