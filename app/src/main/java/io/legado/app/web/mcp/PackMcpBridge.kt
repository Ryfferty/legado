package io.legado.app.web.mcp

import io.legado.app.help.pack.HostMcpToolNames
import io.legado.app.help.pack.PackMcpSchema
import io.legado.app.help.pack.PackToolPermissionStore
import io.legado.app.utils.printOnDebug
import io.legado.pack.api.CancellationSignal
import io.legado.pack.api.PackCaller
import io.legado.pack.api.PackCancelledException
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackEntryPoint
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import io.legado.pack.api.PackInvocation
import io.legado.pack.api.PackManager
import io.legado.pack.api.PackTimeoutException
import io.legado.pack.api.ToolSpec
import io.legado.pack.registry.PackRegistration
import io.legado.pack.registry.PackRegistrationSink
import io.legado.pack.registry.PackRegistrationSnapshot
import io.legado.pack.registry.PackRegistry
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Projects the registry's pack tool declarations onto one live MCP [Server].
 *
 * The server is shared by every MCP session. With `tools.listChanged = true`
 * the SDK's own registry/notification service pushes
 * `notifications/tools/list_changed` to all currently connected sessions when
 * a tool is added or removed; no client refresh is required. Every handler
 * captures the [PackRegistration] it was registered for and the manager
 * re-checks that exact `PackRef`/epoch atomically before enqueueing, so a stale
 * closure cannot dispatch to a newer revision.
 */
internal class PackMcpBridge(
    private val server: Server,
    private val managerProvider: () -> PackManager,
    private val permissions: PackToolPermissionStore,
    private val reservedNames: Set<String> = HostMcpToolNames.RESERVED,
) : PackRegistrationSink, AutoCloseable {

    private data class DesiredTool(
        val registration: PackRegistration,
        val spec: ToolSpec,
        val schema: ToolSchema,
    )

    private data class InstalledTool(
        val registration: PackRegistration,
        val spec: ToolSpec,
        val schema: ToolSchema,
    )

    private val lock = Any()
    private val reservedLower: Set<String> =
        reservedNames.map { it.lowercase(Locale.ROOT) }.toHashSet()
    private val installed = LinkedHashMap<String, InstalledTool>()
    private val warnings = LinkedHashMap<String, List<PackDiagnostic>>()
    private val knownPackIds = LinkedHashSet<PackId>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var registrationHandle: AutoCloseable? = null
    private var closed = false

    fun attach(registry: PackRegistry) {
        val handle = registry.attach(this)
        synchronized(lock) {
            registrationHandle?.close()
            registrationHandle = handle
            if (closed) {
                registrationHandle = null
                handle.close()
            }
        }
    }

    override fun onSnapshot(snapshot: PackRegistrationSnapshot) {
        synchronized(lock) {
            if (closed) return
            val desired = LinkedHashMap<String, DesiredTool>()
            val nextWarnings = LinkedHashMap<String, MutableList<PackDiagnostic>>()

            snapshot.registrations.values
                .sortedBy { it.ref.id.value }
                .forEach { registration ->
                    registration.manifest.mcpTools.forEach { spec ->
                        val packKey = registration.ref.id.value
                        when {
                            HostMcpToolNames.isReserved(spec.name) ||
                            spec.name.lowercase(Locale.ROOT) in reservedLower -> {
                                nextWarnings.add(
                                    packKey,
                                    PackDiagnostic(
                                        code = PackErrorCode.TOOL_CONFLICT,
                                        message = "tool '${spec.name}' is reserved by the host",
                                        path = "pack.$packKey.mcpTools.${spec.name}",
                                    ),
                                )
                            }

                            spec.name in desired -> {
                                nextWarnings.add(
                                    packKey,
                                    PackDiagnostic(
                                        code = PackErrorCode.TOOL_CONFLICT,
                                        message = "tool '${spec.name}' is already provided by another pack",
                                        path = "pack.$packKey.mcpTools.${spec.name}",
                                    ),
                                )
                            }

                            else -> {
                                val schema = PackMcpSchema.toToolSchema(spec.inputSchema)
                                if (schema == null) {
                                    nextWarnings.add(
                                        packKey,
                                        PackDiagnostic(
                                            code = PackErrorCode.UNSUPPORTED,
                                            message = "tool '${spec.name}' input schema uses " +
                                                "top-level keywords the MCP SDK cannot represent: " +
                                                PackMcpSchema.unsupportedKeywords(spec.inputSchema)
                                                    .joinToString(", "),
                                            path = "pack.$packKey.mcpTools.${spec.name}.inputSchema",
                                            details = mapOf(
                                                "tool" to spec.name,
                                                "keywords" to PackMcpSchema
                                                    .unsupportedKeywords(spec.inputSchema)
                                                    .joinToString(","),
                                            ),
                                        ),
                                    )
                                } else {
                                    desired[spec.name] = DesiredTool(registration, spec, schema)
                                }
                            }
                        }
                    }
                }

            val toRemove = installed.filter { (name, current) ->
                val next = desired[name]
                next == null ||
                    next.registration.ref != current.registration.ref ||
                    next.spec != current.spec ||
                    next.schema != current.schema
            }
            toRemove.keys.forEach { name ->
                removeInstalledLocked(name)
            }

            desired.forEach { (name, desiredTool) ->
                if (name !in installed) {
                    installLocked(name, desiredTool, nextWarnings)
                }
            }

            warnings.clear()
            nextWarnings.forEach { (packKey, diagnostics) ->
                warnings[packKey] = diagnostics.distinct()
            }

            val currentPackIds = snapshot.registrations.keys
            val disappeared = knownPackIds.filter { it !in currentPackIds }
            knownPackIds.clear()
            knownPackIds.addAll(currentPackIds)
            if (disappeared.isNotEmpty()) {
                // Disable/uninstall revokes host write grants as well as MCP
                // registrations. File IO runs on a worker after the registry
                // sink has returned.
                cleanupScope.launch {
                    disappeared.forEach { id ->
                        runCatching { permissions.removePack(id) }
                            .onFailure { it.printOnDebug() }
                    }
                }
            }
        }
    }

    fun warningsFor(id: PackId): List<PackDiagnostic> = synchronized(lock) {
        warnings[id.value].orEmpty()
    }

    fun warningsSnapshot(): Map<String, List<PackDiagnostic>> = synchronized(lock) {
        warnings.mapValues { it.value.toList() }
    }

    /**
     * Immediate revocation used by management operations. Epoch checks in the
     * manager remain the final authority; removing the MCP entry also makes
     * the tool disappear from `tools/list` right away.
     */
    fun revoke(id: PackId) {
        synchronized(lock) {
            installed.entries
                .filter { it.value.registration.ref.id == id }
                .map { it.key }
                .forEach(::removeInstalledLocked)
            warnings.remove(id.value)
        }
    }

    internal suspend fun invoke(
        registration: PackRegistration,
        spec: ToolSpec,
        argsJson: String,
        clientId: String?,
        cancellation: CancellationSignal = CancellationSignal(),
    ): CallToolResult {
        if (
            spec.isWrite &&
            !permissions.isWriteToolEnabled(registration.ref, registration.manifest, spec.name)
        ) {
            return PackMcpJson.error(
                code = PackErrorCode.PERMISSION_DENIED.wire,
                message = "写入工具 ${spec.name} 默认关闭，需先在管理页或显式授权中启用",
            )
        }
        return try {
            val result = managerProvider().invoke(
                PackInvocation(
                    expectedRef = registration.ref,
                    entry = PackEntryPoint.McpTool(spec.name),
                    argJson = argsJson,
                    caller = PackCaller.Mcp(clientId),
                    cancellation = cancellation,
                )
            )
            PackMcpJson.result(result)
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
                message = failure.localizedMessage ?: "扩展包工具调用失败",
            )
        }
    }

    override fun close() {
        val handle = synchronized(lock) {
            if (closed) return
            closed = true
            installed.keys.toList().forEach(::removeInstalledLocked)
            warnings.clear()
            knownPackIds.clear()
            cleanupScope.cancel()
            val current = registrationHandle
            registrationHandle = null
            current
        }
        handle?.close()
    }

    private suspend fun ClientConnection.handleTool(
        registration: PackRegistration,
        spec: ToolSpec,
        request: CallToolRequest,
    ): CallToolResult {
        val cancellation = CancellationSignal()
        val job = currentCoroutineContext()[Job]
        val handle = job?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                cancellation.cancel("MCP request cancelled")
            }
        }
        return try {
            invoke(
                registration = registration,
                spec = spec,
                argsJson = request.arguments?.toString() ?: "{}",
                clientId = sessionId,
                cancellation = cancellation,
            )
        } finally {
            handle?.dispose()
        }
    }

    private fun installLocked(
        name: String,
        desired: DesiredTool,
        nextWarnings: MutableMap<String, MutableList<PackDiagnostic>>,
    ) {
        val packKey = desired.registration.ref.id.value
        val tool = Tool(
            name = name,
            description = describe(desired),
            inputSchema = desired.schema,
            title = name,
            annotations = ToolAnnotations(
                readOnlyHint = !desired.spec.isWrite,
                destructiveHint = desired.spec.isWrite,
                idempotentHint = !desired.spec.isWrite,
                openWorldHint = true,
            ),
            meta = buildJsonObject {
                put("io.legado.pack/id", packKey)
                put("io.legado.pack/revision", desired.registration.ref.revision.value)
                put("io.legado.pack/epoch", desired.registration.ref.epoch)
                put("io.legado.pack/tool", name)
                put("io.legado.pack/declaredWrite", desired.spec.isWrite)
            },
        )
        val handler: suspend ClientConnection.(CallToolRequest) -> CallToolResult = {
            handleTool(desired.registration, desired.spec, it)
        }
        try {
            server.addTool(tool, handler)
            installed[name] = InstalledTool(
                registration = desired.registration,
                spec = desired.spec,
                schema = desired.schema,
            )
        } catch (first: Exception) {
            // A stale same-name entry can exist after a partially failed
            // update. Remove only that pack-owned name and retry once.
            server.removeTool(name)
            try {
                server.addTool(tool, handler)
                installed[name] = InstalledTool(
                    registration = desired.registration,
                    spec = desired.spec,
                    schema = desired.schema,
                )
            } catch (second: Exception) {
                try {
                    server.removeTool(name)
                } catch (_: Exception) {
                    // Best effort; the server may be shutting down.
                }
                nextWarnings.add(
                    packKey,
                    PackDiagnostic(
                        code = PackErrorCode.INTERNAL,
                        message = "failed to register MCP tool '$name': " +
                            (second.localizedMessage ?: first.localizedMessage ?: "unknown error"),
                        path = "pack.$packKey.mcpTools.$name",
                    ),
                )
            }
        }
    }

    private fun removeInstalledLocked(name: String) {
        installed.remove(name)
        try {
            server.removeTool(name)
        } catch (_: Exception) {
            // The server may already be closed during process shutdown.
        }
    }

    private fun describe(desired: DesiredTool): String {
        val description = desired.spec.description
            .ifBlank { "扩展包 ${desired.registration.manifest.name} 提供的工具" }
        val suffix = "（扩展包 ${desired.registration.ref.id.value}）"
        return (description + suffix).take(1024)
    }

    private fun MutableMap<String, MutableList<PackDiagnostic>>.add(
        packKey: String,
        diagnostic: PackDiagnostic,
    ) {
        getOrPut(packKey) { mutableListOf<PackDiagnostic>() } += diagnostic
    }
}