package io.legado.pack.api

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

enum class PackCallerKind {
    SOURCE,
    MCP,
    WEB,
    SELF_TEST,
    HOST,
}

/**
 * Who initiated a pack call. Constructed by the host, never by package code.
 * The source key is host-derived so pack KV namespaces cannot be forged by a
 * book-source script.
 */
sealed class PackCaller {

    abstract val kind: PackCallerKind
    abstract val subject: String?

    data class Source(
        val sourceKey: String,
        val sourceName: String? = null,
    ) : PackCaller() {
        override val kind: PackCallerKind get() = PackCallerKind.SOURCE
        override val subject: String get() = sourceKey

        init {
            require(sourceKey.isNotBlank()) { "sourceKey must not be blank" }
            require(sourceKey.length <= 256) { "sourceKey is too long" }
        }
    }

    data class Mcp(val clientId: String? = null) : PackCaller() {
        override val kind: PackCallerKind get() = PackCallerKind.MCP
        override val subject: String? get() = clientId
    }

    data class Web(val remoteAddress: String? = null) : PackCaller() {
        override val kind: PackCallerKind get() = PackCallerKind.WEB
        override val subject: String? get() = remoteAddress
    }

    data object SelfTest : PackCaller() {
        override val kind: PackCallerKind get() = PackCallerKind.SELF_TEST
        override val subject: String? get() = null
    }

    data object Host : PackCaller() {
        override val kind: PackCallerKind get() = PackCallerKind.HOST
        override val subject: String? get() = null
    }
}

/**
 * Thread-safe cooperative cancellation signal.
 *
 * Cancellation is advisory: JS instruction observers and HTTP calls can react
 * immediately, while DEX/native code must poll [isCancelled] or the call
 * context. It must never be described as a hard thread/process kill.
 */
class CancellationSignal(initialReason: String? = null) {

    private val reasonRef = AtomicReference<String?>(initialReason?.also {
        require(it.isNotBlank()) { "cancellation reason must not be blank" }
    })
    private val callbacks: MutableSet<() -> Unit> =
        Collections.newSetFromMap(ConcurrentHashMap<() -> Unit, Boolean>())

    val isCancelled: Boolean get() = reasonRef.get() != null
    val reason: String? get() = reasonRef.get()

    /** Returns true only for the first cancellation. */
    fun cancel(reason: String): Boolean {
        require(reason.isNotBlank()) { "cancellation reason must not be blank" }
        if (!reasonRef.compareAndSet(null, reason)) return false
        callbacks.toList().forEach(::invokeOnce)
        return true
    }

    /**
     * Registers a callback and returns a handle that removes it. If the signal
     * is already cancelled, the action runs before this method returns.
     * Exceptions from user callbacks are ignored so cancellation cannot be
     * blocked by a broken observer.
     */
    fun onCancel(action: () -> Unit): AutoCloseable {
        callbacks.add(action)
        if (isCancelled) invokeOnce(action)
        return AutoCloseable {
            callbacks.remove(action)
            Unit
        }
    }

    private fun invokeOnce(action: () -> Unit) {
        if (callbacks.remove(action)) {
            try {
                action()
            } catch (_: Exception) {
                // Cancellation observers are diagnostics/cleanup hooks. A failing
                // observer must not prevent other observers or the caller from
                // observing cancellation.
            }
        }
    }
}

/**
 * Per-call context passed to every package entry point and primitive.
 *
 * [deadlineNanos] is an absolute value on the host's monotonic `nanoTime`
 * clock. Implementations must check [checkActive] or [failureOrNull] at
 * initialization, before/after user code and before returning a result.
 * Native ABI 1 must expose this context as a `pack_call_v1` with thread-safe
 * check_active/now_nanos and retain/release lifetime; it must not create a
 * second deadline or use wall-clock time.
 */
class PackCallContext(
    val requestId: String,
    val packRef: PackRef,
    val caller: PackCaller,
    val deadlineNanos: Long,
    val cancellation: CancellationSignal = CancellationSignal(),
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(requestId.length <= 128) { "requestId is too long" }
        require(requestId.none { it.isISOControl() }) { "requestId contains control characters" }
    }

    fun remainingNanos(nowNanos: Long = System.nanoTime()): Long {
        val remaining = deadlineNanos - nowNanos
        return if (remaining > 0L) remaining else 0L
    }

    fun remainingMillis(nowNanos: Long = System.nanoTime()): Long = remainingNanos(nowNanos) / 1_000_000L

    fun isExpired(nowNanos: Long = System.nanoTime()): Boolean = nowNanos - deadlineNanos >= 0L

    /** Throws [PackCancelledException] or [PackTimeoutException]. */
    fun checkActive(nowNanos: Long = System.nanoTime()) {
        cancellation.reason?.let { throw PackCancelledException(it) }
        if (isExpired(nowNanos)) {
            throw PackTimeoutException(requestId, null)
        }
    }

    /**
     * Non-throwing form for runtimes that need to map the state into a
     * structured [PackResult]. Cancellation takes precedence over timeout.
     */
    fun failureOrNull(nowNanos: Long = System.nanoTime()): PackDiagnostic? {
        cancellation.reason?.let {
            return PackDiagnostic(
                code = PackErrorCode.CANCELLED,
                message = "pack call cancelled: $it",
                details = mapOf("requestId" to requestId),
            )
        }
        if (isExpired(nowNanos)) {
            return PackDiagnostic(
                code = PackErrorCode.TIMEOUT,
                message = "pack call timed out: $requestId",
                details = mapOf("requestId" to requestId),
            )
        }
        return null
    }

    companion object {
        fun after(
            requestId: String,
            packRef: PackRef,
            caller: PackCaller,
            timeoutMs: Long,
            cancellation: CancellationSignal = CancellationSignal(),
            nowNanos: Long = System.nanoTime(),
        ): PackCallContext {
            require(timeoutMs > 0L) { "timeoutMs must be positive" }
            require(timeoutMs <= Long.MAX_VALUE / 1_000_000L) { "timeoutMs is too large" }
            return PackCallContext(
                requestId = requestId,
                packRef = packRef,
                caller = caller,
                deadlineNanos = nowNanos + timeoutMs * 1_000_000L,
                cancellation = cancellation,
            )
        }
    }
}

/**
 * Bounded execution policy for one pack worker.
 *
 * A dedicated single worker with a bounded queue is required; a stuck call
 * must not cause unbounded thread creation. `Thread.interrupt`/coroutine
 * cancellation are advisory and cannot prove that native code stopped.
 */
data class PackExecutionPolicy(
    val defaultTimeoutMs: Long = 10_000L,
    val hardTimeoutMs: Long = 30_000L,
    val queueCapacity: Int = 1,
    val closeTimeoutMs: Long = 2_000L,
) {
    init {
        require(defaultTimeoutMs in 1L..hardTimeoutMs) {
            "defaultTimeoutMs must be between 1 and hardTimeoutMs"
        }
        require(hardTimeoutMs in 1L..600_000L) { "hardTimeoutMs must be between 1 and 600000" }
        require(queueCapacity in 1..64) { "queueCapacity must be between 1 and 64" }
        require(closeTimeoutMs in 1L..hardTimeoutMs) {
            "closeTimeoutMs must be between 1 and hardTimeoutMs"
        }
    }

    /**
     * A requested timeout is capped by [hardTimeoutMs]. Values <= 0 are
     * rejected instead of being silently coerced.
     */
    fun timeoutFor(requestedMs: Long?): Long {
        if (requestedMs == null) return defaultTimeoutMs
        require(requestedMs > 0L) { "requested timeout must be positive" }
        return requestedMs.coerceAtMost(hardTimeoutMs)
    }
}