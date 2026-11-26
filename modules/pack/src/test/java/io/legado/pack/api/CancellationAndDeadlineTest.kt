package io.legado.pack.api

import io.legado.pack.TestPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellationAndDeadlineTest {

    private val ref = TestPacks.ref("hello-js")

    @Test
    fun `cancellation signal is idempotent and notifies once`() {
        val signal = CancellationSignal()
        var calls = 0
        signal.onCancel { calls++ }

        assertTrue(signal.cancel("user stopped"))
        assertFalse(signal.cancel("again"))
        assertEquals(1, calls)
        assertTrue(signal.isCancelled)
        assertEquals("user stopped", signal.reason)

        signal.onCancel { calls++ }
        assertEquals(2, calls)
    }

    @Test
    fun `late cancellation observer fires immediately and only once`() {
        val signal = CancellationSignal("already")
        var calls = 0
        val handle = signal.onCancel { calls++ }

        assertEquals(1, calls)
        handle.close()
        assertEquals(1, calls)
    }

    @Test
    fun `a failing observer does not block other observers`() {
        val signal = CancellationSignal()
        var calls = 0
        signal.onCancel { throw IllegalStateException("observer failure") }
        signal.onCancel { calls++ }

        signal.cancel("stop")
        assertEquals(1, calls)
    }

    @Test
    fun `call context enforces a monotonic deadline`() {
        val context = PackCallContext.after(
            requestId = "req-1",
            packRef = ref,
            caller = PackCaller.SelfTest,
            timeoutMs = 1_000L,
            nowNanos = 0L,
        )

        assertEquals(1_000_000_000L, context.remainingNanos(0L))
        assertFalse(context.isExpired(999_999_999L))
        assertTrue(context.isExpired(1_000_000_000L))
        assertEquals(0L, context.remainingMillis(1_000_000_000L))
        assertNull(context.failureOrNull(0L))
        assertEquals(PackErrorCode.TIMEOUT, context.failureOrNull(1_000_000_000L)?.code)
        assertThrows(PackTimeoutException::class.java) {
            context.checkActive(1_000_000_000L)
        }
    }

    @Test
    fun `cancellation takes precedence over an expired deadline`() {
        val signal = CancellationSignal("user cancelled")
        val context = PackCallContext(
            requestId = "req-2",
            packRef = ref,
            caller = PackCaller.Host,
            deadlineNanos = 0L,
            cancellation = signal,
        )

        assertEquals(PackErrorCode.CANCELLED, context.failureOrNull(1L)?.code)
        assertThrows(PackCancelledException::class.java) {
            context.checkActive(1L)
        }
    }

    @Test
    fun `zero or negative timeout is rejected instead of coerced`() {
        assertThrows(IllegalArgumentException::class.java) {
            PackCallContext.after("req", ref, PackCaller.Host, timeoutMs = 0L)
            Unit
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackCallContext.after("req", ref, PackCaller.Host, timeoutMs = -1L)
            Unit
        }
    }

    @Test
    fun `execution policy caps requested timeouts`() {
        val policy = PackExecutionPolicy(
            defaultTimeoutMs = 1_000L,
            hardTimeoutMs = 5_000L,
            queueCapacity = 1,
        )

        assertEquals(1_000L, policy.timeoutFor(null))
        assertEquals(5_000L, policy.timeoutFor(99_999L))
        assertThrows(IllegalArgumentException::class.java) {
            policy.timeoutFor(0L)
            Unit
        }
    }

    @Test
    fun `result factory maps failure codes to statuses`() {
        val ok = PackResult.ok("req", "{}", 3L)
        assertEquals(PackCallStatus.OK, ok.status)
        assertNull(ok.error)

        val timeout = PackResult.failure(
            "req",
            PackDiagnostic(PackErrorCode.TIMEOUT, "timed out"),
            5L,
        )
        assertEquals(PackCallStatus.TIMEOUT, timeout.status)
        assertEquals(PackErrorCode.TIMEOUT, timeout.error?.code)

        assertThrows(IllegalArgumentException::class.java) {
            PackResult(
                requestId = "req",
                status = PackCallStatus.OK,
                dataJson = "{}",
                message = null,
                elapsedMs = 0L,
                error = PackDiagnostic(PackErrorCode.INTERNAL, "bad"),
            )
            Unit
        }
    }

    @Test
    fun `close outcome cannot claim release while remaining resident`() {
        assertTrue(CloseOutcome.released().released)

        val resident = CloseOutcome.resident("native handle still mapped")
        assertTrue(resident.stillResident)
        assertTrue(resident.needsRestart)
        assertEquals(PackErrorCode.NEEDS_RESTART, resident.failure?.code)

        assertThrows(IllegalArgumentException::class.java) {
            CloseOutcome(
                released = true,
                stillResident = true,
            )
            Unit
        }
    }
}