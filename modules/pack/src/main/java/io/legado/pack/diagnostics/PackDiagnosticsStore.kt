package io.legado.pack.diagnostics

import io.legado.pack.api.DiagnosticEvent
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackId
import io.legado.pack.api.PackLogLevel
import io.legado.pack.api.PackRef
import java.util.ArrayDeque

/**
 * Bounded, structured diagnostics for the UI and the diagnostics snapshot API.
 *
 * It is deliberately memory-only: pack log files have the 100 MB disk budget;
 * structured events answer "what failed" and vanish with the process.
 */
class PackDiagnosticsStore(
    private val maxGlobalEvents: Int = 2_000,
    private val maxPerPackEvents: Int = 500,
) {

    private val lock = Any()
    private val globalEvents = ArrayDeque<DiagnosticEvent>()
    private val perPack = LinkedHashMap<PackId, ArrayDeque<DiagnosticEvent>>()

    fun record(
        packRef: PackRef?,
        requestId: String?,
        code: PackErrorCode?,
        message: String,
        path: String? = null,
        details: Map<String, String> = emptyMap(),
        level: PackLogLevel? = null,
    ) {
        val sanitizedMessage = Redaction.redactText(message.replace('\n', ' ').replace('\r', ' '))
            .ifBlank { code?.wire ?: "pack diagnostic" }
            .take(8_192)
        val event = DiagnosticEvent(
            timestampEpochMs = System.currentTimeMillis(),
            level = level ?: levelFor(code),
            message = sanitizedMessage,
            code = code,
            packRef = packRef,
            requestId = requestId,
            path = path?.replace('\n', ' ')?.replace('\r', ' ')?.take(MaxPathChars),
            details = Redaction.redactDetails(details).toMap(),
        )
        synchronized(lock) {
            globalEvents.addLast(event)
            while (globalEvents.size > maxGlobalEvents) globalEvents.removeFirst()
            val id = packRef?.id
            if (id != null) {
                val events = perPack.getOrPut(id) { ArrayDeque() }
                events.addLast(event)
                while (events.size > maxPerPackEvents) events.removeFirst()
            }
        }
    }

    fun recordDiagnostic(packRef: PackRef?, requestId: String?, diagnostic: PackDiagnostic) {
        record(
            packRef = packRef,
            requestId = requestId,
            code = diagnostic.code,
            message = diagnostic.message,
            path = diagnostic.path,
            details = diagnostic.details,
        )
    }

    fun snapshot(id: PackId?, limit: Int = 200): List<DiagnosticEvent> {
        if (limit <= 0) return emptyList()
        synchronized(lock) {
            val source = if (id == null) globalEvents else perPack[id] ?: return emptyList()
            val skip = (source.size - limit).coerceAtLeast(0)
            // Defensive copy, mirroring PackLogStore.snapshot: the stored
            // events (and their details maps) must not be mutable from the
            // outside.
            return source.drop(skip).map { it.copy(details = it.details.toMap()) }
        }
    }

    fun onPackRemoved(id: PackId) {
        synchronized(lock) {
            perPack.remove(id)
            val retained = globalEvents.filter { it.packRef?.id != id }
            globalEvents.clear()
            retained.forEach { globalEvents.addLast(it) }
        }
    }

    private fun levelFor(code: PackErrorCode?): PackLogLevel = when {
        code == null -> PackLogLevel.INFO
        code.severity == io.legado.pack.api.PackSeverity.RED_CARD -> PackLogLevel.ERROR
        code.severity == io.legado.pack.api.PackSeverity.WARNING -> PackLogLevel.WARN
        else -> PackLogLevel.INFO
    }

    private companion object {
        private const val MaxPathChars = 256
    }
}