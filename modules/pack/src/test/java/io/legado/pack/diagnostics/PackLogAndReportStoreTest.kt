package io.legado.pack.diagnostics

import io.legado.pack.TestPacks
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackId
import io.legado.pack.api.PackLogLevel
import io.legado.pack.api.PackMetrics
import io.legado.pack.api.PackResourceLimits
import io.legado.pack.api.PackStorageLayout
import io.legado.pack.api.SelfTestCaseResult
import io.legado.pack.api.SelfTestReport
import io.legado.pack.api.SelfTestStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class PackLogAndReportStoreTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private fun limits(): PackResourceLimits = PackResourceLimits(
        maxLogBytesPerPack = 600,
        maxLogBytesTotal = 1_000,
    )

    @Test
    fun logsAreBoundedRedactedAndSplitPerPack() {
        val root = temp.newFolder("packs")
        val layout = PackStorageLayout(root)
        val store = PackLogStore(layout, limits())
        val packA = PackId.require("log-pack-a")
        val packB = PackId.require("log-pack-b")

        repeat(40) { index ->
            store.log(
                packA,
                PackLogLevel.INFO,
                "message $index token=super-secret-value",
                mapOf("Authorization" to "Bearer abc.def", "Cookie" to "sid=123", "ok" to "visible"),
            )
        }
        repeat(40) { index ->
            store.log(packB, PackLogLevel.WARN, "other $index")
        }

        val snapshot = store.snapshot(packA, 10)
        assertTrue(snapshot.isNotEmpty())
        assertTrue(snapshot.size <= 10)
        val allText = snapshot.joinToString("\n") { it.message + it.details }
        assertFalse(allText.contains("super-secret-value"))
        assertFalse(allText.contains("abc.def"))
        assertFalse(allText.contains("sid=123"))
        assertTrue(allText.contains("visible"))
        assertEquals("[redacted]", snapshot.first().details["Authorization"])
        assertEquals("[redacted]", snapshot.first().details["Cookie"])

        val fileA = store.logFile(packA)
        val fileB = store.logFile(packB)
        assertTrue(fileA.length() <= 600L)
        assertTrue(fileB.length() <= 600L)
        assertTrue(store.totalDiskBytes() <= 1_000L)
    }

    @Test
    fun globalBudgetPressureEvictsOtherPacksAndKeepsTheWriterDiskLog() {
        val root = temp.newFolder("packs")
        val layout = PackStorageLayout(root)
        val store = PackLogStore(layout, limits())
        val packA = PackId.require("log-keep-a")
        val packB = PackId.require("log-evict-b")

        // B is written first so it is the least recently modified candidate.
        while (store.logFile(packB).length() < 500L) {
            store.log(packB, PackLogLevel.INFO, "evict-b line")
        }

        // Crossing the global budget while writing A must free B's log and
        // keep A's channel. When truncate() reported remaining instead of
        // reclaimed bytes, the accounting never shrank and A's channel was
        // reset on every write instead.
        var guard = 0
        while (store.logFile(packA).length() < 500L && guard < 500) {
            store.log(packA, PackLogLevel.INFO, "keep-a line")
            guard++
        }

        assertEquals(0L, store.logFile(packB).length())
        assertTrue(store.logFile(packA).length() >= 500L)
        assertTrue(store.logFile(packA).length() <= 600L)
        assertTrue(store.totalDiskBytes() <= 1_000L)
    }

    @Test
    fun evictedPacksResumeWritingWithConsistentAccounting() {
        val root = temp.newFolder("packs")
        val layout = PackStorageLayout(root)
        val store = PackLogStore(layout, limits())
        val packA = PackId.require("log-keep-a")
        val packB = PackId.require("log-evict-b")

        // B is written first so it is the least recently modified candidate.
        while (store.logFile(packB).length() < 500L) {
            store.log(packB, PackLogLevel.INFO, "evict-b line")
        }

        // A's writes cross the global budget and evict B's log.
        var guard = 0
        while (store.logFile(packA).length() < 500L && guard < 500) {
            store.log(packA, PackLogLevel.INFO, "keep-a line")
            guard++
        }
        assertEquals(0L, store.logFile(packB).length())

        // B resumes writing. With a stale cached balance the per-pack limit
        // tripped on a phantom value and every line was dropped instead.
        guard = 0
        while (store.logFile(packB).length() < 200L && guard < 500) {
            store.log(packB, PackLogLevel.INFO, "resume-b line")
            guard++
        }

        assertTrue(store.logFile(packB).length() >= 200L)
        // Accounting matches the files on disk.
        assertEquals(store.logFile(packA).length(), store.diskBytes(packA))
        assertEquals(store.logFile(packB).length(), store.diskBytes(packB))
        assertEquals(
            store.logFile(packA).length() + store.logFile(packB).length(),
            store.totalDiskBytes(),
        )
    }

    @Test
    fun structuredDiagnosticsAreBoundedAndRemovable() {
        val store = PackDiagnosticsStore(maxGlobalEvents = 10, maxPerPackEvents = 3)
        val ref = TestPacks.ref("diag-pack")
        store.record(
            packRef = ref,
            requestId = "req",
            code = PackErrorCode.PERMISSION_DENIED,
            message = "denied token=abc",
            details = mapOf("Authorization" to "Bearer xyz"),
        )
        store.recordDiagnostic(ref, null, PackDiagnostic(PackErrorCode.TIMEOUT, "timed out"))
        val events = store.snapshot(ref.id, 10)
        assertEquals(2, events.size)
        assertEquals(PackLogLevel.ERROR, events.first().level)
        assertFalse(events.joinToString { it.message + it.details }.contains("abc"))
        store.onPackRemoved(ref.id)
        assertTrue(store.snapshot(ref.id, 10).isEmpty())
    }

    @Test
    fun selfTestReportsRoundTripAndCorruptFilesDoNotCrash() {
        val root = temp.newFolder("packs")
        val layout = PackStorageLayout(root)
        val failures = CopyOnWriteArrayList<PackDiagnostic>()
        val store = SelfTestReportStore(layout) { failures += it }
        val ref = TestPacks.ref("diag-pack")
        val report1 = SelfTestReport(
            runId = "run-1",
            packRef = ref,
            startedAtEpochMs = 100,
            finishedAtEpochMs = 200,
            cases = listOf(
                SelfTestCaseResult("case", SelfTestStatus.PASS, dataJson = "{}", elapsedMs = 10),
            ),
            metrics = PackMetrics(elapsedMs = 10, networkBytesIn = 5, networkBytesOut = 7, logBytes = 3),
            diagnostics = emptyList(),
        )
        val report2 = report1.copy(runId = "run-2", finishedAtEpochMs = 300)
        assertNull(store.save(report1))
        assertNull(store.save(report2))

        assertEquals(report1, store.load(ref.id, "run-1"))
        assertEquals(report2, store.load(ref.id))
        assertEquals(2, store.recent(ref.id, 10).size)
        assertEquals("run-2", store.recent(ref.id, 1).single().runId)

        val corrupt = File(File(layout.packDir(ref.id), "self-tests"), "run-3.json")
        corrupt.writeText("not json")
        assertNull(store.load(ref.id, "run-3"))
        assertTrue(failures.isNotEmpty())
        assertNotNull(store.load(ref.id))
    }
}