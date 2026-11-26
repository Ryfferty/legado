package io.legado.app.help.pack

import io.legado.app.data.entities.BookSource
import io.legado.pack.api.PackCaller
import io.legado.pack.api.PackEntryPoint
import io.legado.pack.api.PackState
import io.legado.pack.registry.PackRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class JsExtensionsPackBridgeTest {

    private lateinit var manager: FakePackManager
    private lateinit var source: BookSource

    @Before
    fun setUp() {
        val root = Files.createTempDirectory("pack-js-bridge").toFile()
        manager = FakePackManager(listOf(PackFixtures.info()))
        val registry = PackRegistry(reservedToolNames = HostMcpToolNames.RESERVED)
        PackHost.installForTest(
            environment = FakePackHostEnvironment(root),
            manager = manager,
            registry = registry,
            toolPermissions = PackToolPermissionStore(root.resolve("grants.json")),
        )
        source = BookSource(
            bookSourceUrl = "https://example.com/book",
            bookSourceName = "Example",
        )
    }

    @After
    fun tearDown() {
        PackHost.resetForTest()
    }

    @Test
    fun `engineCall uses the live source identity and source exported command`() {
        val result = source.engineCall(
            "demo.pack",
            "source_echo",
            "{\"value\":\"hello\"}",
        )

        assertEquals("{\"ok\":true}", result)
        val invocation = manager.lastBlockingInvocation!!
        assertEquals(PackEntryPoint.SourceCommand("source_echo"), invocation.entry)
        assertEquals("{\"value\":\"hello\"}", invocation.argJson)
        val caller = invocation.caller as PackCaller.Source
        assertEquals(PackSourceIdentity.of(source), caller.sourceKey)
    }

    @Test
    fun `engineCall rejects non exported commands missing packs and non JSON arguments`() {
        assertThrows(IllegalStateException::class.java) {
            source.engineCall("demo.pack", "add", "{}")
        }
        assertNull(manager.lastBlockingInvocation)

        assertThrows(IllegalStateException::class.java) {
            source.engineCall("missing.pack", "source_echo", "{}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            source.engineCall("demo.pack", "source_echo", "not-json")
        }
    }

    @Test
    fun `packKV is isolated by the real source identity`() {
        assertTrue(source.packKV.put("demo.pack", "token", "alpha"))
        assertEquals("alpha", source.packKV.get("demo.pack", "token"))

        val other = BookSource(
            bookSourceUrl = "https://example.com/other",
            bookSourceName = "Other",
        )
        assertNull(other.packKV.get("demo.pack", "token"))
        assertTrue(other.packKV.put("demo.pack", "token", "beta"))
        assertEquals("alpha", source.packKV.get("demo.pack", "token"))
        assertEquals("beta", other.packKV.get("demo.pack", "token"))
    }

    @Test
    fun `narrow crypto bridge keeps legacy names and values`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", source.packCrypto.md5("abc"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", source.md5("abc"))
        assertEquals(
            source.md5Encode("abc"),
            source.packCrypto.md5Encode("abc"),
        )
        assertEquals("3cd24fb0d6963f7d", source.packCrypto.md5Encode16("abc"))
        assertEquals(
            source.digestHex("abc", "SHA-256"),
            source.packCrypto.digestHex("abc", "SHA-256"),
        )
    }

    @Test
    fun `disabled packs are rejected before a call is recorded`() {
        manager.add(PackFixtures.info(state = PackState.DISABLED))
        assertThrows(IllegalStateException::class.java) {
            source.engineCall("demo.pack", "source_echo", "{}")
        }
        assertNull(manager.lastBlockingInvocation)
    }

    @Test
    fun `broken packs are admitted so a successful call can self heal`() {
        manager.add(PackFixtures.info(state = PackState.BROKEN))
        assertEquals("{\"ok\":true}", source.engineCall("demo.pack", "source_echo", "{}"))
        assertNotNull(manager.lastBlockingInvocation)
    }

    @Test
    fun `needs restart packs are rejected with the restart code`() {
        manager.add(PackFixtures.info(state = PackState.NEEDS_RESTART))
        val error = assertThrows(IllegalStateException::class.java) {
            source.engineCall("demo.pack", "source_echo", "{}")
        }
        assertTrue(error.message!!.contains("[NEEDS_RESTART]"))
        assertNull(manager.lastBlockingInvocation)
    }
}