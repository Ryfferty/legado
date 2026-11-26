package io.legado.pack.manager

import io.legado.pack.api.ComponentKind
import io.legado.pack.api.PackCallStatus
import io.legado.pack.api.PackCaller
import io.legado.pack.api.PackExecutionPolicy
import io.legado.pack.api.PackId
import io.legado.pack.api.PackState
import io.legado.pack.registry.PackRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DefaultPackManagerTimeoutTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var registry: PackRegistry
    private var manager: DefaultPackManager? = null

    @Before
    fun setUp() {
        root = temp.newFolder("packs")
        registry = PackRegistry()
    }

    @After
    fun tearDown() {
        manager?.close()
        registry.close()
    }

    private fun newManager(provider: FakeRuntimeProvider): DefaultPackManager {
        val environment = FakeEnvironment(root, mapOf(ComponentKind.JS to provider))
        val created = DefaultPackManager(
            environment,
            registry,
            executionPolicy = PackExecutionPolicy(
                defaultTimeoutMs = 1_000,
                hardTimeoutMs = 2_000,
                queueCapacity = 1,
                closeTimeoutMs = 120,
            ),
        )
        manager = created
        return created
    }

    @Test
    fun cooperativeTimeoutReturnsTimeoutAndPackRemainsUsable() = runBlocking {
        val slow = AtomicBoolean(true)
        val provider = FakeRuntimeProvider(
            loader = { ref, component, _, _ ->
                FakeInstance(
                    ref = ref,
                    componentId = component.id,
                    invoker = { _, _, call ->
                        while (slow.get()) {
                            call.checkActive()
                            Thread.sleep(5)
                        }
                        "{}"
                    },
                )
            },
        )
        val manager = newManager(provider)
        PackFixtures.install(manager, PackFixtures.userscript(id = "slow-pack"))

        val timedOut = manager.invokeBlocking(
            PackId.require("slow-pack"),
            "hello",
            "{}",
            PackCaller.Host,
            timeoutMs = 100,
        )
        assertEquals(PackCallStatus.TIMEOUT, timedOut.status)
        assertEquals(1, provider.loadCount.get())
        assertTrue(manager.info(PackId.require("slow-pack"))!!.state != PackState.NEEDS_RESTART)

        slow.set(false)
        val recovered = manager.invokeBlocking(
            PackId.require("slow-pack"),
            "hello",
            "{}",
            PackCaller.Host,
            timeoutMs = 1_000,
        )
        assertEquals(PackCallStatus.OK, recovered.status)
        assertEquals(PackState.ENABLED, manager.info(PackId.require("slow-pack"))!!.state)
    }

    @Test
    fun nonCooperativeTimeoutQuarantinesWithoutCreatingMoreWorkers() = runBlocking {
        val release = CountDownLatch(1)
        val provider = FakeRuntimeProvider(
            loader = { ref, component, _, _ ->
                FakeInstance(
                    ref = ref,
                    componentId = component.id,
                    invoker = { _, _, _ ->
                        while (release.count > 0L) {
                            try {
                                Thread.sleep(10)
                            } catch (_: InterruptedException) {
                                // Deliberately non-cooperative: prove the manager
                                // quarantines instead of claiming a kill.
                            }
                        }
                        "{}"
                    },
                )
            },
        )
        val manager = newManager(provider)
        PackFixtures.install(manager, PackFixtures.userscript(id = "stuck-pack"))

        val first = manager.invokeBlocking(
            PackId.require("stuck-pack"),
            "hello",
            "{}",
            PackCaller.Host,
            timeoutMs = 100,
        )
        assertEquals(PackCallStatus.NEEDS_RESTART, first.status)
        assertEquals(PackState.NEEDS_RESTART, manager.info(PackId.require("stuck-pack"))!!.state)
        assertEquals(1, provider.loadCount.get())

        repeat(5) {
            val rejected = manager.invokeBlocking(
                PackId.require("stuck-pack"),
                "hello",
                "{}",
                PackCaller.Host,
                timeoutMs = 100,
            )
            assertEquals(PackCallStatus.NEEDS_RESTART, rejected.status)
        }
        assertEquals(1, provider.loadCount.get())
        val workers = Thread.getAllStackTraces().keys.count { it.name == "pack-worker-stuck-pack" }
        assertTrue("expected at most one pack worker, found $workers", workers <= 1)

        release.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            val remaining = Thread.getAllStackTraces().keys.count { it.name == "pack-worker-stuck-pack" }
            if (remaining == 0) break
            Thread.sleep(10)
        }
    }

    @Test
    fun queuedCallBeyondCapacityIsRejectedImmediately() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = FakeRuntimeProvider(
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
        val manager = newManager(provider)
        PackFixtures.install(manager, PackFixtures.userscript(id = "queue-pack"))

        val first = async(Dispatchers.IO) {
            manager.invokeBlocking(
                PackId.require("queue-pack"),
                "hello",
                "{}",
                PackCaller.Host,
                timeoutMs = 5_000,
            )
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) {
            manager.invokeBlocking(
                PackId.require("queue-pack"),
                "hello",
                "{}",
                PackCaller.Host,
                timeoutMs = 5_000,
            )
        }
        Thread.sleep(100)
        val third = manager.invokeBlocking(
            PackId.require("queue-pack"),
            "hello",
            "{}",
            PackCaller.Host,
            timeoutMs = 100,
        )
        assertEquals(PackCallStatus.ERROR, third.status)
        assertTrue(third.message?.contains("queue") == true || third.code == "INVALID_REQUEST")
        release.countDown()
        first.await()
        second.await()
    }
}