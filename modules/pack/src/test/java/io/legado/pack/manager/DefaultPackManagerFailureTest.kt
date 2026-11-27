package io.legado.pack.manager

import io.legado.pack.api.ComponentKind
import io.legado.pack.api.PackCallStatus
import io.legado.pack.api.PackCaller
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import io.legado.pack.api.PackState
import io.legado.pack.api.RuntimeAvailability
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
}