package io.legado.app.help.pack

import io.legado.pack.api.PackCapability
import io.legado.pack.api.PackException
import io.legado.pack.api.PackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PackSourceKvBridgeTest {

    @Test
    fun `values are isolated by the host derived source identity`() {
        val root = Files.createTempDirectory("pack-kv").toFile()
        val manager = FakePackManager(listOf(PackFixtures.info()))
        val first = PackKvBridge({ "1|https://example.com/a" }, manager, root)
        val second = PackKvBridge({ "1|https://example.com/b" }, manager, root)

        assertTrue(first.put("demo.pack", "token", "alpha"))
        assertEquals("alpha", first.get("demo.pack", "token"))
        assertEquals("[\"token\"]", first.keys("demo.pack"))
        assertNull(second.get("demo.pack", "token"))

        assertTrue(second.put("demo.pack", "token", "beta"))
        assertEquals("alpha", first.get("demo.pack", "token"))
        assertEquals("beta", second.get("demo.pack", "token"))

        assertTrue(first.remove("demo.pack", "token"))
        assertNull(first.get("demo.pack", "token"))
        assertFalse(first.remove("demo.pack", "token"))
    }

    @Test
    fun `missing KV capability disabled pack and missing identity fail closed`() {
        val root = Files.createTempDirectory("pack-kv").toFile()
        val manifest = PackFixtures.manifest()
        val noKv = manifest.copy(
            capabilities = manifest.capabilities - PackCapability.KV,
        )
        val manager = FakePackManager(listOf(PackFixtures.info(noKv)))
        val bridge = PackKvBridge({ "1|https://example.com" }, manager, root)

        val noCapability = assertThrows(PackException::class.java) {
            bridge.get("demo.pack", "key")
        }
        assertEquals(
            io.legado.pack.api.PackErrorCode.PERMISSION_DENIED,
            noCapability.diagnostic.code,
        )

        manager.add(PackFixtures.info(manifest, state = PackState.DISABLED))
        val disabled = assertThrows(PackException::class.java) {
            bridge.get("demo.pack", "key")
        }
        assertEquals(
            io.legado.pack.api.PackErrorCode.PACK_DISABLED,
            disabled.diagnostic.code,
        )

        manager.add(PackFixtures.info(manifest))
        val noIdentity = PackKvBridge({ null }, manager, root)
        val identityFailure = assertThrows(PackException::class.java) {
            noIdentity.put("demo.pack", "key", "value")
        }
        assertEquals(
            io.legado.pack.api.PackErrorCode.PERMISSION_DENIED,
            identityFailure.diagnostic.code,
        )

        val missing = assertThrows(PackException::class.java) {
            PackKvBridge({ "1|https://example.com" }, FakePackManager(), root)
                .get("missing.pack", "key")
        }
        assertEquals(
            io.legado.pack.api.PackErrorCode.PACK_NOT_FOUND,
            missing.diagnostic.code,
        )
    }

    @Test
    fun `keys and values are bounded`() {
        val root = Files.createTempDirectory("pack-kv").toFile()
        val manager = FakePackManager(listOf(PackFixtures.info()))
        val bridge = PackKvBridge({ "1|https://example.com" }, manager, root)

        val keyFailure = assertThrows(PackException::class.java) {
            bridge.put("demo.pack", "x".repeat(257), "value")
        }
        assertEquals(
            io.legado.pack.api.PackErrorCode.INVALID_REQUEST,
            keyFailure.diagnostic.code,
        )

        val valueFailure = assertThrows(PackException::class.java) {
            bridge.put("demo.pack", "key", "x".repeat(1024 * 1024 + 1))
        }
        assertEquals(
            io.legado.pack.api.PackErrorCode.INVALID_REQUEST,
            valueFailure.diagnostic.code,
        )
    }
}