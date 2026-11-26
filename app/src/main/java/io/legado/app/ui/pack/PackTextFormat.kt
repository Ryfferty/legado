package io.legado.app.ui.pack

import android.content.Context
import io.legado.app.R
import io.legado.pack.api.ComponentSpec
import io.legado.pack.api.ImportPreview
import io.legado.pack.api.NetworkPolicy
import io.legado.pack.api.PackCapability
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackInfo
import io.legado.pack.api.PackManifest
import io.legado.pack.api.PackState
import io.legado.pack.api.SelfTestReport
import io.legado.pack.api.SelfTestStatus
import io.legado.pack.api.UninstallReport
import java.text.DateFormat
import java.util.Date

/**
 * 扩展包界面的纯文本组装器。全部文本来自 strings_pack 资源,
 * 只呈现 PackManager 公共契约中真实存在的字段:
 * 不虚构 CPU/流量/日志量等配额数值(那需要诊断 API, 见集成说明)。
 */
object PackTextFormat {

    fun diagnosticText(context: Context, diagnostic: PackDiagnostic): String {
        return buildString {
            append('[').append(diagnostic.code.wire).append("] ")
            append(diagnostic.message)
            diagnostic.path?.let { append("\n").append(it) }
            if (diagnostic.details.isNotEmpty()) {
                append('\n')
                diagnostic.details.entries.joinTo(this, "\n") { (key, value) -> "$key=$value" }
            }
        }
    }

    fun stateText(context: Context, state: PackState): String = when (state) {
        PackState.ENABLED -> context.getString(R.string.pack_state_enabled)
        PackState.DISABLED -> context.getString(R.string.pack_state_disabled)
        PackState.NEEDS_RESTART -> context.getString(R.string.pack_state_needs_restart)
        PackState.BROKEN -> context.getString(R.string.pack_state_broken)
    }

    fun selfTestStatusText(context: Context, status: SelfTestStatus): String = when (status) {
        SelfTestStatus.PASS -> context.getString(R.string.pack_self_test_status_pass)
        SelfTestStatus.FAIL -> context.getString(R.string.pack_self_test_status_fail)
        SelfTestStatus.ERROR -> context.getString(R.string.pack_self_test_status_error)
        SelfTestStatus.TIMEOUT -> context.getString(R.string.pack_self_test_status_timeout)
        SelfTestStatus.SKIP -> context.getString(R.string.pack_self_test_status_skip)
    }

    fun timeText(epochMs: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(epochMs))

    /** 导入确认对话框: manifest + 权限摘要 + 内容指纹/警告。 */
    fun importSummary(context: Context, preview: ImportPreview): String {
        return buildString {
            append(manifestSummary(context, preview.manifest))
            appendLine(label(context, R.string.pack_field_content_sha, preview.contentSha256.value))
            if (preview.warnings.isNotEmpty()) {
                appendLine(label(context, R.string.pack_field_warnings))
                preview.warnings.forEach { warning ->
                    appendLine("  ${diagnosticText(context, warning)}")
                }
            }
        }
    }

    /** 已安装包详情。 */
    fun manifestDetail(context: Context, info: PackInfo): String {
        return buildString {
            append(manifestSummary(context, info.manifest))
            appendLine(label(context, R.string.pack_field_state, stateText(context, info.state)))
            appendLine(label(context, R.string.pack_field_installed_at, timeText(info.installedAtEpochMs)))
            appendLine(label(context, R.string.pack_field_revision, info.ref.revision.value.take(16)))
        }
    }

    private fun manifestSummary(context: Context, manifest: PackManifest): String {
        return buildString {
            appendLine(label(context, R.string.pack_field_name, manifest.name))
            appendLine(label(context, R.string.pack_field_id, manifest.id.value))
            appendLine(
                label(
                    context,
                    R.string.pack_field_version,
                    context.getString(R.string.pack_version_code_s, manifest.version, manifest.versionCode),
                )
            )
            if (manifest.author.isNotBlank()) {
                appendLine(label(context, R.string.pack_field_author, manifest.author))
            }
            if (manifest.description.isNotBlank()) {
                appendLine(label(context, R.string.pack_field_description, manifest.description))
            }
            appendLine(label(context, R.string.pack_field_pack_lib, manifest.packLib.toString()))
            append(componentLines(context, manifest))
            appendLine(label(context, R.string.pack_field_capabilities, capabilitiesText(context, manifest)))
            append(networkLines(context, manifest))
            if (manifest.commands.isNotEmpty()) {
                appendLine(label(context, R.string.pack_field_commands_list))
                manifest.commands.forEach { command ->
                    val suffix = if (command.sourceExported) {
                        " " + context.getString(R.string.pack_command_source_exported)
                    } else {
                        ""
                    }
                    appendLine("  ${command.name} -> ${command.componentId}$suffix")
                }
            }
            if (manifest.mcpTools.isNotEmpty()) {
                appendLine(label(context, R.string.pack_field_tools))
                manifest.mcpTools.forEach { tool ->
                    val suffix = if (tool.isWrite) {
                        " " + context.getString(R.string.pack_tool_write_suffix)
                    } else {
                        ""
                    }
                    appendLine("  ${tool.name} -> ${tool.command}$suffix")
                }
            }
            if (manifest.apiRoutes.isNotEmpty()) {
                appendLine(label(context, R.string.pack_field_routes))
                manifest.apiRoutes.forEach { route ->
                    appendLine("  ${route.name} ${route.method} -> ${route.command}")
                }
            }
            if (manifest.selfTests.isNotEmpty()) {
                appendLine(
                    label(
                        context,
                        R.string.pack_field_self_tests,
                        manifest.selfTests.joinToString(", ") { it.name },
                    )
                )
            }
        }
    }

    fun permissionDiffText(context: Context, diff: PackGrantPolicy.PermissionDiff): String {
        if (!diff.changed) return ""
        return buildString {
            appendLine(context.getString(R.string.pack_permission_changed))
            addSet(this, context, R.string.pack_field_capabilities, diff.addedCapabilities, R.string.pack_permission_added)
            addSet(this, context, R.string.pack_field_capabilities, diff.removedCapabilities, R.string.pack_permission_removed)
            if (diff.networkCanonicalChanged) {
                appendLine(
                    context.getString(
                        R.string.pack_permission_network_changed,
                        context.getString(R.string.pack_field_network),
                    )
                )
            }
            addSet(this, context, R.string.pack_field_tools, diff.addedTools, R.string.pack_permission_added)
            addSet(this, context, R.string.pack_field_tools, diff.removedTools, R.string.pack_permission_removed)
            addSet(this, context, R.string.pack_field_write_tools, diff.addedWriteTools, R.string.pack_permission_added)
            addSet(this, context, R.string.pack_field_write_tools, diff.removedWriteTools, R.string.pack_permission_removed)
            addSet(this, context, R.string.pack_field_routes, diff.addedRoutes, R.string.pack_permission_added)
            addSet(this, context, R.string.pack_field_routes, diff.removedRoutes, R.string.pack_permission_removed)
            addSet(
                this,
                context,
                R.string.pack_field_commands_list,
                diff.addedSourceCommands,
                R.string.pack_permission_added,
            )
            addSet(
                this,
                context,
                R.string.pack_field_commands_list,
                diff.removedSourceCommands,
                R.string.pack_permission_removed,
            )
        }
    }

    private fun addSet(
        builder: StringBuilder,
        context: Context,
        fieldRes: Int,
        values: Set<Any>,
        formatRes: Int,
    ) {
        if (values.isEmpty()) return
        builder.appendLine(
            context.getString(formatRes, context.getString(fieldRes), values.joinToString(", "))
        )
    }

    /** 同包升级上下文行。 */
    fun upgradeLine(context: Context, existing: PackInfo, incoming: PackManifest): String {
        return context.getString(
            R.string.pack_upgrade_line,
            existing.manifest.version,
            existing.manifest.versionCode,
            incoming.version,
            incoming.versionCode,
        )
    }

    fun selfTestReportText(context: Context, report: SelfTestReport): String {
        return buildString {
            appendLine(context.getString(R.string.pack_self_test_run_id, report.runId))
            appendLine(label(context, R.string.pack_field_id, report.packRef.id.value))
            appendLine(
                context.getString(
                    R.string.pack_self_test_time,
                    timeText(report.startedAtEpochMs),
                    timeText(report.finishedAtEpochMs),
                )
            )
            appendLine(
                context.getString(
                    R.string.pack_self_test_summary,
                    report.cases.size,
                    report.cases.count { it.status == SelfTestStatus.PASS },
                )
            )
            appendLine()
            report.cases.forEach { case ->
                val status = selfTestStatusText(context, case.status)
                val line = if (case.message.isNullOrBlank()) {
                    context.getString(R.string.pack_self_test_case, case.name, status, case.elapsedMs)
                } else {
                    context.getString(
                        R.string.pack_self_test_case_with_message,
                        case.name,
                        status,
                        case.message,
                        case.elapsedMs,
                    )
                }
                appendLine(line)
            }
            report.redCards.forEach { card ->
                appendLine(context.getString(R.string.pack_self_test_red_card, diagnosticText(context, card)))
            }
            // 只呈现框架真实上报的耗时; CPU/流量/日志量未在公共契约中提供, 不展示。
            appendLine(context.getString(R.string.pack_self_test_metrics_elapsed, report.metrics.elapsedMs))
            report.metrics.notes.forEach { note ->
                appendLine(note)
            }
        }
    }

    fun uninstallReportText(context: Context, report: UninstallReport): String {
        return buildString {
            appendLine(label(context, R.string.pack_field_id, report.packId.value))
            if (report.clean) {
                appendLine(context.getString(R.string.pack_uninstall_clean))
            } else {
                if (report.registrationRevoked) {
                    appendLine(context.getString(R.string.pack_uninstall_registration_revoked, yes(context)))
                } else {
                    appendLine(context.getString(R.string.pack_uninstall_registration_kept))
                }
                if (report.residualPaths.isNotEmpty()) {
                    appendLine(context.getString(R.string.pack_uninstall_residual_paths))
                    report.residualPaths.forEach { path ->
                        appendLine("  $path")
                    }
                }
                if (report.loadedCodeStillResident) {
                    appendLine(context.getString(R.string.pack_uninstall_resident))
                }
                if (report.needsRestart) {
                    appendLine(context.getString(R.string.pack_uninstall_needs_restart))
                }
                report.failure?.let {
                    appendLine(context.getString(R.string.pack_uninstall_failure, diagnosticText(context, it)))
                }
            }
        }
    }

    private fun yes(context: Context): String = context.getString(R.string.yes)

    private fun label(context: Context, labelRes: Int, value: String): String =
        "${context.getString(labelRes)}: $value"

    private fun label(context: Context, labelRes: Int): String =
        "${context.getString(labelRes)}:"

    private fun capabilitiesText(context: Context, manifest: PackManifest): String {
        if (manifest.capabilities.isEmpty()) {
            return context.getString(R.string.pack_none)
        }
        return manifest.capabilities.joinToString(", ") { capability ->
            capabilityName(context, capability)
        }
    }

    private fun capabilityName(context: Context, capability: PackCapability): String =
        capability.name.lowercase()

    private fun componentLines(context: Context, manifest: PackManifest): String {
        return buildString {
            appendLine(label(context, R.string.pack_field_components))
            manifest.components.forEach { component ->
                appendLine("  ${componentText(context, component)}")
            }
        }
    }

    private fun componentText(context: Context, component: ComponentSpec): String {
        return when (component) {
            is ComponentSpec.Js ->
                "${context.getString(R.string.pack_component_js)} ${component.id}: ${component.entry.value}"

            is ComponentSpec.Dex ->
                "${context.getString(R.string.pack_component_dex)} ${component.id}: ${component.path.value}"

            is ComponentSpec.Native -> {
                val mode = component.requestedMode?.wire ?: ""
                buildString {
                    append(context.getString(R.string.pack_component_native), " ")
                    append(component.id).append(": ")
                    append(component.path.value).append(" (")
                    append(component.abi.wire)
                    if (mode.isNotEmpty()) {
                        append(", ").append(mode)
                    }
                    append(')')
                }
            }
        }
    }

    private fun networkLines(context: Context, manifest: PackManifest): String {
        return when (val network = manifest.network) {
            is NetworkPolicy.None ->
                label(context, R.string.pack_field_network, context.getString(R.string.pack_network_none))

            is NetworkPolicy.Allowlist -> {
                val patterns = network.patterns.joinToString(", ") { it.value }
                label(
                    context,
                    R.string.pack_field_network,
                    context.getString(R.string.pack_network_allowlist, patterns),
                )
            }

            is NetworkPolicy.Open -> {
                val base = label(
                    context,
                    R.string.pack_field_network,
                    context.getString(R.string.pack_network_open),
                )
                if (network.reason.isBlank()) {
                    base
                } else {
                    base + "\n" + label(
                        context,
                        R.string.pack_field_network_reason,
                        network.reason,
                    )
                }
            }
        } + "\n"
    }
}
