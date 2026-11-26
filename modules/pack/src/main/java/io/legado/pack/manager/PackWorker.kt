package io.legado.pack.manager

import io.legado.pack.api.PackExecutionPolicy
import io.legado.pack.api.PackId
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Exactly one daemon worker per pack with a bounded queue.
 *
 * Unlike FutureTask cancellation, this worker distinguishes "still queued" from
 * "already running": a timed-out queued call can be removed without leaking a
 * pending count, while a running call is only declared stopped when its
 * invocation actually returned. That distinction decides between TIMEOUT and
 * the NEEDS_RESTART quarantine.
 */
internal class PackWorker(
    private val packId: PackId,
    private val policy: PackExecutionPolicy,
) : AutoCloseable {

    private val lock = Object()
    private val queue = java.util.ArrayDeque<Job>()
    private var pending = 0
    private var closed = false
    private val threadRef = AtomicReference<Thread?>()
    private val threadName = "pack-worker-${packId.value}"
    private var lastActivityNanos = System.nanoTime()

    fun <T> submit(task: () -> T): JobHandle<T> {
        val handle = JobHandle<T>(task)
        synchronized(lock) {
            if (closed) throw RejectedExecutionException("pack worker is closed")
            if (queue.size >= policy.queueCapacity) {
                throw RejectedExecutionException("pack worker queue is full")
            }
            queue.addLast(handle.job)
            pending++
            ensureThreadLocked()
            lock.notifyAll()
        }
        return handle
    }

    fun isWorkerThread(): Boolean = Thread.currentThread().name == threadName

    fun hasPendingCalls(): Boolean = synchronized(lock) { pending > 0 }

    fun awaitIdle(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        synchronized(lock) {
            while (pending > 0) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return pending == 0
                lock.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
            }
            return true
        }
    }

    fun shutdownBounded(timeoutMs: Long): Boolean {
        var thread: Thread? = null
        synchronized(lock) {
            if (closed) {
                thread = threadRef.get()
            } else {
                closed = true
                thread = threadRef.get()
                val iterator = queue.iterator()
                while (iterator.hasNext()) {
                    val job = iterator.next()
                    iterator.remove()
                    job.cancelFromQueue()
                    pending--
                }
                lock.notifyAll()
            }
        }
        thread?.interrupt()
        val workerThread = thread ?: return true
        return try {
            workerThread.join(timeoutMs)
            !workerThread.isAlive
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    override fun close() {
        shutdownBounded(policy.closeTimeoutMs)
    }

    private fun ensureThreadLocked() {
        val existing = threadRef.get()
        if (existing != null && existing.isAlive) return
        val thread = Thread({ runLoop() }, threadName).apply {
            isDaemon = true
            threadRef.set(this)
        }
        lastActivityNanos = System.nanoTime()
        thread.start()
    }

    private fun takeJob(): Job? {
        synchronized(lock) {
            while (true) {
                val next = queue.pollFirst()
                if (next != null) {
                    next.started = true
                    lastActivityNanos = System.nanoTime()
                    return next
                }
                val idleNanos = System.nanoTime() - lastActivityNanos
                val keepAliveNanos = TimeUnit.SECONDS.toNanos(IdleKeepAliveSeconds)
                if (closed || idleNanos >= keepAliveNanos) {
                    threadRef.compareAndSet(Thread.currentThread(), null)
                    return null
                }
                val waitNanos = keepAliveNanos - idleNanos
                try {
                    lock.wait(waitNanos / 1_000_000L, (waitNanos % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                    Thread.interrupted()
                    if (closed) {
                        threadRef.compareAndSet(Thread.currentThread(), null)
                        return null
                    }
                }
            }
        }
    }

    private fun runLoop() {
        while (true) {
            val running = takeJob() ?: return
            try {
                val value = running.execute()
                synchronized(lock) {
                    running.complete(value)
                    pending--
                    lastActivityNanos = System.nanoTime()
                    lock.notifyAll()
                }
            } catch (t: Throwable) {
                synchronized(lock) {
                    running.fail(t)
                    pending--
                    lastActivityNanos = System.nanoTime()
                    lock.notifyAll()
                }
            }
            if (Thread.interrupted()) {
                synchronized(lock) {
                    if (closed && queue.isEmpty()) {
                        threadRef.compareAndSet(Thread.currentThread(), null)
                        return
                    }
                }
            }
        }
    }

    internal class Job(val task: () -> Any?) {
        @Volatile
        var started = false
        private var done = false
        private var cancelled = false
        private var result: Any? = null
        private var failure: Throwable? = null
        private val monitor = Object()

        fun execute(): Any? = task()

        fun complete(value: Any?) {
            synchronized(monitor) {
                if (cancelled) return
                result = value
                done = true
                monitor.notifyAll()
            }
        }

        fun fail(error: Throwable) {
            synchronized(monitor) {
                if (cancelled) return
                failure = error
                done = true
                monitor.notifyAll()
            }
        }

        fun cancelFromQueue() {
            synchronized(monitor) {
                cancelled = true
                done = true
                monitor.notifyAll()
            }
        }

        fun isStarted(): Boolean = synchronized(monitor) { started }

        fun isDone(): Boolean = synchronized(monitor) { done }

        fun awaitDone(timeoutMs: Long): Boolean {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            synchronized(monitor) {
                while (!done) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) return done
                    monitor.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
                }
                return true
            }
        }

        fun getResult(): Any? {
            synchronized(monitor) {
                if (cancelled) throw CancellationException("pack job was cancelled")
                if (!done) throw IllegalStateException("pack job is not done")
                failure?.let { throw ExecutionException(it) }
                return result
            }
        }
    }

    inner class JobHandle<T>(task: () -> T) : Future<T> {
        internal val job = Job { task() }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            var removed: Job? = null
            synchronized(lock) {
                if (job.isStarted()) {
                    if (mayInterruptIfRunning) threadRef.get()?.interrupt()
                    return false
                }
                if (queue.remove(job)) {
                    pending--
                    lock.notifyAll()
                    removed = job
                }
            }
            val queued = removed
            if (queued != null) {
                queued.cancelFromQueue()
                return true
            }
            return false
        }

        override fun isCancelled(): Boolean =
            job.isDone() &&
                (runCatching { job.getResult() }.exceptionOrNull() is CancellationException)

        override fun isDone(): Boolean = job.isDone()

        override fun get(): T = getResultTyped()

        override fun get(timeout: Long, unit: TimeUnit): T {
            if (!job.awaitDone(unit.toMillis(timeout))) {
                throw TimeoutException("pack job did not finish in time")
            }
            return getResultTyped()
        }

        @Suppress("UNCHECKED_CAST")
        private fun getResultTyped(): T = job.getResult() as T

        /**
         * Removes the job if it is still queued. Returns true when it never
         * started, so the caller can report TIMEOUT without quarantine.
         */
        fun cancelIfQueued(): Boolean = cancel(false)

        fun awaitStopped(timeoutMs: Long): Boolean = job.awaitDone(timeoutMs)

        fun interruptWorker() {
            if (job.isStarted() && !job.isDone()) threadRef.get()?.interrupt()
        }
    }

    companion object {
        private const val IdleKeepAliveSeconds = 30L
    }
}