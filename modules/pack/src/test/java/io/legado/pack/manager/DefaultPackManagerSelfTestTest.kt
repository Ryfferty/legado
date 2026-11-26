package io.legado.pack.manager

import io.legado.pack.api.ComponentKind
import io.legado.pack.api.PackCaller
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import io.legado.pack.api.PackState
import io.legado.pack.api.SelfTestStatus
import io.legado.pack.registry.PackRegistry
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

class DefaultPackManagerSelfTestTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var provider: FakeRuntimeProvider
    private lateinit var environment: FakeEnvironment
    private lateinit var registry: PackRegistry
    private lateinit var manager: DefaultPackManager

    @Before
    fun setUp() {
        root = temp.newFolder("packs")
        provider = FakeRuntimeProvider()
        environment = FakeEnvironment(root, mapOf(ComponentKind.JS to provider))
        registry = PackRegistry()
        manager = DefaultPackManager(environment, registry)
    }

    @After
    fun tearDown() {
        manager.close()
        registry.close()
    }

    private fun selfTestPack(id: String = "selftest-pack"): ByteArray =
        PackFixtures.userscript(
            id = id,
            capabilities = listOf("SELF_TEST"),
            commands = listOf("hello"),
            selfTests = listOf("smoke hello 1000"),
        )

    @Test
    fun selfTestRunsLazilyAndPersistsReportAcrossRestart() = runBlocking {
        PackFixtures.install(manager, selfTestPack())
        assertEquals(0, provider.loadCount.get())
        val report = manager.selfTest(PackId.require("selftest-pack"))
        assertTrue(report.passed)
        assertEquals(1, report.cases.size)
        assertEquals(SelfTestStatus.PASS, report.cases.first().status)
        assertEquals(1, provider.loadCount.get())
        assertEquals(report.runId, manager.selfTestReport(PackId.require("selftest-pack"))!!.runId)
        assertEquals(report.runId, manager.selfTestReport(PackId.require("selftest-pack"), report.runId)!!.runId)

        manager.close()
        manager = DefaultPackManager(environment, registry)
        val restored = manager.selfTestReport(PackId.require("selftest-pack"))
        assertNotNull(restored)
        assertEquals(report.runId, restored!!.runId)
        assertTrue(manager.selfTestReports(PackId.require("selftest-pack")).isNotEmpty())
        assertEquals(1, provider.loadCount.get())
    }

    @Test
    fun failingSelfTestProducesRedCardAndBrokenState() = runBlocking {
        provider = FakeRuntimeProvider(
            loader = { ref, component, _, _ ->
                FakeInstance(
                    ref = ref,
                    componentId = component.id,
                    invoker = { _, _, _ ->
                        throw PackException(
                            io.legado.pack.api.PackDiagnostic(
                                code = PackErrorCode.SELFTEST_FAILED,
                                message = "expected self-test failure",
                            ),
                        )
                    },
                )
            },
        )
        environment = FakeEnvironment(root, mapOf(ComponentKind.JS to provider))
        manager.close()
        manager = DefaultPackManager(environment, registry)
        PackFixtures.install(manager, selfTestPack("failing-pack"))

        val report = manager.selfTest(PackId.require("failing-pack"))
        assertFalse(report.passed)
        assertTrue(report.redCards.isNotEmpty())
        assertEquals(PackState.BROKEN, manager.info(PackId.require("failing-pack"))!!.state)
        assertNotNull(manager.selfTestReport(PackId.require("failing-pack")))
    }

    @Test
    fun packWithoutDeclaredSelfTestsReturnsSkipReport() = runBlocking {
        PackFixtures.install(manager, PackFixtures.userscript(id = "no-tests"))
        val report = manager.selfTest(PackId.require("no-tests"))
        assertFalse(report.passed)
        assertEquals(SelfTestStatus.SKIP, report.cases.single().status)
        assertEquals(0, provider.loadCount.get())
    }

    @Test
    fun disabledPackSelfTestDoesNotLoadCode() = runBlocking {
        PackFixtures.install(manager, selfTestPack("disabled-pack"))
        manager.setEnabled(PackId.require("disabled-pack"), false)
        val report = manager.selfTest(PackId.require("disabled-pack"))
        assertFalse(report.passed)
        assertTrue(report.diagnostics.any { it.code == PackErrorCode.PACK_DISABLED })
        assertEquals(0, provider.loadCount.get())
    }

    @Test
    fun missingPackSelfTestThrowsTypedFailure() = runBlocking {
        try {
            manager.selfTest(PackId.require("missing-pack"))
            org.junit.Assert.fail("expected PACK_NOT_FOUND")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.PACK_NOT_FOUND, e.diagnostic.code)
        }
    }

    @Test
    fun selfTestReportIsNullForUnknownRunId() = runBlocking {
        PackFixtures.install(manager, selfTestPack("runid-pack"))
        manager.selfTest(PackId.require("runid-pack"))
        assertEquals(null, manager.selfTestReport(PackId.require("runid-pack"), "does-not-exist"))
    }
}