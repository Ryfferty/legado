package io.legado.pack.manager

import io.legado.pack.api.ComponentKind
import io.legado.pack.api.PackCallStatus
import io.legado.pack.api.PackCaller
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import io.legado.pack.api.PackResult
import io.legado.pack.api.PackState
import io.legado.pack.api.RuntimeAvailability
import io.legado.pack.api.UninstallReport
import io.legado.pack.registry.PackRegistry
import io.legado.pack.runtime.DETAIL_RESIDENT_AFTER_FAILURE
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DefaultPackManagerFailureTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var registry: PackRegistry
    private lateinit var manager: DefaultPackManager

    @Before
    fun setUp() {
        root = temp.newFolder("packs")
        registry = PackRegistry()
    }

    @After
    fun tearDown() {
        manager.close()
        registry.close()
    }

    private fun managerWith(provider: FakeRuntimeProvider?): DefaultPackManager {
        val providers: Map<ComponentKind, io.legado.pack.api.PackRuntimeProvider> =
            if (provider == null) {
                emptyMap()
            } else {
                mapOf(ComponentKind.JS to provider)
            }
        manager = DefaultPackManager(
            FakeEnvironment(root, providers),
            registry,
        )
        return manager
    }

    @Test
    fun missingRuntimeProviderIsRedCardComponentMissing() = runBlocking {
        val manager = managerWith(provider = null)
        PackFixtures.install(manager, PackFixtures.userscript(id = "no-runtime"))
        val result = manager.invokeBlocking(
            PackId.require("no-runtime"),
            "hello",
            "{}",
            PackCaller.Host,
        )
        assertEquals(PackCallStatus.ERROR, result.status)
        assertEquals(PackErrorCode.COMPONENT_MISSING, result.error!!.code)
        assertTrue(result.error!!.isRedCard)
        assertEquals(PackState.BROKEN, manager.info(PackId.require("no-runtime"))!!.state)
    }

    @Test
    fun providerProbeFailureIsRedCardAndNeverSilentlyFallsBack() = runBlocking {
        val provider = FakeRuntimeProvider()
        provider.probeResult = RuntimeAvailability.Unavailable(
            PackDiagnostic(PackErrorCode.NATIVE_PLATFORM, "unsupported device"),
        )
        val manager = managerWith(provider)
        PackFixtures.install(manager, PackFixtures.userscript(id = "probe-fail"))
        val result = manager.invokeBlocking(
            PackId.require("probe-fail"),
            "hello",
            "{}",
            PackCaller.Host,
        )
        assertEquals(PackCallStatus.ERROR, result.status)
        assertEquals(PackErrorCode.NATIVE_PLATFORM, result.error!!.code)
        assertTrue(result.error!!.isRedCard)
        assertEquals(0, provider.loadCount.get())
    }

    @Test
    fun providerLoadFailureIsRedCardLoadFailed() = runBlocking {
        val provider = FakeRuntimeProvider()
        provider.loadFailure = PackException(
            PackDiagnostic(PackErrorCode.LOAD_FAILED, "native library failed to map"),
        )
        val manager = managerWith(provider)
        PackFixtures.install(manager, PackFixtures.userscript(id = "load-fail"))
        val result = manager.invokeBlocking(
            PackId.require("load-fail"),
            "hello",
            "{}",
            PackCaller.Host,
        )
        assertEquals(PackErrorCode.LOAD_FAILED, result.error!!.code)
        assertTrue(result.error!!.isRedCard)
        assertEquals(PackState.BROKEN, manager.info(PackId.require("load-fail"))!!.state)
        assertNotNull(manager.diagnosticEvents(PackId.require("load-fail")))
        assertTrue(manager.diagnosticEvents(PackId.require("load-fail")).isNotEmpty())
    }

    @Test
    fun missingComponentFileIsRedCardAtInvokeTime() = runBlocking {
        val provider = FakeRuntimeProvider()
        val manager = managerWith(provider)
        PackFixtures.install(manager, PackFixtures.userscript(id = "file-missing"))
        val info = manager.info(PackId.require("file-missing"))!!
        val main = File(
            File(File(File(root, "file-missing"), "code"), info.ref.revision.value),
            "main.js",
        )
        assertTrue(main.isFile)
        main.parentFile.setWritable(true, false)
        main.setWritable(true, false)
        assertTrue(main.delete())

        val result = manager.invokeBlocking(
            PackId.require("file-missing"),
            "hello",
            "{}",
            PackCaller.Host,
        )
        assertEquals(PackErrorCode.COMPONENT_MISSING, result.error!!.code)
        assertTrue(result.error!!.isRedCard)
        assertEquals(0, provider.loadCount.get())
    }

    @Test
    fun residentLoadFailureQuarantinesAndReportsResidencyOnDisableAndUninstall() = runBlocking {
        val provider = FakeRuntimeProvider()
        // A load that failed after mapping trusted code (the shape the native
        // bridge reports through the resident-after-failure detail).
        provider.loadFailure = PackException(
            PackDiagnostic(
                PackErrorCode.NATIVE_PLATFORM,
                "native file load failed: mapped library is pinned until restart",
                details = mapOf(DETAIL_RESIDENT_AFTER_FAILURE to "true"),
            ),
        )
        val manager = managerWith(provider)
        PackFixtures.install(manager, PackFixtures.userscript(id = "resident-fail"))
        val result = manager.invokeBlocking(
            PackId.require("resident-fail"),
            "hello",
            "{}",
            PackCaller.Host,
        )
        assertEquals(PackErrorCode.NATIVE_PLATFORM, result.error!!.code)
        assertEquals("true", result.error!!.details[DETAIL_RESIDENT_AFTER_FAILURE])
        // The mapped code has no closable instance: the pack is quarantined.
        assertEquals(PackState.NEEDS_RESTART, manager.info(PackId.require("resident-fail"))!!.state)

        // Disable must not claim a clean release while the code is resident.
        val disabled = manager.setEnabled(PackId.require("resident-fail"), false)
        assertEquals(PackErrorCode.NEEDS_RESTART, disabled.error!!.code)

        // The uninstall report must say the code is still resident even though
        // the disk is cleared: no instance was ever registered to close.
        val report = manager.uninstall(PackId.require("resident-fail"))
        assertTrue(report.residualPaths.isEmpty())
        assertTrue(report.loadedCodeStillResident)
        assertTrue(report.needsRestart)
        assertFalse(report.clean)
        assertEquals(PackErrorCode.NEEDS_RESTART, report.failure!!.code)
    }

    @Test
    fun plainLoadFailureWithoutResidencyStillUninstallsClean() = runBlocking {
        val provider = FakeRuntimeProvider()
        provider.loadFailure = PackException(
            PackDiagnostic(PackErrorCode.LOAD_FAILED, "plain load failure"),
        )
        val manager = managerWith(provider)
        PackFixtures.install(manager, PackFixtures.userscript(id = "plain-fail"))
        val result = manager.invokeBlocking(
            PackId.require("plain-fail"),
            "hello",
            "{}",
            PackCaller.Host,
        )
        assertEquals(PackErrorCode.LOAD_FAILED, result.error!!.code)
        // No residency signal: broken (recoverable), not quarantined.
        assertEquals(PackState.BROKEN, manager.info(PackId.require("plain-fail"))!!.state)

        val report = manager.uninstall(PackId.require("plain-fail"))
        assertTrue(report.residualPaths.isEmpty())
        assertFalse(report.loadedCodeStillResident)
        assertFalse(report.needsRestart)
        assertTrue(report.clean)
    }

    /**
     * Deterministic interleaving for the disable leg of the resident-failure
     * race: the worker is inside a blocked load while disable advances the
     * admission epoch, and only then does the load fail with the
     * resident-after-failure signal. The record-scoped mark is rejected
     * (record.ref already carries the new epoch), so the manager-scoped
     * witness must carry the residency into the disable result and back into
     * a durable record mark.
     */
    @Test
    fun residentFailureLandingAfterDisableAdvancedEpochIsNotLost() = runBlocking {
        val releaseLoad = CountDownLatch(1)
        val loadEntered = CountDownLatch(1)
        val provider = FakeRuntimeProvider(
            loader = { _, _, _, _ ->
                loadEntered.countDown()
                check(releaseLoad.await(30, TimeUnit.SECONDS)) { "test did not release the blocked load" }
                throw PackException(
                    PackDiagnostic(
                        PackErrorCode.NATIVE_PLATFORM,
                        "native file load failed: mapped library is pinned until restart",
                        details = mapOf(DETAIL_RESIDENT_AFTER_FAILURE to "true"),
                    ),
                )
            },
        )
        val manager = managerWith(provider)
        val id = PackId.require("resident-race-disable")
        PackFixtures.install(manager, PackFixtures.userscript(id = "resident-race-disable"))

        // The worker enters the blocked load while the pack is still enabled.
        var invokeResult: PackResult? = null
        val invokeThread = Thread {
            invokeResult = manager.invokeBlocking(id, "hello", "{}", PackCaller.Host)
        }
        invokeThread.start()
        assertTrue("load never started", loadEntered.await(30, TimeUnit.SECONDS))

        // Disable advances the epoch (the record's ref no longer matches the
        // failing load's ref) and then blocks draining this very worker.
        var disableResult: PackResult? = null
        val disableThread = Thread {
            disableResult = runBlocking { manager.setEnabled(id, false) }
        }
        disableThread.start()
        awaitUntil("disable to advance the admission epoch") { manager.info(id)?.state == PackState.DISABLED }

        releaseLoad.countDown()
        joinThread(invokeThread, "invoke")
        joinThread(disableThread, "disable")

        assertEquals(PackErrorCode.NATIVE_PLATFORM, invokeResult!!.error!!.code)
        // The witness, not the (rejected) record mark, produced this verdict.
        assertEquals(PackErrorCode.NEEDS_RESTART, disableResult!!.error!!.code)
        assertEquals(
            "component 'main' failed to load but its code remains mapped in this process; restart required",
            disableResult!!.error!!.message,
        )
        // Aggregation folds the witness back into a durable record mark.
        assertEquals(PackState.NEEDS_RESTART, manager.info(id)!!.state)

        val report = manager.uninstall(id)
        assertTrue(report.residualPaths.isEmpty())
        assertTrue(report.loadedCodeStillResident)
        assertTrue(report.needsRestart)
        assertFalse(report.clean)
        assertEquals(PackErrorCode.NEEDS_RESTART, report.failure!!.code)
    }

    /**
     * Deterministic interleaving for the uninstall leg: the record is removed
     * while the worker is still inside a blocked load; the resident failure
     * lands only afterwards. With the record gone there is no mark to write,
     * so only the manager-scoped witness (consumed after the worker drained)
     * can keep the uninstall report from claiming a clean release.
     */
    @Test
    fun residentFailureLandingAfterRecordRemovalStillReportsResidentOnUninstall() = runBlocking {
        val releaseLoad = CountDownLatch(1)
        val loadEntered = CountDownLatch(1)
        val provider = FakeRuntimeProvider(
            loader = { _, _, _, _ ->
                loadEntered.countDown()
                check(releaseLoad.await(30, TimeUnit.SECONDS)) { "test did not release the blocked load" }
                throw PackException(
                    PackDiagnostic(
                        PackErrorCode.NATIVE_PLATFORM,
                        "native file load failed: mapped library is pinned until restart",
                        details = mapOf(DETAIL_RESIDENT_AFTER_FAILURE to "true"),
                    ),
                )
            },
        )
        val manager = managerWith(provider)
        val id = PackId.require("resident-race-uninstall")
        PackFixtures.install(manager, PackFixtures.userscript(id = "resident-race-uninstall"))

        var invokeResult: PackResult? = null
        val invokeThread = Thread {
            invokeResult = manager.invokeBlocking(id, "hello", "{}", PackCaller.Host)
        }
        invokeThread.start()
        assertTrue("load never started", loadEntered.await(30, TimeUnit.SECONDS))

        // Uninstall removes the record (and blocks draining the worker) while
        // the load is still running; the failure lands only after release.
        var report: UninstallReport? = null
        val uninstallThread = Thread {
            report = runBlocking { manager.uninstall(id) }
        }
        uninstallThread.start()
        awaitUntil("uninstall to remove the record") { manager.info(id) == null }

        releaseLoad.countDown()
        joinThread(invokeThread, "invoke")
        joinThread(uninstallThread, "uninstall")

        assertEquals(PackErrorCode.NATIVE_PLATFORM, invokeResult!!.error!!.code)
        val uninstalled = report!!
        assertTrue(uninstalled.residualPaths.isEmpty())
        assertTrue(
            "a resident failure landing after record removal must not be reported as a clean release",
            uninstalled.loadedCodeStillResident,
        )
        assertTrue(uninstalled.needsRestart)
        assertFalse(uninstalled.clean)
        assertEquals(PackErrorCode.NEEDS_RESTART, uninstalled.failure!!.code)
    }

    /** Polls [condition] until it holds or a 30 s deadline fails the test. */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L)
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.nanoTime() < deadline)
            Thread.sleep(10L)
        }
    }

    /** Joins [thread] and fails the test when it did not finish in 30 s. */
    private fun joinThread(thread: Thread, what: String) {
        thread.join(TimeUnit.SECONDS.toMillis(30L))
        assertTrue("$what thread did not finish", !thread.isAlive)
    }
}