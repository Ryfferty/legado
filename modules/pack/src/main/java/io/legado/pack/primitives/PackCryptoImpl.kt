package io.legado.pack.primitives

import io.legado.pack.api.PackCrypto
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.Mac
import javax.crypto.NoSuchPaddingException
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JCA-backed crypto facade.
 *
 * The framework does not ship its own cryptography and never substitutes an
 * algorithm. SM4 is available only when a real JCA provider implements it; on
 * a host without such a provider the call fails with CRYPTO_UNSUPPORTED.
 *
 * [PackCrypto.cipher] has no mode parameter in the public contract, so this
 * implementation performs encryption. Decryption is a contract gap, not a
 * silently inferred reversible operation.
 */
public class PackCryptoImpl(
    private val guard: PackEpochGuard,
) : PackCrypto {

    override fun digest(algorithm: String, data: ByteArray): ByteArray {
        guard.ensureActive()
        val mapped = mapDigest(algorithm)
        val digest = try {
            MessageDigest.getInstance(mapped)
        } catch (e: NoSuchAlgorithmException) {
            throw unsupported("digest", algorithm)
        } catch (e: Exception) {
            throw invalid("digest", "cannot initialise '$algorithm'", e)
        }
        return try {
            digest.digest(data)
        } catch (e: Exception) {
            throw invalid("digest", "digest failed", e)
        }
    }

    override fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        guard.ensureActive()
        val mapped = mapHmac(algorithm)
            ?: throw unsupported("hmac", algorithm)
        if (key.isEmpty()) {
            throw invalid("hmac", "HMAC key must not be empty")
        }
        val mac = try {
            Mac.getInstance(mapped)
        } catch (e: NoSuchAlgorithmException) {
            throw unsupported("hmac", algorithm)
        } catch (e: Exception) {
            throw invalid("hmac", "cannot initialise '$algorithm'", e)
        }
        return try {
            mac.init(SecretKeySpec(key, mapped))
            mac.doFinal(data)
        } catch (e: Exception) {
            throw invalid("hmac", "HMAC failed for '$algorithm'", e)
        }
    }

    override fun cipher(
        spec: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray,
    ): ByteArray {
        guard.ensureActive()
        val parts = spec.trim().split('/')
        if (parts.isEmpty() || parts.size > 3 || parts.any { it.isBlank() }) {
            throw invalid("cipher", "cipher spec must be ALG[/MODE[/PADDING]]")
        }
        val token = parts[0].uppercase(Locale.ROOT)
        val algorithm = when (token) {
            "AES" -> "AES"
            "DES" -> "DES"
            "DESEDE", "3DES", "TRIPLEDES", "TRIPLE-DES" -> "DESede"
            "RC4", "ARCFOUR" -> "ARCFOUR"
            "SM4" -> "SM4"
            "RSA" -> "RSA"
            else -> throw unsupported("cipher", spec)
        }
        if (algorithm == "ARCFOUR" && parts.size > 1) {
            throw invalid("cipher", "RC4 does not accept a mode or padding")
        }
        if (algorithm == "RSA" && iv != null) {
            throw invalid("cipher", "RSA does not accept an IV")
        }
        if (algorithm == "ARCFOUR" && iv != null) {
            throw invalid("cipher", "RC4 does not accept an IV")
        }
        val transformation = buildString {
            append(algorithm)
            parts.drop(1).forEach { part ->
                append('/')
                append(part.uppercase(Locale.ROOT))
            }
        }
        val cipher = try {
            Cipher.getInstance(transformation)
        } catch (e: NoSuchAlgorithmException) {
            throw unsupported(
                "cipher",
                if (algorithm == "SM4") {
                    "SM4 requires a JCA provider that actually implements SM4; " +
                        "the framework will not substitute another algorithm"
                } else {
                    transformation
                },
            )
        } catch (e: NoSuchPaddingException) {
            throw unsupported("cipher", transformation)
        } catch (e: Exception) {
            throw invalid("cipher", "cannot initialise '$transformation'", e)
        }
        return try {
            if (algorithm == "RSA") {
                val keyFactory = KeyFactory.getInstance("RSA")
                val publicKey = runCatching {
                    keyFactory.generatePublic(X509EncodedKeySpec(key))
                }.getOrNull()
                if (publicKey != null) {
                    cipher.init(Cipher.ENCRYPT_MODE, publicKey)
                } else {
                    val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(key))
                    cipher.init(Cipher.ENCRYPT_MODE, privateKey)
                }
            } else {
                val keySpec = SecretKeySpec(key, algorithm)
                if (iv != null) {
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
                } else {
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec)
                }
            }
            cipher.doFinal(data)
        } catch (e: java.security.InvalidKeyException) {
            throw invalid("cipher", "invalid key or IV for '$transformation'", e)
        } catch (e: java.security.InvalidAlgorithmParameterException) {
            throw invalid("cipher", "invalid IV for '$transformation'", e)
        } catch (e: IllegalBlockSizeException) {
            throw invalid("cipher", "invalid input length for '$transformation'", e)
        } catch (e: BadPaddingException) {
            throw invalid("cipher", "padding failure for '$transformation'", e)
        } catch (e: IllegalArgumentException) {
            throw invalid("cipher", "invalid cipher parameters", e)
        } catch (e: Exception) {
            throw invalid("cipher", "cipher operation failed for '$transformation'", e)
        }
    }

    private fun mapDigest(raw: String): String {
        val normalized = raw.trim()
        if (normalized.isEmpty()) throw unsupported("digest", raw)
        return when (normalized.uppercase(Locale.ROOT).replace("_", "-")) {
            "MD5" -> "MD5"
            "SHA1", "SHA-1" -> "SHA-1"
            "SHA224", "SHA-224" -> "SHA-224"
            "SHA256", "SHA-256" -> "SHA-256"
            "SHA384", "SHA-384" -> "SHA-384"
            "SHA512", "SHA-512" -> "SHA-512"
            "SHA3-256" -> "SHA3-256"
            "SHA3-512" -> "SHA3-512"
            "SM3" -> "SM3"
            else -> normalized
        }
    }

    private fun mapHmac(raw: String): String? {
        val compact = raw.trim().replace("-", "").replace("_", "").uppercase(Locale.ROOT)
        return when (compact) {
            "HMACMD5", "MD5" -> "HmacMD5"
            "HMACSHA1", "SHA1" -> "HmacSHA1"
            "HMACSHA224", "SHA224" -> "HmacSHA224"
            "HMACSHA256", "SHA256" -> "HmacSHA256"
            "HMACSHA384", "SHA384" -> "HmacSHA384"
            "HMACSHA512", "SHA512" -> "HmacSHA512"
            "HMACSM3", "SM3" -> "HmacSM3"
            else -> if (compact.startsWith("HMAC") && compact.length > 4) {
                "Hmac" + compact.substring(4)
            } else {
                null
            }
        }
    }

    private fun unsupported(operation: String, algorithm: String): PackException =
        PackException(
            PackDiagnostic(
                code = PackErrorCode.CRYPTO_UNSUPPORTED,
                message = "$operation algorithm is not available: $algorithm",
                path = "crypto.$operation",
            ),
        )

    private fun invalid(operation: String, message: String, cause: Throwable? = null): PackException =
        PackException(
            PackDiagnostic(
                code = PackErrorCode.INVALID_REQUEST,
                message = message,
                path = "crypto.$operation",
            ),
            cause,
        )
}