package io.legado.pack.api

enum class PackCallStatus {
    OK,
    ERROR,
    TIMEOUT,
    CANCELLED,
    NEEDS_RESTART,
}

/**
 * Structured result for one pack call.
 *
 * `status == OK` iff `error == null`; every failure carries a typed
 * [PackDiagnostic]. HTTP/MCP adapters may serialize this without losing the
 * stable code or red-card severity.
 */
data class PackResult(
    val requestId: String,
    val status: PackCallStatus,
    val dataJson: String?,
    val message: String?,
    val elapsedMs: Long,
    val error: PackDiagnostic? = null,
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(elapsedMs >= 0L) { "elapsedMs must be >= 0" }
        require((status == PackCallStatus.OK) == (error == null)) {
            "OK results must not carry an error and failures must carry one"
        }
    }

    /** Stable wire code; "OK" for a successful call. */
    val code: String get() = error?.code?.wire ?: "OK"

    val isError: Boolean get() = error != null

    companion object {
        fun ok(
            requestId: String,
            dataJson: String?,
            elapsedMs: Long,
        ): PackResult = PackResult(
            requestId = requestId,
            status = PackCallStatus.OK,
            dataJson = dataJson,
            message = null,
            elapsedMs = elapsedMs,
            error = null,
        )

        fun failure(
            requestId: String,
            error: PackDiagnostic,
            elapsedMs: Long,
        ): PackResult {
            val status = when (error.code) {
                PackErrorCode.TIMEOUT -> PackCallStatus.TIMEOUT
                PackErrorCode.CANCELLED -> PackCallStatus.CANCELLED
                PackErrorCode.NEEDS_RESTART -> PackCallStatus.NEEDS_RESTART
                else -> PackCallStatus.ERROR
            }
            return PackResult(
                requestId = requestId,
                status = status,
                dataJson = null,
                message = error.message,
                elapsedMs = elapsedMs,
                error = error,
            )
        }
    }
}

enum class PackLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/**
 * Per-call/per-pack metrics. Values only cover traffic and time mediated by
 * framework primitives. Trusted DEX/native code can bypass those primitives,
 * so the host must not present these numbers as a complete audit.
 */
data class PackMetrics(
    val elapsedMs: Long = 0L,
    val workerCpuMs: Long? = null,
    val networkBytesIn: Long = 0L,
    val networkBytesOut: Long = 0L,
    val logBytes: Long = 0L,
    val notes: List<String> = emptyList(),
) {
    init {
        require(elapsedMs >= 0L) { "elapsedMs must be >= 0" }
        require(workerCpuMs == null || workerCpuMs >= 0L) { "workerCpuMs must be >= 0" }
        require(networkBytesIn >= 0L) { "networkBytesIn must be >= 0" }
        require(networkBytesOut >= 0L) { "networkBytesOut must be >= 0" }
        require(logBytes >= 0L) { "logBytes must be >= 0" }
    }
}

data class DiagnosticEvent(
    val timestampEpochMs: Long,
    val level: PackLogLevel,
    val message: String,
    val code: PackErrorCode? = null,
    val packRef: PackRef? = null,
    val requestId: String? = null,
    /** Where the failure happened, e.g. `mcpTools.demo_add` or `logs/<id>`. */
    val path: String? = null,
    val details: Map<String, String> = emptyMap(),
) {
    init {
        require(message.isNotBlank()) { "diagnostic event message must not be blank" }
    }
}

enum class SelfTestStatus {
    PASS,
    FAIL,
    ERROR,
    TIMEOUT,
    SKIP,
}

data class SelfTestCaseResult(
    val name: String,
    val status: SelfTestStatus,
    val message: String? = null,
    val dataJson: String? = null,
    val elapsedMs: Long = 0L,
) {
    init {
        require(name.isNotBlank()) { "self-test case name must not be blank" }
        require(elapsedMs >= 0L) { "elapsedMs must be >= 0" }
    }
}

/**
 * Bound, structured self-test report. The same report is returned to the local
 * UI and served to Web/MCP readers by `runId`.
 */
class SelfTestReport(
    val runId: String,
    val packRef: PackRef,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    cases: List<SelfTestCaseResult>,
    val metrics: PackMetrics = PackMetrics(),
    diagnostics: List<PackDiagnostic> = emptyList(),
) {
    val cases: List<SelfTestCaseResult> = immutableList(cases)
    val diagnostics: List<PackDiagnostic> = immutableList(diagnostics)

    init {
        require(PackRoutes.isValidRunId(runId)) { "invalid self-test runId" }
        require(finishedAtEpochMs >= startedAtEpochMs) {
            "self-test finish time must not precede its start time"
        }
    }

    val passed: Boolean
        get() = cases.isNotEmpty() &&
            cases.all { it.status == SelfTestStatus.PASS } &&
            diagnostics.none { it.isRedCard }

    val redCards: List<PackDiagnostic>
        get() = diagnostics.filter { it.isRedCard }

    val failedCases: List<SelfTestCaseResult>
        get() = cases.filter { it.status != SelfTestStatus.PASS && it.status != SelfTestStatus.SKIP }

    fun copy(
        runId: String = this.runId,
        packRef: PackRef = this.packRef,
        startedAtEpochMs: Long = this.startedAtEpochMs,
        finishedAtEpochMs: Long = this.finishedAtEpochMs,
        cases: List<SelfTestCaseResult> = this.cases,
        metrics: PackMetrics = this.metrics,
        diagnostics: List<PackDiagnostic> = this.diagnostics,
    ): SelfTestReport = SelfTestReport(
        runId = runId,
        packRef = packRef,
        startedAtEpochMs = startedAtEpochMs,
        finishedAtEpochMs = finishedAtEpochMs,
        cases = cases,
        metrics = metrics,
        diagnostics = diagnostics,
    )

    override fun equals(other: Any?): Boolean =
        other is SelfTestReport &&
            runId == other.runId &&
            packRef == other.packRef &&
            startedAtEpochMs == other.startedAtEpochMs &&
            finishedAtEpochMs == other.finishedAtEpochMs &&
            cases == other.cases &&
            metrics == other.metrics &&
            diagnostics == other.diagnostics

    override fun hashCode(): Int {
        var result = runId.hashCode()
        result = 31 * result + packRef.hashCode()
        result = 31 * result + startedAtEpochMs.hashCode()
        result = 31 * result + finishedAtEpochMs.hashCode()
        result = 31 * result + cases.hashCode()
        result = 31 * result + metrics.hashCode()
        result = 31 * result + diagnostics.hashCode()
        return result
    }

    override fun toString(): String = "SelfTestReport(runId=$runId, pack=${packRef.id.value})"
}