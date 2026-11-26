package io.legado.pack.diagnostics

import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import io.legado.pack.api.PackMetrics
import io.legado.pack.api.PackRef
import io.legado.pack.api.PackRoutes
import io.legado.pack.api.RevisionId
import io.legado.pack.api.SelfTestCaseResult
import io.legado.pack.api.SelfTestReport
import io.legado.pack.api.SelfTestStatus
import io.legado.pack.storage.AtomicFiles
import io.legado.pack.storage.PackJsonText
import io.legado.pack.storage.optionalArray
import io.legado.pack.storage.optionalBoolean
import io.legado.pack.storage.optionalLong
import io.legado.pack.storage.optionalObject
import io.legado.pack.storage.optionalString
import io.legado.pack.storage.readStringMap
import io.legado.pack.storage.requiredArray
import io.legado.pack.storage.requiredInt
import io.legado.pack.storage.requiredLong
import io.legado.pack.storage.requiredObject
import io.legado.pack.storage.requiredString
import java.io.File

internal object SelfTestCodec {

    private const val MAX_REPORT_CHARS = 16 * 1024 * 1024

    fun encode(report: SelfTestReport): String = buildString(2048) {
        append('{')
        append("\"runId\":").append(PackJsonText.quote(report.runId))
        append(",\"packRef\":{")
        append("\"id\":").append(PackJsonText.quote(report.packRef.id.value))
        append(",\"revision\":").append(PackJsonText.quote(report.packRef.revision.value))
        append(",\"epoch\":").append(report.packRef.epoch)
        append('}')
        append(",\"startedAtEpochMs\":").append(report.startedAtEpochMs)
        append(",\"finishedAtEpochMs\":").append(report.finishedAtEpochMs)
        append(",\"metrics\":{")
        append("\"elapsedMs\":").append(report.metrics.elapsedMs)
        append(",\"workerCpuMs\":")
        report.metrics.workerCpuMs?.let { append(it) } ?: append("null")
        append(",\"networkBytesIn\":").append(report.metrics.networkBytesIn)
        append(",\"networkBytesOut\":").append(report.metrics.networkBytesOut)
        append(",\"logBytes\":").append(report.metrics.logBytes)
        append(",\"notes\":[")
        report.metrics.notes.forEachIndexed { index, note ->
            if (index > 0) append(',')
            append(PackJsonText.quote(note))
        }
        append("]}")
        append(",\"cases\":[")
        report.cases.forEachIndexed { index, case ->
            if (index > 0) append(',')
            append('{')
            append("\"name\":").append(PackJsonText.quote(case.name))
            append(",\"status\":").append(PackJsonText.quote(case.status.name))
            append(",\"message\":")
            case.message?.let { append(PackJsonText.quote(it)) } ?: append("null")
            append(",\"dataJson\":")
            case.dataJson?.let { append(PackJsonText.quote(it)) } ?: append("null")
            append(",\"elapsedMs\":").append(case.elapsedMs)
            append('}')
        }
        append(']')
        append(",\"diagnostics\":[")
        report.diagnostics.forEachIndexed { index, diagnostic ->
            if (index > 0) append(',')
            appendDiagnostic(diagnostic)
        }
        append(']')
        append('}')
    }

    fun decode(json: String, expectedPackId: PackId? = null): SelfTestReport {
        val root = PackJsonText.parseObject(json, MAX_REPORT_CHARS, "selfTest")
        val runId = root.requiredString("runId", "selfTest")
        if (!PackRoutes.isValidRunId(runId)) {
            throw invalid("selfTest.runId", "invalid self-test run id")
        }
        val refObj = root.requiredObject("packRef", "selfTest")
        val id = PackId.parse(refObj.requiredString("id", "selfTest.packRef"))
            ?: throw invalid("selfTest.packRef.id", "invalid pack id")
        if (expectedPackId != null && id != expectedPackId) {
            throw invalid("selfTest.packRef.id", "self-test report belongs to another pack")
        }
        val revision = RevisionId.parse(refObj.requiredString("revision", "selfTest.packRef"))
            ?: throw invalid("selfTest.packRef.revision", "invalid revision")
        val packRef = PackRef(id, revision, refObj.requiredLong("epoch", "selfTest.packRef"))
        val started = root.requiredLong("startedAtEpochMs", "selfTest")
        val finished = root.requiredLong("finishedAtEpochMs", "selfTest")
        val metricsObj = root.optionalObject("metrics", "selfTest")
        val metrics = if (metricsObj == null) {
            PackMetrics()
        } else {
            PackMetrics(
                elapsedMs = metricsObj.optionalLong("elapsedMs", "selfTest.metrics") ?: 0L,
                workerCpuMs = metricsObj.optionalLong("workerCpuMs", "selfTest.metrics"),
                networkBytesIn = metricsObj.optionalLong("networkBytesIn", "selfTest.metrics") ?: 0L,
                networkBytesOut = metricsObj.optionalLong("networkBytesOut", "selfTest.metrics") ?: 0L,
                logBytes = metricsObj.optionalLong("logBytes", "selfTest.metrics") ?: 0L,
                notes = metricsObj.optionalArray("notes", "selfTest.metrics")?.items?.map { item ->
                    (item as? io.legado.pack.api.JsonValue.Str)?.value
                        ?: throw invalid("selfTest.metrics.notes", "note must be a string")
                } ?: emptyList(),
            )
        }
        val cases = root.optionalArray("cases", "selfTest")?.items?.mapIndexed { index, item ->
            val obj = item as? io.legado.pack.api.JsonValue.Obj
                ?: throw invalid("selfTest.cases[$index]", "case must be an object")
            val statusName = obj.requiredString("status", "selfTest.cases[$index]")
            val status = SelfTestStatus.entries.firstOrNull { it.name == statusName }
                ?: throw invalid("selfTest.cases[$index].status", "unknown self-test status")
            SelfTestCaseResult(
                name = obj.requiredString("name", "selfTest.cases[$index]"),
                status = status,
                message = obj.optionalString("message", "selfTest.cases[$index]"),
                dataJson = obj.optionalString("dataJson", "selfTest.cases[$index]"),
                elapsedMs = obj.optionalLong("elapsedMs", "selfTest.cases[$index]") ?: 0L,
            )
        } ?: emptyList()
        val diagnostics = root.optionalArray("diagnostics", "selfTest")?.items?.mapIndexed { index, item ->
            decodeDiagnostic(item, index)
        } ?: emptyList()
        return try {
            SelfTestReport(
                runId = runId,
                packRef = packRef,
                startedAtEpochMs = started,
                finishedAtEpochMs = finished,
                cases = cases,
                metrics = metrics,
                diagnostics = diagnostics,
            )
        } catch (e: IllegalArgumentException) {
            throw invalid("selfTest", e.message ?: "invalid self-test report", e)
        }
    }

    private fun StringBuilder.appendDiagnostic(diagnostic: PackDiagnostic) {
        append('{')
        append("\"code\":").append(PackJsonText.quote(diagnostic.code.wire))
        append(",\"message\":").append(PackJsonText.quote(diagnostic.message))
        diagnostic.path?.let { append(",\"path\":").append(PackJsonText.quote(it)) }
        append(",\"details\":").append(PackJsonText.writeStringMap(diagnostic.details))
        append('}')
    }

    private fun decodeDiagnostic(value: io.legado.pack.api.JsonValue, index: Int): PackDiagnostic {
        val obj = value as? io.legado.pack.api.JsonValue.Obj
            ?: throw invalid("selfTest.diagnostics[$index]", "diagnostic must be an object")
        val wire = obj.requiredString("code", "selfTest.diagnostics[$index]")
        val code = PackErrorCode.entries.firstOrNull { it.wire == wire }
            ?: throw invalid("selfTest.diagnostics[$index].code", "unknown diagnostic code")
        return PackDiagnostic(
            code = code,
            message = obj.requiredString("message", "selfTest.diagnostics[$index]"),
            path = obj.optionalString("path", "selfTest.diagnostics[$index]"),
            details = readStringMap(
                obj.optionalObject("details", "selfTest.diagnostics[$index]"),
                "selfTest.diagnostics[$index].details",
            ),
        )
    }

    private fun invalid(path: String, message: String, cause: Throwable? = null): PackException =
        PackException(PackDiagnostic(PackErrorCode.MANIFEST_INVALID, message, path), cause)
}

/**
 * Persists self-test reports next to the pack installation so the Web/MCP
 * `runId` query keeps working after a process restart. It never executes code.
 */
class SelfTestReportStore(
    private val layout: io.legado.pack.api.PackStorageLayout,
    private val onStorageFailure: (PackDiagnostic) -> Unit = {},
) {

    fun save(report: SelfTestReport): PackDiagnostic? {
        val directory = directory(report.packRef.id)
        return try {
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
                throw IllegalStateException("cannot create self-test directory")
            }
            AtomicFiles.writeText(File(directory, "${report.runId}.json"), SelfTestCodec.encode(report))
            AtomicFiles.writeText(File(directory, LATEST_FILE), report.runId)
            prune(directory, keep = report.runId)
            null
        } catch (e: Exception) {
            val diagnostic = PackDiagnostic(
                code = PackErrorCode.IO_FAILED,
                message = "cannot persist self-test report: ${e.message}",
                path = "self-tests/${report.runId}",
            )
            onStorageFailure(diagnostic)
            diagnostic
        }
    }

    fun load(id: PackId, runId: String? = null): SelfTestReport? {
        val directory = directory(id)
        if (!directory.isDirectory) return null
        if (runId != null) {
            if (!PackRoutes.isValidRunId(runId)) return null
            return loadFile(File(directory, "$runId.json"), id)
        }
        val latest = File(directory, LATEST_FILE)
        if (latest.isFile) {
            val latestRunId = runCatching {
                AtomicFiles.readText(latest, 128L, LATEST_FILE).trim()
            }.getOrNull()
            if (latestRunId != null && PackRoutes.isValidRunId(latestRunId)) {
                val report = loadFile(File(directory, "$latestRunId.json"), id)
                if (report != null) return report
            }
        }
        val files = directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") && it.name.length > 5 }
            ?.sortedByDescending { it.name }
            ?: return null
        files.forEach { file ->
            val report = loadFile(file, id)
            if (report != null) return report
        }
        return null
    }

    fun recent(id: PackId, limit: Int): List<SelfTestReport> {
        if (limit <= 0) return emptyList()
        val directory = directory(id)
        if (!directory.isDirectory) return emptyList()
        val result = ArrayList<SelfTestReport>()
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") && it.name.length > 5 }
            ?.sortedByDescending { it.name }
            ?.forEach { file ->
                if (result.size >= limit) return@forEach
                loadFile(file, id)?.let { result += it }
            }
        return result
    }

    private fun loadFile(file: File, id: PackId): SelfTestReport? {
        if (!file.isFile) return null
        return try {
            val text = AtomicFiles.readText(file, MAX_REPORT_BYTES, file.name)
            val report = SelfTestCodec.decode(text, expectedPackId = id)
            if (file.name != "${report.runId}.json") {
                throw IllegalStateException("report file name does not match its runId")
            }
            report
        } catch (e: Exception) {
            onStorageFailure(
                PackDiagnostic(
                    code = PackErrorCode.IO_FAILED,
                    message = "cannot read self-test report: ${e.message}",
                    path = "self-tests/${file.name}",
                ),
            )
            null
        }
    }

    private fun prune(directory: File, keep: String) {
        val files = directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") && it.name != "$keep.json" }
            ?.sortedBy { it.lastModified() }
            ?: return
        val excess = files.size + 1 - MAX_REPORTS
        if (excess <= 0) return
        files.take(excess).forEach { it.delete() }
    }

    private fun directory(id: PackId): File = File(layout.packDir(id), "self-tests")

    companion object {
        private const val LATEST_FILE = "latest"
        private const val MAX_REPORTS = 20
        private const val MAX_REPORT_BYTES = 16L * 1024 * 1024
    }
}