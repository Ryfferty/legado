package io.legado.pack.primitives

import io.legado.pack.api.JsonValue
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.storage.AtomicFiles
import io.legado.pack.storage.PackJsonText
import io.legado.pack.storage.optionalBoolean
import io.legado.pack.storage.optionalObject
import io.legado.pack.storage.requiredArray
import io.legado.pack.storage.requiredLong
import io.legado.pack.storage.requiredString
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.File

/**
 * Cookie store owned by exactly one pack. The host's global cookie store and
 * global OkHttp client are never used.
 *
 * Cookies are persisted atomically below `<pack>/cookies/`. A corrupt or
 * oversized cookie file is moved aside and a typed diagnostic is emitted; it
 * never silently blends into another pack's cookies.
 */
public class PackCookieJar(
    private val file: File,
    private val onDiagnostic: (PackDiagnostic) -> Unit = {},
) : CookieJar {

    private val lock = Any()
    private val cookies = ArrayList<Cookie>()

    @Volatile
    private var closed = false

    init {
        load()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (closed) return emptyList()
        synchronized(lock) {
            val now = System.currentTimeMillis()
            return cookies.filter { cookie ->
                (!cookie.secure || url.isHttps) &&
                    cookie.expiresAt > now &&
                    cookie.matches(url)
            }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (closed || cookies.isEmpty()) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            cookies.forEach { cookie ->
                if (cookie.expiresAt != Long.MAX_VALUE && cookie.expiresAt <= now) return@forEach
                this.cookies.removeAll { existing ->
                    existing.name == cookie.name &&
                        existing.domain.equals(cookie.domain, ignoreCase = true) &&
                        existing.path == cookie.path
                }
                this.cookies += cookie
            }
            while (this.cookies.size > MaxCookies) {
                this.cookies.removeAt(0)
            }
            persistLocked()
        }
    }

    fun close() {
        closed = true
    }

    fun clear() {
        synchronized(lock) {
            cookies.clear()
            persistLocked()
        }
    }

    private fun load() {
        if (!file.isFile) return
        if (file.length() > MaxFileBytes) {
            quarantine("cookie file exceeds $MaxFileBytes bytes")
            return
        }
        try {
            val text = AtomicFiles.readText(file, MaxFileBytes, file.name)
            if (text.isBlank()) return
            val root = PackJsonText.parseValue(text, MaxFileChars) as? JsonValue.Obj
                ?: throw IllegalStateException("cookie file must be a JSON object")
            val array = root.requiredArray("cookies", "cookies")
            val now = System.currentTimeMillis()
            array.items.forEach { item ->
                val obj = item as? JsonValue.Obj
                    ?: throw IllegalStateException("cookie entry must be an object")
                val name = obj.requiredString("name", "cookies")
                val value = obj.requiredString("value", "cookies")
                val domain = obj.requiredString("domain", "cookies")
                val path = obj.requiredString("path", "cookies")
                val expiresAt = obj.requiredLong("expiresAt", "cookies")
                if (expiresAt != Long.MAX_VALUE && expiresAt <= now) return@forEach
                val builder = Cookie.Builder()
                    .name(name)
                    .value(value)
                    .path(path)
                    .expiresAt(expiresAt)
                if (obj.optionalBoolean("hostOnly", "cookies") == true) {
                    builder.hostOnlyDomain(domain)
                } else {
                    builder.domain(domain)
                }
                if (obj.optionalBoolean("secure", "cookies") == true) builder.secure()
                if (obj.optionalBoolean("httpOnly", "cookies") == true) builder.httpOnly()
                cookies += builder.build()
                if (cookies.size >= MaxCookies) return@forEach
            }
        } catch (e: Exception) {
            quarantine("cannot read cookie file: ${e.message}")
        }
    }

    private fun quarantine(reason: String) {
        cookies.clear()
        try {
            val corrupt = File(file.parentFile, file.name + ".corrupt")
            corrupt.delete()
            file.renameTo(corrupt)
        } catch (_: Exception) {
            // The diagnostic below is the authoritative signal.
        }
        onDiagnostic(
            PackDiagnostic(
                code = PackErrorCode.IO_FAILED,
                message = reason + "; cookie store was reset",
                path = "cookies/${file.name}",
            ),
        )
    }

    private fun persistLocked() {
        if (closed) return
        try {
            var encoded = encode()
            while (encoded.toByteArray(Charsets.UTF_8).size > MaxFileBytes && cookies.isNotEmpty()) {
                cookies.removeAt(0)
                encoded = encode()
            }
            if (encoded.toByteArray(Charsets.UTF_8).size > MaxFileBytes) {
                onDiagnostic(
                    PackDiagnostic(
                        code = PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                        message = "cookie store exceeds $MaxFileBytes bytes; not persisted",
                        path = "cookies/${file.name}",
                    ),
                )
                return
            }
            AtomicFiles.writeText(file, encoded)
        } catch (e: Exception) {
            onDiagnostic(
                PackDiagnostic(
                    code = PackErrorCode.IO_FAILED,
                    message = "cannot persist cookies: ${e.message}",
                    path = "cookies/${file.name}",
                ),
            )
        }
    }

    private fun encode(): String = buildString {
        append("{\"cookies\":[")
        cookies.forEachIndexed { index, cookie ->
            if (index > 0) append(',')
            append('{')
            append("\"name\":").append(PackJsonText.quote(cookie.name))
            append(",\"value\":").append(PackJsonText.quote(cookie.value))
            append(",\"domain\":").append(PackJsonText.quote(cookie.domain))
            append(",\"path\":").append(PackJsonText.quote(cookie.path))
            append(",\"expiresAt\":").append(cookie.expiresAt)
            append(",\"secure\":").append(cookie.secure)
            append(",\"httpOnly\":").append(cookie.httpOnly)
            append(",\"hostOnly\":").append(cookie.hostOnly)
            append('}')
        }
        append("]}")
    }

    companion object {
        private const val MaxCookies = 256
        private const val MaxFileBytes = 512L * 1024
        private const val MaxFileChars = 1024 * 1024
    }
}