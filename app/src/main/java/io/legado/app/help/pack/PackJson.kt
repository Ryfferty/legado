package io.legado.app.help.pack

import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackInfo
import io.legado.pack.api.PackMetrics
import io.legado.pack.api.PackRef
import io.legado.pack.api.PackResult
import io.legado.pack.api.SelfTestReport
import io.legado.pack.api.ToolSpec
import io.legado.pack.api.UninstallReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Stable `{code,data,message,isError}` envelopes shared by the pack MCP tools
 * and the `/packs/...` Web API. Existing built-in MCP tools keep their legacy
 * plain-text responses; only pack-related endpoints use this envelope.
 */
object PackJson {

    fun payload(
        code: String,
        data: JsonElement = JsonNull,
        message: String? = null,
        isError: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("code", code)
        put("data", data)
        put("message", message?.let { JsonPrimitive(it) } ?: JsonNull)
        put("isError", isError)
    }

    fun success(data: JsonElement = JsonNull, message: String? = null): JsonObject =
        payload("OK", data, message, false)

    fun error(
        code: String,
        message: String,
        data: JsonElement = JsonNull,
    ): JsonObject = payload(code, data, message, true)

    fun result(result: PackResult): JsonObject = buildJsonObject {
        put("code", result.code)
        put("data", dataElement(result.dataJson))
        put("message", result.message?.let { JsonPrimitive(it) } ?: JsonNull)
        put("isError", result.isError)
        put("requestId", result.requestId)
        put("elapsedMs", result.elapsedMs)
    }

    fun dataElement(raw: String?): JsonElement {
        if (raw.isNullOrBlank()) return JsonNull
        return try {
            Json.parseToJsonElement(raw)
        } catch (_: Exception) {
            // The runtime contract requires JSON. Preserve an invalid payload
            // verbatim rather than losing it; the envelope remains valid.
            JsonPrimitive(raw)
        }
    }

    fun ref(ref: PackRef): JsonObject = buildJsonObject {
        put("id", ref.id.value)
        put("revision", ref.revision.value)
        put("epoch", ref.epoch)
    }

    fun info(
        info: PackInfo,
        permissions: PackToolPermissionStore? = null,
    ): JsonObject = buildJsonObject {
        put("id", info.ref.id.value)
        put("revision", info.ref.revision.value)
        put("epoch", info.ref.epoch)
        put("name", info.manifest.name)
        put("version", info.manifest.version)
        put("versionCode", info.manifest.versionCode)
        put("description", info.manifest.description)
        put("author", info.manifest.author)
        put("state", info.state.name)
        put("installedAtEpochMs", info.installedAtEpochMs)
        put("network", info.manifest.network.canonical())
        put("permissionHash", info.manifest.permissions.digest())
        putJsonArray("capabilities") {
            info.manifest.capabilities.map { it.name }.sorted().forEach { add(JsonPrimitive(it)) }
        }
        putJsonArray("tools") {
            info.manifest.mcpTools.forEach { spec ->
                add(
                    tool(
                        tool = spec,
                        writeEnabled = if (spec.isWrite) {
                            permissions?.isWriteToolEnabled(
                                info.ref,
                                info.manifest,
                                spec.name,
                            )
                        } else {
                            null
                        },
                    )
                )
            }
        }
        putJsonArray("apiRoutes") {
            info.manifest.apiRoutes.forEach { route ->
                add(buildJsonObject {
                    put("name", route.name)
                    put("method", route.method.name)
                    put("command", route.command)
                    put("isWrite", route.isWrite)
                })
            }
        }
        putJsonArray("selfTests") {
            info.manifest.selfTests.forEach { test ->
                add(buildJsonObject {
                    put("name", test.name)
                    put("command", test.command)
                    put("timeoutMs", test.timeoutMs)
                })
            }
        }
    }

    fun tool(tool: ToolSpec, writeEnabled: Boolean? = null): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("command", tool.command)
        put("description", tool.description)
        put("isWrite", tool.isWrite)
        if (writeEnabled != null) {
            put("writeEnabled", writeEnabled)
        }
        put("inputSchema", PackMcpSchema.toJsonElement(tool.inputSchema))
    }

    fun report(report: SelfTestReport): JsonObject = buildJsonObject {
        put("runId", report.runId)
        put("pack", ref(report.packRef))
        put("startedAtEpochMs", report.startedAtEpochMs)
        put("finishedAtEpochMs", report.finishedAtEpochMs)
        put("passed", report.passed)
        putJsonArray("cases") {
            report.cases.forEach { case ->
                add(buildJsonObject {
                    put("name", case.name)
                    put("status", case.status.name)
                    put("message", case.message?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("data", dataElement(case.dataJson))
                    put("elapsedMs", case.elapsedMs)
                })
            }
        }
        put("metrics", metrics(report.metrics))
        putJsonArray("diagnostics") {
            report.diagnostics.forEach { add(diagnostic(it)) }
        }
        putJsonArray("redCards") {
            report.redCards.forEach { add(diagnostic(it)) }
        }
        putJsonArray("failedCases") {
            report.failedCases.forEach { case -> add(JsonPrimitive(case.name)) }
        }
    }

    fun uninstall(report: UninstallReport): JsonObject = buildJsonObject {
        put("packId", report.packId.value)
        put("registrationRevoked", report.registrationRevoked)
        putJsonArray("residualPaths") {
            report.residualPaths.forEach { add(JsonPrimitive(it)) }
        }
        put("loadedCodeStillResident", report.loadedCodeStillResident)
        put("needsRestart", report.needsRestart)
        put("clean", report.clean)
        put("failure", report.failure?.let { diagnostic(it) } ?: JsonNull)
    }

    fun diagnostic(diagnostic: PackDiagnostic): JsonObject = buildJsonObject {
        put("code", diagnostic.code.wire)
        put("message", diagnostic.message)
        put("path", diagnostic.path?.let { JsonPrimitive(it) } ?: JsonNull)
        put("severity", diagnostic.severity.name)
        put("redCard", diagnostic.isRedCard)
        put("details", buildJsonObject {
            diagnostic.details.forEach { (key, value) -> put(key, value) }
        })
    }

    private fun metrics(metrics: PackMetrics): JsonObject = buildJsonObject {
        put("elapsedMs", metrics.elapsedMs)
        put("workerCpuMs", metrics.workerCpuMs)
        put("networkBytesIn", metrics.networkBytesIn)
        put("networkBytesOut", metrics.networkBytesOut)
        put("logBytes", metrics.logBytes)
        putJsonArray("notes") { metrics.notes.forEach { add(JsonPrimitive(it)) } }
    }
}