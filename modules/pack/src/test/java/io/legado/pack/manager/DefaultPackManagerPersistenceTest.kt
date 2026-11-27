package io.legado.pack.manager

import io.legado.pack.api.ComponentKind
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackGrant
import io.legado.pack.api.PackId
import io.legado.pack.api.PackImportHint
import io.legado.pack.api.PackState
import io.legado.pack.api.PackStorageLayout
import io.legado.pack.registry.PackRegistry
import io.legado.pack.storage.ManifestJsonCodec
import io.legado.pack.storage.PackFormat
import io.legado.pack.storage.PackInstallLedger
import io.legado.pack.storage.StoredPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persistence-ordering and durability tests for the state that lives outside
 * the pack code revisions:
 *
 * * the admission epoch must stay monotonic across an uninstall/reinstall
 *   (the durable install ledger), because host-side grant bookkeeping binds
 *   permissions to (id, revision, epoch, hash);
 * * same-epoch state snapshots must reach `current.json` in snapshot order
 *   even when their persists interleave in reverse (deliver-side
 *   markBroken/markHealthy run on the callers' coroutines);
 * * a prepareImport that completes after close() must be rejected and its
 *   staging reclaimed instead of being published into a closed manager;
 * * close() must surface an IO red card when pending staging cannot be
 *   deleted (only enforceable on platforms where an open handle blocks
 *   deletion; otherwise the test is skipped via an assumption).
 */
class DefaultPackManagerPersistenceTest {

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
        manager = DefaultPackManager(environment, registry)
    }

    @After
    fun tearDown() {
        manager.close()
        registry.close()
    }

    private fun currentJson(id: String): File = File(File(root, id), "current.json")

    // ------------------------------------------------------------------
    // Install epochs (problem: reinstall after uninstall restarted at 1)
    // ------------------------------------------------------------------

    @Test
    fun reinstallAfterUninstallNeverReusesAnAdmissionEpoch() = runBlocking {
        val content = PackFixtures.userscript(id = "epoch-reuse-pack")
        val first = PackFixtures.install(manager, content).info
        assertEquals(1L, first.ref.epoch)
        assertTrue(File(root, ".epochs.json").isFile)

        val report = manager.uninstall(PackId.require("epoch-reuse-pack"))
        assertTrue(report.residualPaths.isEmpty())

        // Identical content, no current.json left: without the ledger this
        // reinstall would reuse epoch 1.
        val second = PackFixtures.install(manager, content).info
        assertTrue(
            "reinstall reused epoch ${second.ref.epoch} after epoch ${first.ref.epoch}",
            second.ref.epoch > first.ref.epoch,
        )

        // The high-water mark survives a manager restart as well.
        manager.close()
        manager = DefaultPackManager(environment, registry)
        manager.uninstall(PackId.require("epoch-reuse-pack"))
        val third = PackFixtures.install(manager, content).info
        assertTrue(
            "reinstall reused epoch ${third.ref.epoch} after a manager restart",
            third.ref.epoch > second.ref.epoch,
        )
    }

    @Test
    fun enableDisableAndUninstallEpochsReachTheDurableLedger() = runBlocking {
        val content = PackFixtures.userscript(id = "epoch-lifecycle-pack")
        val first = PackFixtures.install(manager, content).info
        assertEquals(1L, first.ref.epoch)

        // Each toggle consumes an admission epoch that host-side grants can
        // bind to; the durable high-water mark must not stay at 1.
        assertFalse(manager.setEnabled(PackId.require("epoch-lifecycle-pack"), false).isError)
        assertFalse(manager.setEnabled(PackId.require("epoch-lifecycle-pack"), true).isError)

        val report = manager.uninstall(PackId.require("epoch-lifecycle-pack"))
        assertTrue(report.residualPaths.isEmpty())
        assertTrue(report.clean)

        // The last live epoch was 3 (install 1, disable 2, enable 3) and the
        // uninstall itself bumped to 4: reinstalling identical content must
        // land beyond every epoch this installation already used.
        assertEquals(4L, PackInstallLedger(root).highestEpoch(PackId.require("epoch-lifecycle-pack")))
        val second = PackFixtures.install(manager, content).info
        assertTrue(
            "reinstall reused epoch ${second.ref.epoch} after enable/disable/uninstall",
            second.ref.epoch > 4L,
        )
    }

    @Test
    fun legacyInstallationWithoutALedgerMigratesItsEpochOnRecovery() = runBlocking {
        val content = PackFixtures.userscript(id = "legacy-ledger-pack")
        val installed = PackFixtures.install(manager, content).info
        assertFalse(manager.setEnabled(PackId.require("legacy-ledger-pack"), false).isError)
        assertFalse(manager.setEnabled(PackId.require("legacy-ledger-pack"), true).isError)
        manager.close()

        // Simulate an installation that predates the durable ledger: only
        // current.json exists on disk, no .epochs.json.
        assertTrue(File(root, ".epochs.json").delete())

        manager = DefaultPackManager(environment, registry)
        // Recovery must migrate the stored epoch (3) into the ledger before
        // the pack can be uninstalled; without the migration a reinstall of
        // identical content could fall back onto an already-used epoch.
        assertEquals(3L, PackInstallLedger(root).highestEpoch(PackId.require("legacy-ledger-pack")))

        manager.uninstall(PackId.require("legacy-ledger-pack"))
        val reinstalled = PackFixtures.install(manager, content).info
        assertTrue(
            "reinstall reused epoch ${reinstalled.ref.epoch} after migrating a legacy installation",
            reinstalled.ref.epoch > installed.ref.epoch + 2L,
        )
    }

    // ------------------------------------------------------------------
    // Same-epoch snapshot ordering (problem: stale snapshot persisted last)
    // ------------------------------------------------------------------
    @Test
    fun staleSameEpochSnapshotCannotOverwriteNewerDiskState() = runBlocking {
        val info = PackFixtures.install(
            manager,
            PackFixtures.userscript(id = "epoch-order-pack"),
        ).info

        // Drive the real markBroken/markHealthy path: broken first, then a
        // successful call that recovers the pack. Both snapshots carry the
        // same epoch; the healthy one is the newer snapshot.
        provider.loadFailure = PackException(
            PackDiagnostic(PackErrorCode.LOAD_FAILED, "load failed once"),
        )
        val failing = manager.invokeBlocking(
            PackId.require("epoch-order-pack"),
            "hello",
            "{}",
            io.legado.pack.api.PackCaller.Host,
        )
        assertEquals(PackErrorCode.LOAD_FAILED.wire, failing.code)
        assertEquals(PackState.BROKEN, manager.info(PackId.require("epoch-order-pack"))!!.state)

        provider.loadFailure = null
        val recovering = manager.invokeBlocking(
            PackId.require("epoch-order-pack"),
            "hello",
            "{}",
            io.legado.pack.api.PackCaller.Host,
        )
        assertEquals(io.legado.pack.api.PackCallStatus.OK, recovering.status)
        assertEquals(PackState.ENABLED, manager.info(PackId.require("epoch-order-pack"))!!.state)
        assertTrue(currentJson("epoch-order-pack").readText().contains("\"broken\":null"))

        // A stale BROKEN snapshot of the same epoch, taken before the
        // recovery snapshot (generation 0 < live generation), persisted now:
        // the guard must refuse the write instead of rolling the disk state
        // back to broken while memory stays healthy.
        val stale = StoredPack(
            schemaVersion = 1,
            id = info.ref.id,
            manifest = info.manifest,
            manifestJson = ManifestJsonCodec.encode(info.manifest),
            revision = info.ref.revision,
            contentSha256 = info.ref.revision,
            epoch = info.ref.epoch,
            enabled = true,
            installedAtEpochMs = info.installedAtEpochMs,
            needsRestart = false,
            broken = PackDiagnostic(
                PackErrorCode.LOAD_FAILED,
                "stale broken snapshot from a lost race",
            ),
            grant = PackGrant(permissionHash = info.manifest.permissions.digest()),
            lastSelfTestRunId = null,
            format = PackFormat.USERSCRIPT,
        )

        // Superseded is not a failure: the lifecycle flows treat false as
        // "persist failed, roll memory back", which must not happen here.
        assertTrue(manager.persistMetadata(stale, 0L))

        val disk = currentJson("epoch-order-pack").readText()
        assertFalse(
            "stale same-epoch snapshot reached current.json",
            disk.contains("stale broken snapshot from a lost race"),
        )
        assertTrue(disk.contains("\"broken\":null"))
        assertEquals(PackState.ENABLED, manager.info(PackId.require("epoch-order-pack"))!!.state)
    }

    @Test
    fun concurrentDeliverStatesKeepDiskAndMemoryConsistent() = runBlocking {
        PackFixtures.install(manager, PackFixtures.userscript(id = "hammer-pack"))
        val id = PackId.require("hammer-pack")
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val counter = AtomicInteger()
        val failureBase = PackDiagnostic(PackErrorCode.LOAD_FAILED, "hammer failure")
        val threads = (1..2).map {
            Thread {
                while (!stop.get()) {
                    provider.loadFailure = PackException(
                        failureBase.copy(message = "hammer failure #${counter.incrementAndGet()}"),
                    )
                    runCatching {
                        manager.invokeBlocking(id, "hello", "{}", io.legado.pack.api.PackCaller.Host)
                    }
                    provider.loadFailure = null
                    runCatching {
                        manager.invokeBlocking(id, "hello", "{}", io.legado.pack.api.PackCaller.Host)
                    }
                }
            }
        }
        threads.forEach { it.start() }
        Thread.sleep(300)
        // Stop the hammer and join before settling: afterwards no thread can
        // persist a state older than the final snapshot below.
        stop.set(true)
        threads.forEach { it.join(5_000) }

        provider.loadFailure = null
        val settled = manager.invokeBlocking(id, "hello", "{}", io.legado.pack.api.PackCaller.Host)
        assertEquals(io.legado.pack.api.PackCallStatus.OK, settled.status)

        assertEquals(PackState.ENABLED, manager.info(id)!!.state)
        val disk = currentJson("hammer-pack").readText()
        assertTrue(
            "disk state diverged from memory after concurrent delivers: $disk",
            disk.contains("\"broken\":null"),
        )
    }

    // ------------------------------------------------------------------
    // close() vs an in-flight prepareImport (problem: publication raced)
    // ------------------------------------------------------------------

    @Test
    fun prepareCompletingAfterCloseIsRejectedAndStagingReclaimed() = runBlocking {
        val content = PackFixtures.userscript(id = "close-race-pack")
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val input = object : InputStream() {
            private var served = false

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served) return -1
                served = true
                entered.countDown()
                assertTrue(gate.await(10, TimeUnit.SECONDS))
                val n = minOf(len, content.size)
                System.arraycopy(content, 0, b, off, n)
                return n
            }

            override fun read(): Int = read(ByteArray(1), 0, 1)
        }
        // Catch inside the child coroutine: a plain async whose block throws
        // would cancel the runBlocking parent through structured concurrency,
        // so the expected PackException is converted to a value here.
        val outcome = async(Dispatchers.IO) {
            try {
                manager.prepareImport(input, PackImportHint())
                "unexpectedly-completed"
            } catch (e: PackException) {
                "rejected:${e.diagnostic.code}"
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        // close() returns while the import coroutine is still blocked inside
        // its staging work; the preparation finishes afterwards.
        manager.close()
        gate.countDown()

        assertEquals("rejected:${PackErrorCode.INTERNAL}", outcome.await())

        // The staging of the rejected preparation is reclaimed, not left
        // behind in a manager that is already closed.
        val packDir = File(root, "close-race-pack")
        assertFalse(
            "staging skeleton of a rejected late preparation remains: ${packDir.list()}",
            packDir.exists(),
        )
    }

    // ------------------------------------------------------------------
    // close() must report undiscarded staging (problem: Boolean was swallowed)
    // ------------------------------------------------------------------

    @Test
    fun closeReportsIoFailedWhenPendingStagingCannotBeDeleted() = runBlocking {
        val probe = File(root, "delete-probe.txt")
        probe.writeText("probe")
        val probeStream = FileInputStream(probe)
        val deletionBlocked = !probe.delete()
        probeStream.close()
        probe.delete()
        Assume.assumeTrue(
            "this platform does not block deletion of an open file; the residual branch is not enforceable here",
            deletionBlocked,
        )

        val preview = manager.prepareImport(
            java.io.ByteArrayInputStream(PackFixtures.userscript(id = "lock-staging-pack")),
            PackImportHint(),
        )
        val staging = PackStorageLayout(root).stagingDir(
            PackId.require("lock-staging-pack"),
            preview.token,
        )
        val stagedFiles = staging.listFiles()?.filter { it.isFile }.orEmpty()
        assertTrue(stagedFiles.isNotEmpty())
        val lock = FileInputStream(stagedFiles.first())

        try {
            manager.close()
            val events = manager.diagnosticEvents(null, 100)
            assertTrue(
                "close() left pending staging without an IO_FAILED red card: $events",
                events.any { event ->
                    event.code == PackErrorCode.IO_FAILED &&
                        event.message.contains("cannot discard pending import staging")
                },
            )
        } finally {
            lock.close()
        }
    }
}
