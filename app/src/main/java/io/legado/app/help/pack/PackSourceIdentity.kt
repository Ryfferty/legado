package io.legado.app.help.pack

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.source.getSourceType
import java.security.MessageDigest

/**
 * Host-derived identity of a book/RSS source. Scripts never supply this value;
 * it is always computed from the live [BaseSource] instance.
 *
 * The plain form is `sourceType|sourceKey`. Source keys are usually URLs and
 * may exceed the PackCaller limit, so long identities are replaced by a
 * stable SHA-256 digest. The same function is used for `java.engineCall`
 * caller identity and for `java.packKV` namespaces, keeping the two views
 * aligned.
 */
object PackSourceIdentity {

    private const val MAX_SOURCE_KEY_CHARS = 256

    fun of(source: BaseSource?): String? {
        source ?: return null
        val key = source.getKey()
        if (key.isBlank()) return null
        val type = runCatching { source.getSourceType().toString() }
            .getOrElse { source.javaClass.name }
        val raw = "$type|$key"
        return if (raw.length <= MAX_SOURCE_KEY_CHARS) {
            raw
        } else {
            "sha256:" + sha256Hex(raw)
        }
    }

    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        val alphabet = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(alphabet[unsigned ushr 4])
                append(alphabet[unsigned and 0x0f])
            }
        }
    }
}