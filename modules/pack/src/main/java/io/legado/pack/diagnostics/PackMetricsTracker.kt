package io.legado.pack.diagnostics

import io.legado.pack.api.PackMetrics
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-pack counters for metrics mediated by framework primitives.
 *
 * These are lower bounds: trusted DEX/native code can open files/sockets
 * directly. The manager adds that caveat to reports for such packs.
 */
public class PackMetricsTracker {

    private val elapsedMs = AtomicLong()
    private val networkBytesIn = AtomicLong()
    private val networkBytesOut = AtomicLong()
    private val logBytes = AtomicLong()

    fun addElapsed(ms: Long) {
        if (ms > 0L) elapsedMs.addAndGet(ms)
    }

    fun addNetworkIn(bytes: Long) {
        if (bytes > 0L) networkBytesIn.addAndGet(bytes)
    }

    fun addNetworkOut(bytes: Long) {
        if (bytes > 0L) networkBytesOut.addAndGet(bytes)
    }

    fun addLogBytes(bytes: Long) {
        if (bytes > 0L) logBytes.addAndGet(bytes)
    }

    fun snapshot(notes: List<String> = emptyList()): PackMetrics = PackMetrics(
        elapsedMs = elapsedMs.get().coerceAtLeast(0L),
        workerCpuMs = null,
        networkBytesIn = networkBytesIn.get().coerceAtLeast(0L),
        networkBytesOut = networkBytesOut.get().coerceAtLeast(0L),
        logBytes = logBytes.get().coerceAtLeast(0L),
        notes = notes,
    )

    companion object {
        fun delta(before: PackMetrics, after: PackMetrics): PackMetrics = PackMetrics(
            elapsedMs = (after.elapsedMs - before.elapsedMs).coerceAtLeast(0L),
            workerCpuMs = null,
            networkBytesIn = (after.networkBytesIn - before.networkBytesIn).coerceAtLeast(0L),
            networkBytesOut = (after.networkBytesOut - before.networkBytesOut).coerceAtLeast(0L),
            logBytes = (after.logBytes - before.logBytes).coerceAtLeast(0L),
            notes = after.notes,
        )
    }
}