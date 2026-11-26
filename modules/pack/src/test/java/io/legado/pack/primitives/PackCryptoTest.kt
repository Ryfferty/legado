package io.legado.pack.primitives

import io.legado.pack.api.PackCancelledException
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class PackCryptoTest {

    private val crypto = PackCryptoImpl(PackEpochGuard { true })

    @Test
    fun md5KnownVector() {
        val digest = crypto.digest("MD5", "abc".toByteArray(Charsets.US_ASCII))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hex(digest))
    }

    @Test
    fun sha256KnownVector() {
        val digest = crypto.digest("SHA-256", "abc".toByteArray(Charsets.US_ASCII))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(digest),
        )
    }

    @Test
    fun hmacSha256KnownVector() {
        val result = crypto.hmac(
            "HmacSHA256",
            "key".toByteArray(Charsets.US_ASCII),
            "The quick brown fox jumps over the lazy dog".toByteArray(Charsets.US_ASCII),
        )
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            hex(result),
        )
    }

    @Test
    fun aesCbcNoPaddingKnownVector() {
        val key = parseHex("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = parseHex("000102030405060708090a0b0c0d0e0f")
        val plaintext = parseHex("6bc1bee22e409f96e93d7e117393172a")
        val encrypted = crypto.cipher("AES/CBC/NoPadding", key, iv, plaintext)
        assertEquals("7649abac8119b246cee98e9b12e9197d", hex(encrypted))
    }

    @Test
    fun rc4KnownVector() {
        // JCE ARCFOUR requires a 40-1024 bit key, so the classic 3-byte "Key"
        // vector is rejected by the provider; use the equally public 48-bit one.
        val encrypted = crypto.cipher(
            "RC4",
            "Secret".toByteArray(Charsets.US_ASCII),
            null,
            "Attack at dawn".toByteArray(Charsets.US_ASCII),
        )
        assertEquals("45a01f645fc35b383552544b9bf5", hex(encrypted))
    }

    @Test
    fun unsupportedAlgorithmIsExplicit() {
        try {
            crypto.digest("definitely-not-a-digest", byteArrayOf(1))
            fail("expected CRYPTO_UNSUPPORTED")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.CRYPTO_UNSUPPORTED, e.diagnostic.code)
        }
        try {
            crypto.cipher("TOTALLY-FAKE/CBC/PKCS5Padding", byteArrayOf(1), null, byteArrayOf())
            fail("expected CRYPTO_UNSUPPORTED")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.CRYPTO_UNSUPPORTED, e.diagnostic.code)
        }
    }

    @Test
    fun sm4IsSupportedOnlyWithARealProvider() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (it + 1).toByte() }
        val plaintext = "sm4-vector".toByteArray(Charsets.UTF_8)
        val providerSupports = try {
            Cipher.getInstance("SM4/CBC/PKCS5Padding")
            true
        } catch (_: Exception) {
            false
        }
        if (providerSupports) {
            val encrypted = crypto.cipher("SM4/CBC/PKCS5Padding", key, iv, plaintext)
            val decrypted = Cipher.getInstance("SM4/CBC/PKCS5Padding").run {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "SM4"),
                    IvParameterSpec(iv),
                )
                doFinal(encrypted)
            }
            assertArrayEquals(plaintext, decrypted)
        } else {
            try {
                crypto.cipher("SM4/CBC/PKCS5Padding", key, iv, plaintext)
                fail("expected explicit SM4 provider failure")
            } catch (e: PackException) {
                assertEquals(PackErrorCode.CRYPTO_UNSUPPORTED, e.diagnostic.code)
            }
        }
    }

    @Test
    fun rsaEncryptsWithEncodedPublicKey() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val plaintext = "rsa payload".toByteArray(Charsets.UTF_8)
        val encrypted = crypto.cipher(
            "RSA/ECB/PKCS1Padding",
            keyPair.public.encoded,
            null,
            plaintext,
        )
        val decrypted = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, keyPair.private)
            doFinal(encrypted)
        }
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun staleEpochRejectsCrypto() {
        val active = AtomicBoolean(true)
        val guarded = PackCryptoImpl(PackEpochGuard { active.get() })
        guarded.digest("SHA-256", byteArrayOf(1))
        active.set(false)
        try {
            guarded.digest("SHA-256", byteArrayOf(1))
            fail("expected epoch failure")
        } catch (_: PackCancelledException) {
        }
    }

    @Test
    fun desAndTripleDesAreAvailable() {
        val desKey = ByteArray(8) { (it + 1).toByte() }
        val desPlain = "12345678".toByteArray(Charsets.US_ASCII)
        val desEncrypted = crypto.cipher("DES/ECB/PKCS5Padding", desKey, null, desPlain)
        assertTrue(desEncrypted.isNotEmpty())

        val tripleKey = ByteArray(24) { (it + 1).toByte() }
        val tripleEncrypted = crypto.cipher("3DES/ECB/PKCS5Padding", tripleKey, null, desPlain)
        assertTrue(tripleEncrypted.isNotEmpty())
    }

    private fun hex(bytes: ByteArray): String {
        val alphabet = "0123456789abcdef"
        return buildString {
            bytes.forEach {
                val value = it.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
            }
        }
    }

    private fun parseHex(text: String): ByteArray {
        require(text.length % 2 == 0)
        return ByteArray(text.length / 2) { index ->
            text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}