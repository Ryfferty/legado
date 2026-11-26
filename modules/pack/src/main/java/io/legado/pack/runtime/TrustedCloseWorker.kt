package io.legado.pack.runtime

import io.legado.pack.api.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One shared bounded cleanup worker: uncooperative close must not block a host close budget. */
internal object TrustedCloseWorker {
    private val executor = ThreadPoolExecutor(
        1, 1, 15L, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        { task -> Thread(task, "pack-trusted-close").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    fun submit(action: () -> CloseOutcome): AtomicReference<CloseOutcome?> {
        val result = AtomicReference<CloseOutcome?>()
        try {
            executor.execute {
                result.set(try {
                    action()
                } catch (failure: Throwable) {
                    CloseOutcome.failed(PackDiagnostic(PackErrorCode.NEEDS_RESTART, "trusted close failed: ${failure.javaClass.simpleName}"))
                })
            }
        } catch (_: RejectedExecutionException) {
            result.set(CloseOutcome.resident("bounded trusted-close queue is full; process restart required"))
        }
        return result
    }
}
