package io.legado.pack.runtime.js

import io.legado.pack.api.*
import io.legado.pack.runtime.runtimeFailure
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Host-only facade. Never wrapped with javaToJS or exposed as a Java object. */
internal class ContextPrimitives(private val context: PackContext) : PackJsPrimitiveFacade {
    private fun requireCapability(capability: PackCapability) {
        if (capability !in context.manifest.capabilities) {
            runtimeFailure(PackErrorCode.PERMISSION_DENIED, "undeclared primitive: $capability")
        }
    }

    /**
     * HTTP admission, factored out so the JS bridge can run it before
     * serializing the request: an undeclared network capability must fail
     * before any codec limit can mask it. No network I/O is involved.
     */
    fun ensureHttpAdmitted() {
        requireCapability(PackCapability.HTTP)
        if (context.manifest.network == NetworkPolicy.None) {
            runtimeFailure(PackErrorCode.PERMISSION_DENIED, "network is not declared")
        }
    }

    private fun key(key: String) {
        requireCapability(PackCapability.KV)
        if (key.length > context.limits.maxKvKeyChars) {
            runtimeFailure(PackErrorCode.IMPORT_LIMIT_EXCEEDED, "KV key limit exceeded")
        }
    }

    override fun kvGet(key: String): String? {
        key(key)
        return context.kv.get(key)
    }

    override fun kvPut(key: String, value: String) {
        key(key)
        if (value.toByteArray(Charsets.UTF_8).size > context.limits.maxKvValueBytes) {
            runtimeFailure(PackErrorCode.IMPORT_LIMIT_EXCEEDED, "KV value limit exceeded")
        }
        context.kv.put(key, value)
    }

    override fun kvRemove(key: String) {
        key(key)
        context.kv.remove(key)
    }

    override fun log(level: String, message: String) {
        requireCapability(PackCapability.LOG)
        val parsed = PackLogLevel.entries.firstOrNull { it.name == level.uppercase(Locale.ROOT) }
            ?: runtimeFailure(PackErrorCode.INVALID_REQUEST, "invalid log level")
        context.logger.log(parsed, message)
    }

    override fun invokeComponent(componentId: String, command: String, argJson: String, call: PackCallContext): String {
        call.checkActive()
        if (context.manifest.componentById(componentId) == null ||
            context.manifest.commandByName(command)?.componentId != componentId
        ) runtimeFailure(PackErrorCode.COMPONENT_MISSING, "undeclared component/command")
        return context.components.invoke(componentId, command, argJson, call).also { call.checkActive() }
    }

    override fun http(requestJson: String, call: PackCallContext): String {
        ensureHttpAdmitted()
        call.checkActive()
        val request = PackJsHttpJson.decodeRequest(requestJson)
        val limit = minOf(
            request.maxResponseBytes ?: context.limits.maxHttpResponseBytes,
            context.limits.maxHttpResponseBytes,
            PackJsHttpJson.MAX_RESPONSE_BODY_BYTES,
        )
        return context.http.execute(
            PackHttpRequest(request.method, request.url, request.headers, request.body, request.followRedirects, limit), call,
        ).use { response ->
            if ((response.contentLength ?: 0L) > limit) {
                runtimeFailure(PackErrorCode.IMPORT_LIMIT_EXCEEDED, "HTTP response limit exceeded")
            }
            val bytes = ByteArrayOutputStream()
            response.bodyStream().use { input ->
                val buffer = ByteArray(8192)
                var size = 0L
                while (true) {
                    call.checkActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (count == 0) continue
                    size += count
                    if (size > limit) runtimeFailure(PackErrorCode.IMPORT_LIMIT_EXCEEDED, "HTTP response limit exceeded")
                    bytes.write(buffer, 0, count)
                }
            }
            call.checkActive()
            PackJsHttpJson.encodeResponse(
                PackJsHttpResponse(response.statusCode, response.headers, response.finalUrl, response.contentLength, bytes.toByteArray()),
            )
        }
    }

    override fun cryptoDigest(algorithm: String, dataBase64: String): String {
        requireCapability(PackCapability.CRYPTO)
        return BinaryText.encode(context.crypto.digest(algorithm, BinaryText.decode(dataBase64)))
    }

    override fun cryptoHmac(algorithm: String, keyBase64: String, dataBase64: String): String {
        requireCapability(PackCapability.CRYPTO)
        return BinaryText.encode(context.crypto.hmac(algorithm, BinaryText.decode(keyBase64), BinaryText.decode(dataBase64)))
    }

    override fun cryptoCipher(spec: String, keyBase64: String, ivBase64: String?, dataBase64: String): String {
        requireCapability(PackCapability.CRYPTO)
        return BinaryText.encode(context.crypto.cipher(spec, BinaryText.decode(keyBase64), ivBase64?.let(BinaryText::decode), BinaryText.decode(dataBase64)))
    }
}

/** RFC 4648, usable on minSdk 23 and the plain JVM without Android Base64. */
internal object BinaryText {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String = buildString {
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index++].toInt() and 255
            val second = if (index < bytes.size) bytes[index++].toInt() and 255 else -1
            val third = if (index < bytes.size) bytes[index++].toInt() and 255 else -1
            append(ALPHABET[first ushr 2])
            append(ALPHABET[((first and 3) shl 4) or (if (second < 0) 0 else second ushr 4)])
            append(if (second < 0) '=' else ALPHABET[((second and 15) shl 2) or (if (third < 0) 0 else third ushr 6)])
            append(if (third < 0) '=' else ALPHABET[third and 63])
        }
    }

    fun decode(text: String): ByteArray {
        if (text.length % 4 != 0 || text.length > PackJsProtocol.MAX_JSON_CHARS) invalid()
        val out = ByteArrayOutputStream(text.length / 4 * 3)
        var index = 0
        while (index < text.length) {
            val a = ALPHABET.indexOf(text[index++])
            val b = ALPHABET.indexOf(text[index++])
            val cChar = text[index++]
            val dChar = text[index++]
            val c = ALPHABET.indexOf(cChar)
            val d = ALPHABET.indexOf(dChar)
            if (a < 0 || b < 0 || (c < 0 && cChar != '=') || (d < 0 && dChar != '=')) invalid()
            if (c < 0 && (d >= 0 || b and 15 != 0)) invalid()
            if (c >= 0 && d < 0 && c and 3 != 0) invalid()
            if ((c < 0 || d < 0) && index != text.length) invalid()
            out.write((a shl 2) or (b ushr 4))
            if (c >= 0) out.write(((b and 15) shl 4) or (c ushr 2))
            if (d >= 0) out.write(((c and 3) shl 6) or d)
        }
        return out.toByteArray()
    }

    private fun invalid(): Nothing = runtimeFailure(PackErrorCode.INVALID_REQUEST, "invalid padded Base64")
}
