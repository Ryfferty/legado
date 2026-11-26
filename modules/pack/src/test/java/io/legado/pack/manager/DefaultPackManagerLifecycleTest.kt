package io.legado.pack.manager

import io.legado.pack.api.CloseOutcome
import io.legado.pack.api.ComponentKind
import io.legado.pack.api.PackCallStatus
import io.legado.pack.api.PackCaller
import io.legado.pack.api.PackCancelledException
import io.legado.pack.api.PackEntryPoint
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackId
import io.legado.pack.api.PackInvocation
import io.legado.pack.api.PackResult
import io.legado.pack.api.PackState
import io.legado.pack.registry.PackRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DefaultPackManagerLifecycleTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private lateinit var root: File
    private var provider: FakeRuntimeProvider = FakeRuntimeProvider()
    private lateinit var environment: FakeEnvironment
    private lateinit var registry: PackRegistry
    private lateinit var manager: DefaultPackManager

    @Before
    fun setUp() {
        root = temp.newFolder("packs")
        provider = FakeRuntimeProvider()
        environment = FakeEnvironment(root, mapOf(ComponentKind.JS to provider))
        registry = PackRegistry()
        manager = DefaultPackManager(
            environment,
            registry,
            executionPolicy = io.legado.pack.api.PackExecutionPolicy(
                defaultTimeoutMs = 2_000,
                hardTimeoutMs = 5_000,
                closeTimeoutMs = 250,
            ),
        )
    }

    @After
    fun tearDown() {
        manager.close()
        registry.close()
    }

    private fun toolUserscript(id: String, versionCode: Long = 1L): ByteArray =
        PackFixtures.userscript(
            id = id,
            versionCode = versionCode,
            capabilities = listOf("SOURCE_COMMANDS", "MCP_TOOLS"),
            commands = listOf("hello source", "echo"),
            mcpTools = listOf("demo_add echo read"),
        )

    @Test
    fun sourceExportedAndMcpToolGrantsAreCheckedBeforeDispatch() = runBlocking {
        val info = PackFixtures.install(
            manager,
            toolUserscript("permission-pack"),
            grant = { PackFixtures.defaultGrant(it) },
        ).info

        val unexported = manager.invokeBlocking(
            PackId.require("permission-pack"),
            "echo",
            "{}",
            PackCaller.Source("source-key"),
        )
        assertEquals(PackErrorCode.PERMISSION_DENIED.wire, unexported.code)

        val ungrantedTool = manager.invoke(
            PackInvocation(
                expectedRef = info.ref,
                entry = PackEntryPoint.McpTool("demo_add"),
                argJson = "{}",
                caller = PackCaller.Mcp("client"),
            ),
        )
        assertEquals(PackErrorCode.PERMISSION_DENIED.wire, ungrantedTool.code)
        assertEquals(0, provider.loadCount.get())
    }

    @Test
    fun uninstallSurfacesRegistrationSinkFailures() = runBlocking {
        val flaky = java.util.concurrent.atomic.AtomicBoolean(false)
        registry.attach { if (flaky.get()) throw IllegalStateException("sink boom") }
        PackFixtures.install(manager, toolUserscript("sink-uninstall-pack"))

        flaky.set(true)
        val report = manager.uninstall(PackId.require("sink-uninstall-pack"))

        assertNotNull(report.failure)
        assertEquals(PackErrorCode.INTERNAL.wire, report.failure!!.code.wire)
        assertTrue(report.registrationRevoked)
        assertFalse(report.clean)
    }

    @Test
    fun orphanDirectoryUninstallSurfacesSinkFailures() = runBlocking {
        // A pack directory without a manager record (e.g. corrupted metadata)
        // must surface sink failures exactly like the installed-pack path.
        val id = PackId.require("orphan-uninstall-pack")
        val packDir = File(root, "orphan-uninstall-pack")
        assertTrue(packDir.mkdirs())
        assertTrue(File(packDir, "leftover.txt").createNewFile())

        val flaky = java.util.concurrent.atomic.AtomicBoolean(false)
        registry.attach { if (flaky.get()) throw IllegalStateException("sink boom") }
        flaky.set(true)

        val report = manager.uninstall(id)

        assertNotNull(report.failure)
        assertEquals(PackErrorCode.INTERNAL.wire, report.failure!!.code.wire)
        assertTrue(report.registrationRevoked)
        assertFalse(report.clean)
        assertFalse(packDir.exists())
    }

    @Test
    fun staleEntryReferenceAfterUpgradeNeverReachesNewRevision() = runBlocking {
        val v1 = PackFixtures.install(manager, PackFixtures.userscript(versionCode = 1))
        val oldRef = v1.info.ref
        val v2 = PackFixtures.install(manager, PackFixtures.userscript(versionCode = 2))
        assertEquals(oldRef.epoch + 1L, v2.info.ref.epoch)
        assertEquals(0, provider.loadCount.get())

        val stale = PackInvocation(
            expectedRef = oldRef,
            entry = PackEntryPoint.DirectCommand("hello"),
            argJson = "{}",
            caller = PackCaller.Host,
        )
        val staleResult = manager.invoke(stale)
        assertTrue(staleResult.isError)
        assertEquals(PackErrorCode.INVALID_REQUEST.wire, staleResult.code)
        assertEquals(0, provider.loadCount.get())

        val fresh = manager.invokeBlocking(
            PackId.require("hello-js"),
            "hello",
            "{}",
            PackCaller.Host,
        )
        assertEquals(PackCallStatus.OK, fresh.status)
        assertEquals(1, provider.loadCount.get())
    }

    @Test
    fun disableRevokesRegistrationAndRejectsNewCalls() = runBlocking {
        PackFixtures.install(
            manager,
            toolUserscript("tool-pack"),
            grant = { PackFixtures.defaultGrant(it, toolPermissions = mapOf("demo_add" to true)) },
        )
        assertEquals(setOf("demo_add"), registry.snapshot().toolNames)
        val oldInfo = manager.info(PackId.require("tool-pack"))!!

        val disabled = manager.setEnabled(PackId.require("tool-pack"), false)
        assertEquals(PackCallStatus.OK, disabled.status)
        assertTrue(registry.snapshot().toolNames.isEmpty())
        assertEquals(PackState.DISABLED, manager.info(PackId.require("tool-pack"))!!.state)

        val direct = manager.invokeBlocking(
            PackId.require("tool-pack"),
            "hello",
            "{}",
            PackCaller.Host,
        )
        assertEquals(PackErrorCode.PACK_DISABLED.wire, direct.code)

        val oldEntry = manager.invoke(
            PackInvocation(
                expectedRef = oldInfo.ref,
                entry = PackEntryPoint.DirectCommand("hello"),
                argJson = "{}",
                caller = PackCaller.Host,
            ),
        )
        assertTrue(oldEntry.isError)
        assertEquals(0, provider.loadCount.get())
    }

    @Test
    fun uninstallDeletesFilesAndRevokesRegistration() = runBlocking {
        PackFixtures.install(
            manager,
            toolUserscript("tool-pack"),
            grant = { PackFixtures.defaultGrant(it, toolPermissions = mapOf("demo_add" to true)) },
        )
        val invoked = manager.invokeBlocking(
            PackId.require("tool-pack"),
            "hello",
            "{}",
            PackCaller.Host,
        )
        assertEquals(PackCallStatus.OK, invoked.status)
        assertEquals(1, provider.loadCount.get())

        val report = manager.uninstall(PackId.require("tool-pack"))
        assertTrue(report.clean)
        assertTrue(report.registrationRevoked)
        assertTrue(report.residualPaths.isEmpty())
        assertFalse(report.loadedCodeStillResident)
        assertFalse(report.needsRestart)
        assertNull(manager.info(PackId.require("tool-pack")))
        assertFalse(File(root, "tool-pack").exists())
        assertTrue(registry.snapshot().toolNames.isEmpty())
        assertNull(manager.selfTestReport(PackId.require("tool-pack")))
    }

    @Test
    fun uninstallDuringActiveCallQuarantinesAndStillDeletesFiles() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider = FakeRuntimeProvider(
            loader = { ref, component, _, _ ->
                FakeInstance(
                    ref = ref,
                    componentId = component.id,
                    invoker = { _, _, _ ->
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        "{}"
                    },
                )
            },
        )
        environment = FakeEnvironment(root, mapOf(ComponentKind.JS to provider))
        manager.close()
        manager = DefaultPackManager(
            environment,
            registry,
            executionPolicy = io.legado.pack.api.PackExecutionPolicy(
                defaultTimeoutMs = 2_000,
                hardTimeoutMs = 5_000,
                closeTimeoutMs = 150,
            ),
        )
        PackFixtures.install(manager, PackFixtures.userscript(id = "busy-pack"))

        val call = async(Dispatchers.IO) {
            manager.invokeBlocking(
                PackId.require("busy-pack"),
                "hello",
                "{}",
                PackCaller.Host,
            )
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val report = manager.uninstall(PackId.require("busy-pack"))
        release.countDown()
        val result = call.await()

        assertTrue(report.loadedCodeStillResident)
        assertTrue(report.needsRestart)
        assertTrue(report.registrationRevoked)
        assertNull(manager.info(PackId.require("busy-pack")))
        assertFalse(File(root, "busy-pack").exists())
        assertTrue(result.isError)
    }

    @Test
    fun primitivesRejectUseAfterDisable() = runBlocking {
        val captured = AtomicReference<io.legado.pack.api.PackContext>()
        provider = FakeRuntimeProvider(
            loader = { ref, component, context, _ ->
                captured.set(context)
                FakeInstance(ref, component.id)
            },
        )
        environment = FakeEnvironment(root, mapOf(ComponentKind.JS to provider))
        manager.close()
        manager = DefaultPackManager(environment, registry)
        PackFixtures.install(manager, PackFixtures.userscript(id = "epoch-pack"))

        val first = manager.invokeBlocking(
            PackId.require("epoch-pack"),
            "hello",
            "{}",
            PackCaller.Host,
        )
        assertEquals(PackCallStatus.OK, first.status)
        val context = captured.get()
        assertNotNull(context)
        context.kv.put("k", "v")

        manager.setEnabled(PackId.require("epoch-pack"), false)
        try {
            context.kv.put("k", "v2")
            org.junit.Assert.fail("expected stale primitive to fail")
        } catch (_: PackCancelledException) {
            // expected
        }
    }

    @Test
    fun concurrentUpgradeAndUninstallLeavesNoPackAndNoRegistration() = runBlocking {
        PackFixtures.install(
            manager,
            toolUserscript("race-pack"),
            grant = { PackFixtures.defaultGrant(it, toolPermissions = mapOf("demo_add" to true)) },
        )
        val upgradePreview = manager.prepareImport(
            java.io.ByteArrayInputStream(toolUserscript("race-pack", versionCode = 2)),
            io.legado.pack.api.PackImportHint(),
        )
        val upgrade = async(Dispatchers.IO) {
            try {
                manager.commitImport(
                    upgradePreview.token,
                    PackFixtures.defaultGrant(upgradePreview, mapOf("demo_add" to true)),
                )
                null
            } catch (e: io.legado.pack.api.PackException) {
                e
            }
        }
        val uninstall = async(Dispatchers.IO) {
            manager.uninstall(PackId.require("race-pack"))
        }
        val upgradeResult = upgrade.await()
        val uninstallReport = uninstall.await()
        assertTrue(uninstallReport.registrationRevoked)
        assertNull(manager.info(PackId.require("race-pack")))
        assertFalse(File(root, "race-pack").exists())
        assertTrue(registry.snapshot().toolNames.isEmpty())
        if (upgradeResult != null) {
            assertTrue(
                upgradeResult.diagnostic.code == PackErrorCode.INVALID_REQUEST ||
                    upgradeResult.diagnostic.code == PackErrorCode.PACK_NOT_FOUND,
            )
        }
    }
}