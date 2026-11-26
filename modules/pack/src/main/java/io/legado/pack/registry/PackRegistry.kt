package io.legado.pack.registry

import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import io.legado.pack.api.PackManifest
import io.legado.pack.api.PackRef
import io.legado.pack.api.PackRoutes
import io.legado.pack.api.immutableMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class PackRegistration(
    val ref: PackRef,
    val manifest: PackManifest,
) {
    init {
        require(ref.id == manifest.id) { "PackRef id must match the manifest id" }
    }
}

/**
 * Immutable registration view. [version] increases once per accepted update
 * and is the host-visible admission generation; tool closures/adapters must
 * still verify [PackRef.epoch] on every call.
 */
class PackRegistrationSnapshot(
    val version: Long,
    registrations: Map<PackId, PackRegistration>,
) {
    val registrations: Map<PackId, PackRegistration> = immutableMap(registrations)

    init {
        require(version >= 0L) { "registration snapshot version must be >= 0" }
    }

    val toolNames: Set<String>
        get() = registrations.values.flatMap { it.manifest.mcpTools }.map { it.name }.toSet()

    val routePaths: Set<String>
        get() = registrations.values.flatMap { registration ->
            registration.manifest.apiRoutes.map { route ->
                PackRoutes.apiPath(registration.ref.id, route.name)
            }
        }.toSet()

    fun copy(
        version: Long = this.version,
        registrations: Map<PackId, PackRegistration> = this.registrations,
    ): PackRegistrationSnapshot = PackRegistrationSnapshot(version, registrations)

    override fun equals(other: Any?): Boolean =
        other is PackRegistrationSnapshot &&
            version == other.version &&
            registrations == other.registrations

    override fun hashCode(): Int = 31 * version.hashCode() + registrations.hashCode()

    override fun toString(): String =
        "PackRegistrationSnapshot(version=$version, packs=${registrations.size})"
}

/**
 * Metadata-only registration sink. `onSnapshot` must update in-memory
 * registrations quickly and must not execute package code, perform network
 * I/O, wait on another lock, or call back into the registry.
 */
fun interface PackRegistrationSink {
    fun onSnapshot(snapshot: PackRegistrationSnapshot)
}

sealed interface RegistrationResult {
    data class Applied(
        val snapshot: PackRegistrationSnapshot,
        val sinkFailures: List<PackDiagnostic> = emptyList(),
    ) : RegistrationResult

    data class Rejected(
        val diagnostics: List<PackDiagnostic>,
        val current: PackRegistrationSnapshot,
    ) : RegistrationResult {
        init {
            require(diagnostics.isNotEmpty()) { "Rejected result must contain diagnostics" }
        }
    }
}

/**
 * In-memory registration coordinator for MCP tools and Web routes.
 *
 * `replaceAll` validates the complete candidate set first and publishes it in
 * one step; a conflicting pack therefore fails as a whole without overwriting
 * the currently registered tools. `attach` installs the current snapshot and
 * subscribes to later updates while holding the same lock, leaving no window
 * between the base snapshot and the first incremental update.
 *
 * The registry never loads or invokes package code.
 */
class PackRegistry(
    reservedToolNames: Set<String> = emptySet(),
) : AutoCloseable {

    private val reservedToolNames: Set<String> = reservedToolNames.toSet()
    private val lock = ReentrantLock()
    private val sinks = LinkedHashMap<Long, PackRegistrationSink>()
    private var nextSinkId = 0L
    private var current = PackRegistrationSnapshot(0L, emptyMap())
    private var closed = false
    private var notificationDepth = 0

    fun snapshot(): PackRegistrationSnapshot = lock.withLock { current }

    fun attach(sink: PackRegistrationSink): AutoCloseable = lock.withLock {
        ensureOpen()
        val sinkId = ++nextSinkId
        sinks[sinkId] = sink
        try {
            notificationDepth++
            try {
                sink.onSnapshot(current)
            } finally {
                notificationDepth--
            }
        } catch (e: Exception) {
            sinks.remove(sinkId)
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.INTERNAL,
                    message = "registration sink failed during attach",
                ),
                e,
            )
        }
        AutoCloseable {
            lock.withLock { sinks.remove(sinkId) }
            Unit
        }
    }

    /**
     * Atomically replaces the full registration set. The current snapshot is
     * kept when any diagnostic is returned.
     */
    fun replaceAll(registrations: Collection<PackRegistration>): RegistrationResult =
        lock.withLock {
            ensureOpen()
            check(notificationDepth == 0) { "a registration sink must not mutate the registry" }
            val candidate = registrations.toList()
            val diagnostics = validate(candidate)
            if (diagnostics.isNotEmpty()) {
                return@withLock RegistrationResult.Rejected(diagnostics, current)
            }
            val ordered = candidate
                .sortedBy { it.ref.id.value }
                .associateBy { it.ref.id }
            val next = PackRegistrationSnapshot(
                version = current.version + 1L,
                registrations = ordered,
            )
            current = next
            RegistrationResult.Applied(next, notifySinks(next))
        }

    override fun close() {
        lock.withLock {
            if (!closed) {
                closed = true
                sinks.clear()
            }
        }
    }

    private fun ensureOpen() {
        if (closed) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.INTERNAL,
                    message = "pack registry is closed",
                ),
            )
        }
    }

    private fun validate(registrations: Collection<PackRegistration>): List<PackDiagnostic> {
        val diagnostics = mutableListOf<PackDiagnostic>()
        val byId = LinkedHashMap<PackId, PackRegistration>()
        registrations.forEach { registration ->
            if (registration.ref.id != registration.manifest.id) {
                diagnostics += PackDiagnostic(
                    code = PackErrorCode.REGISTRATION_CONFLICT,
                    message = "registration id ${registration.ref.id.value} does not match manifest id",
                    path = "registration.${registration.ref.id.value}",
                )
            }
            val previous = byId[registration.ref.id]
            if (previous == null) {
                byId[registration.ref.id] = registration
            } else {
                diagnostics += PackDiagnostic(
                    code = PackErrorCode.REGISTRATION_CONFLICT,
                    message = "duplicate pack registration '${registration.ref.id.value}'",
                    path = "registration.${registration.ref.id.value}",
                )
            }
        }

        val toolOwners = LinkedHashMap<String, PackId>()
        val routeOwners = LinkedHashMap<String, PackId>()
        byId.values.sortedBy { it.ref.id.value }.forEach { registration ->
            registration.manifest.mcpTools.forEach { tool ->
                if (tool.name in reservedToolNames) {
                    diagnostics += PackDiagnostic(
                        code = PackErrorCode.TOOL_CONFLICT,
                        message = "tool '${tool.name}' is reserved by the host",
                        path = "pack.${registration.ref.id.value}.mcpTools.${tool.name}",
                        details = mapOf("tool" to tool.name),
                    )
                }
                val owner = toolOwners[tool.name]
                if (owner == null) {
                    toolOwners[tool.name] = registration.ref.id
                } else {
                    diagnostics += PackDiagnostic(
                        code = PackErrorCode.TOOL_CONFLICT,
                        message = "tool '${tool.name}' is already registered by '${owner.value}'",
                        path = "pack.${registration.ref.id.value}.mcpTools.${tool.name}",
                        details = mapOf(
                            "tool" to tool.name,
                            "owner" to owner.value,
                            "candidate" to registration.ref.id.value,
                        ),
                    )
                }
            }
            registration.manifest.apiRoutes.forEach { route ->
                val path = PackRoutes.apiPath(registration.ref.id, route.name)
                val owner = routeOwners[path]
                if (owner == null) {
                    routeOwners[path] = registration.ref.id
                } else {
                    diagnostics += PackDiagnostic(
                        code = PackErrorCode.ROUTE_CONFLICT,
                        message = "route '$path' is already registered by '${owner.value}'",
                        path = "pack.${registration.ref.id.value}.apiRoutes.${route.name}",
                        details = mapOf("route" to path, "owner" to owner.value),
                    )
                }
            }
        }
        return diagnostics.distinct()
    }

    private fun notifySinks(snapshot: PackRegistrationSnapshot): List<PackDiagnostic> {
        val failures = mutableListOf<PackDiagnostic>()
        notificationDepth++
        try {
            sinks.entries.toList().forEach { (sinkId, sink) ->
                try {
                    sink.onSnapshot(snapshot)
                } catch (e: Exception) {
                    failures += PackDiagnostic(
                        code = PackErrorCode.INTERNAL,
                        message = "registration sink $sinkId failed",
                        details = mapOf("sink" to sinkId.toString()),
                    )
                }
            }
        } finally {
            notificationDepth--
        }
        return failures
    }
}