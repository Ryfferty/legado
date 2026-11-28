package io.legado.app.web.mcp

import io.legado.app.help.pack.FakePackManager
import io.legado.app.help.pack.HostMcpToolNames
import io.legado.app.help.pack.PackFixtures
import io.legado.app.help.pack.PackMcpSchema
import io.legado.app.help.pack.PackToolPermissionStore
import io.legado.pack.api.PackErrorCode
import io.legado.pack.registry.PackRegistration
import io.legado.pack.registry.PackRegistry
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PackMcpBridgeTest {

    private val toolListChanged = "notifications/tools/list_changed"
    private val promptListChanged = "notifications/prompts/list_changed"

    @Test
    fun `pack tools attach and detach for existing and new sessions`() = runBlocking {
        val server = newServer()
        val manager = FakePackManager(listOf(PackFixtures.info()))
        val permissions = PackToolPermissionStore(
            Files.createTempDirectory("mcp-bridge").resolve("grants.json").toFile()
        )
        val bridge = PackMcpBridge(server, { manager }, permissions)
        val registry = PackRegistry(reservedToolNames = HostMcpToolNames.RESERVED)
        bridge.attach(registry)

        val first = RecordingTransport()
        val second = RecordingTransport()
        server.createSession(first)
        server.createSession(second)
        awaitSessionSubscriptions(server, first, second)

        val registration = PackRegistration(
            PackFixtures.ref(),
            PackFixtures.manifest(),
        )
        registry.replaceAll(listOf(registration))
        assertTrue(server.tools.containsKey("demo_add"))
        assertEquals(toolListChanged, first.awaitNotification(toolListChanged).method)
        assertEquals(toolListChanged, second.awaitNotification(toolListChanged).method)

        // A session opened after the registration sees the same server tool
        // map; existing sessions were notified without reconnecting.
        val third = RecordingTransport()
        server.createSession(third)
        assertTrue(server.tools.containsKey("demo_add"))
        assertNull(server.tools["missing_tool"])

        val result = bridge.invoke(
            registration = registration,
            spec = registration.manifest.mcpTools.single(),
            argsJson = "{\"value\":\"hello\"}",
            clientId = "client-1",
        )
        assertFalse(result.isError ?: false)
        assertEquals("OK", result.structuredContent!!["code"]!!.jsonPrimitive.content)
        assertFalse(result.structuredContent!!["isError"]!!.jsonPrimitive.boolean)
        assertEquals(
            "demo_add",
            manager.lastInvocation!!.entry.let {
                (it as io.legado.pack.api.PackEntryPoint.McpTool).name
            },
        )

        registry.replaceAll(emptyList())
        assertFalse(server.tools.containsKey("demo_add"))
        first.awaitNotification(toolListChanged)
        second.awaitNotification(toolListChanged)
        // The two `awaitNotification` calls above must have consumed exactly
        // the registration and the revocation notification. A warm-up canary
        // arriving late would displace one of them, and the displaced
        // notification would stay unconsumed here -- but only a canary the
        // SDK has already delivered: one whose emission coroutine has not run
        // by the final count check below escapes this assertion (declared
        // limitation, see awaitSessionSubscriptions).
        first.assertNoResidue()
        second.assertNoResidue()

        bridge.close()
        server.close()
    }

    @Test
    fun `a canary still in flight during warm-up cannot leak into the formal assertions`() = runBlocking {
        val server = newServer()
        val manager = FakePackManager(listOf(PackFixtures.info()))
        val permissions = PackToolPermissionStore(
            Files.createTempDirectory("mcp-late").resolve("grants.json").toFile()
        )
        val bridge = PackMcpBridge(server, { manager }, permissions)
        val registry = PackRegistry(reservedToolNames = HostMcpToolNames.RESERVED)
        bridge.attach(registry)

        val first = RecordingTransport()
        // Each delayed send emulates a canary stuck inside the SDK collector
        // pipeline: the message has left the SharedFlow but has not reached
        // the transport queue yet, so neither the queue nor `sentMessages`
        // can see it. The first delayed canary lands only after round 1's
        // one-second window, which forces a second subscription round, and
        // the second delayed canary is still in flight when the round loop
        // exits -- the interleaving in which the old 500ms quiet-window drain
        // returned a "clean" baseline while a canary was on its way.
        val second = RecordingTransport(delayedSends = 2, sendDelayMillis = 1_100L)
        server.createSession(first)
        server.createSession(second)
        awaitSessionSubscriptions(server, first, second)
        // The delayed first canary can never land inside round 1's window, so
        // the warm-up always needed a second round to prove `second`.
        assertTrue(server.tools.containsKey("subscription_canary_2"))

        val registration = PackRegistration(
            PackFixtures.ref(),
            PackFixtures.manifest(),
        )
        registry.replaceAll(listOf(registration))
        assertTrue(server.tools.containsKey("demo_add"))
        assertEquals(toolListChanged, first.awaitNotification(toolListChanged).method)
        assertEquals(toolListChanged, second.awaitNotification(toolListChanged).method)

        registry.replaceAll(emptyList())
        assertFalse(server.tools.containsKey("demo_add"))
        first.awaitNotification(toolListChanged)
        second.awaitNotification(toolListChanged)
        // The late canary must have been drained by the warm-up barrier (the
        // prompt marker cannot be consumed before it); if it had leaked into
        // these assertions it would have displaced either the registration
        // or the revocation notification, which would stay unconsumed here.
        first.assertNoResidue()
        second.assertNoResidue()

        bridge.close()
        server.close()
    }

    @Test
    fun `write tools are denied until the host grants them`() = runBlocking {
        val server = newServer()
        val manifest = PackFixtures.manifest(toolName = "demo_write", isWrite = true)
        val registration = PackRegistration(PackFixtures.ref(manifest), manifest)
        val manager = FakePackManager(listOf(PackFixtures.info(manifest)))
        val permissions = PackToolPermissionStore(
            Files.createTempDirectory("mcp-write").resolve("grants.json").toFile()
        )
        val bridge = PackMcpBridge(server, { manager }, permissions)
        val registry = PackRegistry(reservedToolNames = HostMcpToolNames.RESERVED)
        bridge.attach(registry)
        registry.replaceAll(listOf(registration))

        val denied = bridge.invoke(
            registration,
            registration.manifest.mcpTools.single(),
            "{}",
            "client",
        )
        assertEquals("PERMISSION_DENIED", denied.structuredContent!!["code"]!!.jsonPrimitive.content)
        assertTrue(denied.isError == true)
        assertNull(manager.lastInvocation)

        assertTrue(
            permissions.setWriteToolEnabled(
                registration.ref,
                registration.manifest,
                "demo_write",
                true,
            )
        )
        val allowed = bridge.invoke(
            registration,
            registration.manifest.mcpTools.single(),
            "{}",
            "client",
        )
        assertEquals("OK", allowed.structuredContent!!["code"]!!.jsonPrimitive.content)

        bridge.close()
        server.close()
    }

    @Test
    fun `a stale registration epoch is rejected instead of dispatching`() = runBlocking {
        val server = newServer()
        val manifest = PackFixtures.manifest()
        val oldRegistration = PackRegistration(PackFixtures.ref(manifest, epoch = 1), manifest)
        val newInfo = PackFixtures.info(manifest, epoch = 2)
        val manager = FakePackManager(listOf(newInfo))
        val permissions = PackToolPermissionStore(
            Files.createTempDirectory("mcp-epoch").resolve("grants.json").toFile()
        )
        val bridge = PackMcpBridge(server, { manager }, permissions)
        val registry = PackRegistry(reservedToolNames = HostMcpToolNames.RESERVED)
        bridge.attach(registry)
        registry.replaceAll(listOf(PackRegistration(newInfo.ref, manifest)))

        val result = bridge.invoke(
            oldRegistration,
            oldRegistration.manifest.mcpTools.single(),
            "{}",
            "client",
        )
        assertEquals(PackErrorCode.INVALID_REQUEST.wire, result.structuredContent!!["code"]!!.jsonPrimitive.content)
        assertTrue(result.isError == true)

        bridge.close()
        server.close()
    }

    @Test
    fun `reserved tool names are rejected by the registry and never exposed`() = runBlocking {
        val server = newServer()
        val manifest = PackFixtures.manifest(toolName = "save_source")
        val manager = FakePackManager(listOf(PackFixtures.info(manifest)))
        val permissions = PackToolPermissionStore(
            Files.createTempDirectory("mcp-reserved").resolve("grants.json").toFile()
        )
        val bridge = PackMcpBridge(server, { manager }, permissions)
        val registry = PackRegistry(reservedToolNames = HostMcpToolNames.RESERVED)
        bridge.attach(registry)

        val result = registry.replaceAll(
            listOf(PackRegistration(PackFixtures.ref(manifest), manifest))
        )
        assertTrue(result is io.legado.pack.registry.RegistrationResult.Rejected)
        assertFalse(server.tools.containsKey("save_source"))
        assertTrue(registry.snapshot().registrations.isEmpty())

        bridge.close()
        server.close()
    }

    @Test
    fun `removed tools fail a late call through the stale closure`() = runBlocking {
        val server = newServer()
        val registration = PackRegistration(PackFixtures.ref(), PackFixtures.manifest())
        val manager = FakePackManager(listOf(PackFixtures.info()))
        val permissions = PackToolPermissionStore(
            Files.createTempDirectory("mcp-removed").resolve("grants.json").toFile()
        )
        val bridge = PackMcpBridge(server, { manager }, permissions)
        val registry = PackRegistry(reservedToolNames = HostMcpToolNames.RESERVED)
        bridge.attach(registry)
        registry.replaceAll(listOf(registration))
        assertTrue(server.tools.containsKey("demo_add"))

        registry.replaceAll(emptyList())
        manager.infos.clear()

        val result = bridge.invoke(
            registration,
            registration.manifest.mcpTools.single(),
            "{}",
            "client",
        )
        assertEquals(PackErrorCode.PACK_NOT_FOUND.wire, result.structuredContent!!["code"]!!.jsonPrimitive.content)

        bridge.close()
        server.close()
    }

    private fun newServer(): Server = Server(
        serverInfo = Implementation(name = "test", version = "1"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
                prompts = ServerCapabilities.Prompts(listChanged = true),
                resources = ServerCapabilities.Resources(),
                logging = ServerCapabilities.Logging,
            ),
        ),
    )

    /**
     * The SDK (0.15.0) delivers feature notifications through a SharedFlow with
     * replay = 0 that is fed by coroutines launched on Dispatchers.Default;
     * `addTool`/`addPrompt` return before those coroutines have even run, and
     * each session's collector coroutine only starts collecting some time after
     * `createSession` returns. Both delays are unbounded, and the transport
     * counters can only see messages that already reached `send`, so counting
     * or waiting for silence cannot prove that nothing is still queued inside
     * the SDK.
     *
     * The warm-up therefore establishes the baseline with a completion barrier
     * instead of quiet windows:
     *
     * 1. Subscription rounds: exactly one canary emission per round, and every
     *    session -- including ones that already proved themselves -- must
     *    *consume* a notification in that round before the next round starts.
     *    A transport that has not subscribed yet burns only its own deadline
     *    (the round's canary is dropped for it, replay = 0); a subscribed
     *    transport always receives the round's canary eventually (the flow
     *    buffer is unbounded and per-collector delivery is FIFO), so waiting
     *    it out keeps the queue aligned with the emission sequence instead of
     *    stranding the canary in a queue for the rounds that follow.
     * 2. Completion barrier: the last warm-up emission is a *prompt* canary,
     *    the only prompt notification the warm-up ever produces, and nothing
     *    is emitted after it. Waiting for every transport to consume that
     *    marker drains, per collector FIFO order, every tool canary still
     *    queued or in flight in front of it -- including canaries whose
     *    emission coroutine ran late. Only at that point do the counters mean
     *    something: sent == consumed proves every queue is empty.
     *
     * The one case no black-box barrier can observe is a canary whose emission
     * coroutine is starved past the marker itself. The test bodies therefore
     * end with `assertNoResidue`, which detects exactly the residue the SDK
     * already delivered but left unconsumed at check time: a late canary that
     * displaces a notification the body is awaiting makes the displaced
     * message fail loudly. A canary whose emission has not run by the final
     * count check escapes both the barrier and the counter comparison -- that
     * residue is unobservable from the outside and remains a declared,
     * unverified limitation of this warm-up (the SDK emits from a
     * Dispatchers.Default launch with no completion barrier to hook).
     */
    private suspend fun awaitSessionSubscriptions(
        server: Server,
        vararg transports: RecordingTransport,
    ) {
        var round = 0
        while (transports.any { it.consumedMessages == 0 }) {
            round += 1
            check(round <= CANARY_ROUNDS) {
                "sessions did not subscribe to tool notifications " +
                    "within $CANARY_ROUNDS canary rounds"
            }
            server.addTool(
                name = "subscription_canary_$round",
                description = "awaitSessionSubscriptions canary; never invoked",
                inputSchema = requireNotNull(PackMcpSchema.toToolSchema(PackFixtures.schema())),
            ) { _ -> CallToolResult(content = emptyList()) }
            transports.forEach { transport ->
                if (transport.consumedMessages == 0) {
                    // One timeout per still-unproven transport: a transport that
                    // has not subscribed yet burns only its own deadline.
                    withTimeoutOrNull(CANARY_ROUND_TIMEOUT_MS) {
                        transport.awaitNotification(toolListChanged)
                    }
                } else {
                    // A proven transport is subscribed, so this round's canary
                    // is already on its way to it; waiting it out is what keeps
                    // the rounds aligned and lets the marker below terminate
                    // the warm-up emission sequence.
                    transport.awaitNotification(toolListChanged)
                }
            }
        }
        server.addPrompt(
            name = "subscription_barrier",
            description = "awaitSessionSubscriptions completion barrier; never invoked",
        ) { _ -> GetPromptResult(messages = emptyList(), description = "") }
        transports.forEach { it.awaitNotification(promptListChanged) }
        transports.forEach { it.assertNoResidue() }
    }

    private class RecordingTransport(
        private val delayedSends: Int = 0,
        private val sendDelayMillis: Long = 0L,
    ) : Transport {
        private val messages = Channel<JSONRPCMessage>(Channel.UNLIMITED)
        private var closeBlock: (() -> Unit)? = null
        private var remainingDelayedSends = delayedSends

        /** Messages queued through [send]; counted only after the enqueue. */
        var sentMessages: Int = 0
            private set

        /** Messages taken off the queue by the test; the consumption baseline. */
        var consumedMessages: Int = 0
            private set

        override suspend fun start() = Unit

        override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
            // While this delay runs the message is already inside the SDK
            // collector pipeline but not yet in the queue and not yet counted:
            // exactly the in-flight window the warm-up barrier has to wait out.
            if (remainingDelayedSends > 0) {
                remainingDelayedSends -= 1
                delay(sendDelayMillis)
            }
            messages.send(message)
            sentMessages += 1
        }

        override suspend fun close() {
            closeBlock?.invoke()
        }

        override fun onClose(block: () -> Unit) {
            closeBlock = block
        }

        override fun onError(block: (Throwable) -> Unit) = Unit

        override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) = Unit

        suspend fun awaitNotification(method: String): JSONRPCNotification =
            withTimeout(3_000L) {
                while (true) {
                    val message = messages.receive()
                    consumedMessages += 1
                    if (message is JSONRPCNotification && message.method == method) {
                        return@withTimeout message
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }

        /**
         * Everything the SDK has delivered must have been consumed by the
         * notification it was meant for. Warm-up residue already delivered
         * but left unconsumed here (including a canary that arrived late and
         * displaced a notification the test body awaited) fails loudly
         * instead of silently satisfying a later assertion. What this cannot
         * see is an emission the SDK has not delivered at all by the time of
         * the check: sentMessages only counts messages that reached the
         * transport queue, so a starved emission coroutine escaping the
         * warm-up marker stays undetected (declared limitation).
         */
        fun assertNoResidue() {
            check(sentMessages == consumedMessages) {
                "recording transport finished with " +
                    "${sentMessages - consumedMessages} delivered message(s) unconsumed: " +
                    "a warm-up canary displaced a notification the test awaited"
            }
        }
    }

    private companion object {
        const val CANARY_ROUNDS = 10
        const val CANARY_ROUND_TIMEOUT_MS = 1_000L
    }
}