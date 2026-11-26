package io.legado.app.web.mcp

import io.legado.app.help.pack.PackJson
import io.legado.app.help.pack.PackToolPermissionStore
import io.legado.pack.api.PackCancelledException
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import io.legado.pack.api.PackManager
import io.legado.pack.api.PackTimeoutException
import io.legado.pack.api.PackRoutes
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Host-owned pack management/self-test MCP tools.
 *
 * These are not pack tools and are not governed by pack grants; they require
 * the same `X-Legado-Token` protection as every other MCP request. Their
 * responses use the structured `{code,data,message,isError}` envelope.
 */
internal class McpPackTools(
    private val managerProvider: () -> PackManager,
    private val permissions: PackToolPermissionStore,
    private val bridge: PackMcpBridge? = null,
) {

    private val readAnnotations = ToolAnnotations(
        readOnlyHint = true,
        openWorldHint = false,
    )
    private val writeAnnotations = ToolAnnotations(
        readOnlyHint = false,
        destructiveHint = true,
        idempotentHint = true,
        openWorldHint = false,
    )
    private val testAnnotations = ToolAnnotations(
        readOnlyHint = false,
        destructiveHint = true,
        idempotentHint = false,
        openWorldHint = false,
    )
    private val authorizationAnnotations = ToolAnnotations(
        readOnlyHint = false,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false,
    )

    fun register(server: Server) {
        server.addTool(
            name = "pack_list",
            description = "列出已安装扩展包、启用状态、声明的工具/路由/自检和写入授权状态。",
            inputSchema = ToolSchema(),
            toolAnnotations = readAnnotations,
        ) {
            listResult()
        }

        server.addTool(
            name = "pack_info",
            description = "读取单个扩展包的 manifest 元数据、工具、路由、自检和 MCP 注册告警；不执行包代码。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("id", stringProp("扩展包 id"))
                },
                required = listOf("id"),
            ),
            toolAnnotations = readAnnotations,
        ) { request ->
            infoResult(request.arguments.string("id"))
        }

        server.addTool(
            name = "pack_selftest",
            description = "执行扩展包声明的自检并返回完整报告；报告会绑定 runId，可在之后单独查询。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("id", stringProp("扩展包 id"))
                },
                required = listOf("id"),
            ),
            toolAnnotations = testAnnotations,
        ) { request ->
            selftestResult(request.arguments.string("id"))
        }

        server.addTool(
            name = "pack_selftest_report",
            description = "只读查询已产生的自检报告，不执行包代码；runId 省略时返回该包最新报告。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("id", stringProp("扩展包 id"))
                    put("runId", stringProp("可选的自检 runId"))
                },
                required = listOf("id"),
            ),
            toolAnnotations = readAnnotations,
        ) { request ->
            reportResult(
                request.arguments.string("id"),
                request.arguments.string("runId"),
            )
        }

        server.addTool(
            name = "pack_tool_permissions",
            description = "列出扩展包声明的工具及当前宿主写入授权状态；不修改状态、不执行包代码。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("id", stringProp("扩展包 id"))
                },
                required = listOf("id"),
            ),
            toolAnnotations = readAnnotations,
        ) { request ->
            permissionsResult(request.arguments.string("id"))
        }

        server.addTool(
            name = "pack_set_write_tool",
            description = "显式启用或关闭扩展包声明的写入工具；只有 manifest 标记 isWrite 的工具可被授权。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("id", stringProp("扩展包 id"))
                    put("tool", stringProp("工具名"))
                    putJsonObject("enabled") {
                        put("type", "boolean")
                        put("description", "true 授权写入，false 撤销授权")
                    }
                },
                required = listOf("id", "tool", "enabled"),
            ),
            toolAnnotations = authorizationAnnotations,
        ) { request ->
            setWriteToolResult(
                request.arguments.string("id"),
                request.arguments.string("tool"),
                request.arguments.boolean("enabled"),
            )
        }

        server.addTool(
            name = "pack_set_enabled",
            description = "启用或禁用扩展包。禁用会立即撤销 MCP 工具注册；已登记的回调仍带 epoch 校验。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("id", stringProp("扩展包 id"))
                    putJsonObject("enabled") {
                        put("type", "boolean")
                        put("description", "true 启用，false 禁用")
                    }
                },
                required = listOf("id", "enabled"),
            ),
            toolAnnotations = writeAnnotations,
        ) { request ->
            setEnabledResult(
                request.arguments.string("id"),
                request.arguments.boolean("enabled"),
            )
        }

        server.addTool(
            name = "pack_uninstall",
            description = "卸载扩展包并撤销注册，返回残留文件和需重启状态；该操作删除包目录。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("id", stringProp("扩展包 id"))
                },
                required = listOf("id"),
            ),
            toolAnnotations = testAnnotations,
        ) { request ->
            uninstallResult(request.arguments.string("id"))
        }
    }

    internal fun listResult(): CallToolResult = syncToolCall {
        val manager = managerProvider()
        val data = buildJsonObject {
            putJsonArray("packs") {
                manager.list().forEach { add(PackJson.info(it, permissions)) }
            }
            putJsonObject("mcpWarnings") {
                bridge?.warningsSnapshot()?.forEach { (packId, diagnostics) ->
                    putJsonArray(packId) {
                        diagnostics.forEach { add(PackJson.diagnostic(it)) }
                    }
                }
            }
        }
        PackMcpJson.success(data)
    }

    internal fun infoResult(rawId: String?): CallToolResult = syncToolCall {
        val info = requireInfo(rawId)
        val data = buildJsonObject {
            PackJson.info(info, permissions).forEach { (key, value) -> put(key, value) }
            putJsonArray("mcpWarnings") {
                bridge?.warningsFor(info.ref.id).orEmpty()
                    .forEach { add(PackJson.diagnostic(it)) }
            }
        }
        PackMcpJson.success(data)
    }

    internal suspend fun selftestResult(rawId: String?): CallToolResult =
        suspendToolCall {
            val report = managerProvider().selfTest(requirePackId(rawId))
            PackMcpJson.success(PackJson.report(report))
        }

    internal fun reportResult(rawId: String?, runId: String?): CallToolResult =
        syncToolCall {
            val id = requirePackId(rawId)
            val normalizedRunId = runId?.trim()?.takeIf { it.isNotEmpty() }
            if (normalizedRunId != null && !PackRoutes.isValidRunId(normalizedRunId)) {
                return@syncToolCall PackMcpJson.error(
                    code = PackErrorCode.INVALID_REQUEST.wire,
                    message = "runId 格式无效",
                )
            }
            val report = managerProvider().selfTestReport(id, normalizedRunId)
                ?: return@syncToolCall PackMcpJson.error(
                    code = "REPORT_NOT_FOUND",
                    message = "没有找到对应的自检报告",
                )
            PackMcpJson.success(PackJson.report(report))
        }

    internal fun permissionsResult(rawId: String?): CallToolResult = syncToolCall {
        val info = requireInfo(rawId)
        PackMcpJson.success(PackJson.info(info, permissions))
    }

    internal fun setWriteToolResult(
        rawId: String?,
        toolName: String?,
        enabled: Boolean?,
    ): CallToolResult = syncToolCall {
        val id = requirePackId(rawId)
        val tool = toolName?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@syncToolCall PackMcpJson.error(
                code = PackErrorCode.INVALID_REQUEST.wire,
                message = "tool 不能为空",
            )
        val enabledValue = enabled ?: return@syncToolCall PackMcpJson.error(
            code = PackErrorCode.INVALID_REQUEST.wire,
            message = "enabled 必须为布尔值",
        )
        val info = requireInfo(rawId)
        val changed = permissions.setWriteToolEnabled(
            info.ref,
            info.manifest,
            tool,
            enabledValue,
        )
        if (!changed) {
            return@syncToolCall PackMcpJson.error(
                code = PackErrorCode.INVALID_REQUEST.wire,
                message = "工具 $tool 不是该扩展包声明的写入工具",
            )
        }
        PackMcpJson.success(
            buildJsonObject {
                put("packId", id.value)
                put("tool", tool)
                put("enabled", enabledValue)
            }
        )
    }

    internal suspend fun setEnabledResult(
        rawId: String?,
        enabled: Boolean?,
    ): CallToolResult = suspendToolCall {
        val id = requirePackId(rawId)
        val enabledValue = enabled ?: return@suspendToolCall PackMcpJson.error(
            code = PackErrorCode.INVALID_REQUEST.wire,
            message = "enabled 必须为布尔值",
        )
        val result = managerProvider().setEnabled(id, enabledValue)
        if (result.isError) {
            return@suspendToolCall PackMcpJson.result(result)
        }
        // Tool add/remove is driven synchronously by the registry snapshot:
        // replaceAll notifies sinks inside the manager's mutation lock, so
        // the disabled pack's tools are already gone. Revoking here would
        // race a concurrent re-enable and drop freshly registered tools.
        val info = managerProvider().info(id)
        PackMcpJson.success(
            if (info == null) {
                buildJsonObject {
                    put("packId", id.value)
                    put("enabled", enabledValue)
                }
            } else {
                buildJsonObject {
                    PackJson.info(info, permissions).forEach { (key, value) -> put(key, value) }
                    put("enabled", enabledValue)
                }
            }
        )
    }

    internal suspend fun uninstallResult(rawId: String?): CallToolResult =
        suspendToolCall {
            val id = requirePackId(rawId)
            val report = managerProvider().uninstall(id)
            permissions.removePack(id)
        val data = PackJson.uninstall(report)
        val failure = report.failure
        when {
            failure != null -> PackMcpJson.diagnostic(failure)
                !report.clean -> PackMcpJson.error(
                    code = PackErrorCode.RESIDUAL_FILES.wire,
                    message = "扩展包卸载后仍有残留或仍需重启",
                    data = data,
                )

                else -> PackMcpJson.success(data)
            }
        }

    private fun requireInfo(rawId: String?): io.legado.pack.api.PackInfo {
        val id = requirePackId(rawId)
        return managerProvider().info(id)
            ?: throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.PACK_NOT_FOUND,
                    message = "扩展包 ${id.value} 未安装",
                )
            )
    }

    private fun requirePackId(rawId: String?): PackId {
        val normalized = rawId?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.INVALID_REQUEST,
                    message = "id 不能为空",
                )
            )
        return PackId.parse(normalized)
            ?: throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.INVALID_REQUEST,
                    message = "扩展包 id 格式无效",
                )
            )
    }

    private fun syncToolCall(block: () -> CallToolResult): CallToolResult =
        try {
            block()
        } catch (timeout: PackTimeoutException) {
            PackMcpJson.diagnostic(timeout.diagnostic)
        } catch (cancelled: PackCancelledException) {
            PackMcpJson.diagnostic(cancelled.diagnostic)
        } catch (failure: PackException) {
            PackMcpJson.diagnostic(failure.diagnostic)
        } catch (failure: Exception) {
            PackMcpJson.error(
                code = PackErrorCode.INTERNAL.wire,
                message = failure.localizedMessage ?: "扩展包管理操作失败",
            )
        }

    private suspend fun suspendToolCall(
        block: suspend () -> CallToolResult,
    ): CallToolResult = try {
        block()
    } catch (timeout: PackTimeoutException) {
        PackMcpJson.diagnostic(timeout.diagnostic)
    } catch (cancelled: PackCancelledException) {
        PackMcpJson.diagnostic(cancelled.diagnostic)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: PackException) {
        PackMcpJson.diagnostic(failure.diagnostic)
    } catch (failure: Exception) {
        PackMcpJson.error(
            code = PackErrorCode.INTERNAL.wire,
            message = failure.localizedMessage ?: "扩展包管理操作失败",
        )
    }

    private fun stringProp(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun JsonObject?.string(key: String): String? =
        this?.get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject?.boolean(key: String): Boolean? =
        this?.get(key)?.jsonPrimitive?.booleanOrNull
}