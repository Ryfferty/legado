package io.legado.pack.primitives

import io.legado.pack.api.PackId
import io.legado.pack.api.PackLogLevel
import io.legado.pack.api.PackLogger
import io.legado.pack.api.PackRef
import io.legado.pack.diagnostics.PackLogStore
import io.legado.pack.diagnostics.PackMetricsTracker

internal class PackLoggerImpl(
    private val store: PackLogStore,
    private val id: PackId,
    private val ref: PackRef,
    private val guard: PackEpochGuard,
    private val tracker: PackMetricsTracker,
) : PackLogger {

    override fun log(level: PackLogLevel, message: String, details: Map<String, String>) {
        guard.ensureActive()
        if (ref.id != id) {
            throw io.legado.pack.api.PackCancelledException("pack logger revision mismatch")
        }
        val bytes = store.log(id, level, message, details)
        tracker.addLogBytes(bytes)
    }
}