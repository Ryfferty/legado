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
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.channels.Channel
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
                resources = ServerCapabilities.Resources(),
                logging = ServerCapabilities.Logging,
            ),
        ),
    )

    /**
     * The SDK (0.15.0) delivers feature notifications through a SharedFlow with
     * replay = 0; each session's collector coroutine runs on Dispatchers.Default
     * and only starts collecting some time after `createSession` returns, so a
     * `replaceAll` immediately afterwards can race the collector's subscription
     * and have its notification dropped with no way to recover it.
     *
     * The warm-up therefore establishes two explicit baselines per session
     * before the test body runs:
     *
     * 1. A subscription witness: every transport must *consume* a canary
     *    notification of its own. A send-side flag is not a witness — it used
     *    to be flipped before the message was even queued, and a round that
     *    spent one shared timeout waiting for the first transport never waited
     *    for (or consumed) the second transport's canary, which then lingered
     *    as residue for the rounds that followed.
     * 2. A consumption baseline: every queue is drained until each transport
     *    has consumed everything the warm-up sent it, so the assertions in the
     *    test body can only be satisfied by the notifications their own
     *    actions produce.
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
            // One timeout per still-unproven transport: a transport that has
            // not subscribed yet burns only its own deadline, and a transport
            // that proves itself early is left alone instead of sharing one
            // sequential wait whose timeout can strand another's canary.
            transports.filter { it.consumedMessages == 0 }.forEach { transport ->
                withTimeoutOrNull(CANARY_ROUND_TIMEOUT_MS) {
                    transport.awaitNotification(toolListChanged)
                }
            }
        }
        // The witnesses prove each collector ran at some point; canaries from
        // earlier rounds may still sit in a queue or be in flight behind a
        // slow collector. Drain every queue until it stays quiet so each
        // session starts the test body from one known-empty baseline.
        transports.forEach { it.drainNotifications() }
    }

    private class RecordingTransport : Transport {
        private val messages = Channel<JSONRPCMessage>(Channel.UNLIMITED)
        private var closeBlock: (() -> Unit)? = null

        /** Messages queued through [send]; counted only after the enqueue. */
        var sentMessages: Int = 0
            private set

        /** Messages taken off the queue by the test; the consumption baseline. */
        var consumedMessages: Int = 0
            private set

        override suspend fun start() = Unit

        override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
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
         * Consumes everything queued until the queue stays quiet for a whole
         * drain window, then requires everything sent to have been consumed.
         * Warm-up residue left behind here would otherwise satisfy a later
         * `awaitNotification` that was meant for the notification the test
         * body itself triggered.
         */
        suspend fun drainNotifications() {
            while (true) {
                val message = withTimeoutOrNull(DRAIN_QUIET_TIMEOUT_MS) { messages.receive() }
                if (message == null) {
                    break
                }
                consumedMessages += 1
            }
            check(sentMessages == consumedMessages) {
                "recording transport drained with " +
                    "${sentMessages - consumedMessages} message(s) still queued or in flight"
            }
        }
    }

    private companion object {
        const val CANARY_ROUNDS = 10
        const val CANARY_ROUND_TIMEOUT_MS = 1_000L
        const val DRAIN_QUIET_TIMEOUT_MS = 500L
    }
}