package io.legado.pack.api

import io.legado.pack.TestPacks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfTestReportTest {

    private val ref = TestPacks.ref("hello-js")

    @Test
    fun `red diagnostic cannot coexist with passed true`() {
        val report = SelfTestReport(
            runId = "run_1",
            packRef = ref,
            startedAtEpochMs = 10L,
            finishedAtEpochMs = 20L,
            cases = listOf(
                SelfTestCaseResult(name = "smoke", status = SelfTestStatus.PASS),
            ),
            diagnostics = listOf(
                PackDiagnostic(PackErrorCode.INTERNAL, "driver failed"),
            ),
        )

        assertFalse(report.passed)
        assertTrue(report.redCards.isNotEmpty())
    }

    @Test
    fun `all passing cases without red diagnostics pass`() {
        val report = SelfTestReport(
            runId = "run_1",
            packRef = ref,
            startedAtEpochMs = 10L,
            finishedAtEpochMs = 20L,
            cases = listOf(
                SelfTestCaseResult(name = "smoke", status = SelfTestStatus.PASS),
            ),
        )

        assertTrue(report.passed)
        assertTrue(report.redCards.isEmpty())
    }

    @Test
    fun `run id matches the web route constraint`() {
        assertTrue(PackRoutes.isValidRunId("run_1"))
        assertFalse(PackRoutes.isValidRunId("../run"))
        assertThrows(IllegalArgumentException::class.java) {
            SelfTestReport(
                runId = "../run",
                packRef = ref,
                startedAtEpochMs = 0L,
                finishedAtEpochMs = 0L,
                cases = emptyList(),
            )
            Unit
        }
    }
}