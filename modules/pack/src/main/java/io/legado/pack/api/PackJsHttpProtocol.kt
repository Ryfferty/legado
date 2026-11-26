package io.legado.pack.api

import io.legado.pack.manifest.JsonStructureException
import io.legado.pack.manifest.StrictJsonParser
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Minimal JSON messages used by `PackJsPrimitiveFacade.http(requestJson, call)`.
 *
 * Request object:
 * ```
 * {
 *   "method": "POST",                 // required, uppercase HTTP token
 *   "url": "https://example.com/x",   // required, absolute http/https
 *   "headers": {"X-Test": ["a","b"]}, // optional; string or array values
 *   "bodyBase64": "AQID",             // optional; omitted/null means no body
 *   "followRedirects": true,          // optional, default true
 *   "maxResponseBytes": 65536         // optional positive integer, host cap
 * }
 * ```
 *
 * Response object:
 * ```
 * {
 *   "statusCode": 404,                // 100..599; non-2xx is still a response
 *   "headers": {"Content-Type": ["text/plain"]},
 *   "finalUrl": "https://example.com/x",
 *   "contentLength": 12,              // may be null
 *   "bodyBase64": "..."               // decoded bytes, possibly empty
 * }
 * ```
 *
 * `bodyBase64` is RFC 4648 standard Base64 with padding. Header names are HTTP
 * tokens; values must not contain CR/LF. `Host`, `Content-Length` and
 * `Transfer-Encoding` are rejected from request headers because the primitive
 * controls them. A non-2xx response is encoded as a successful response object;
 * transport/policy/size/cancel failures throw a typed [PackException] and never
 * invent an HTTP status. Request/response JSON is capped by
 * [MAX_HTTP_JSON_CHARS]; decoded request bodies by [MAX_REQUEST_BODY_BYTES];
 * decoded response bodies by [MAX_RESPONSE_BODY_BYTES]. `maxResponseBytes`
 * cannot exceed [MAX_RESPONSE_BODY_BYTES].
 *
 * The JS runtime JSON-serializes a plain JS request object and JSON-parses the
 * returned response string; it must never pass [PackHttpRequest] or
 * [PackHttpResponse] objects/streams to JS.
 */
data class PackJsHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray? = null,
    val followRedirects: Boolean = true,
    val maxResponseBytes: Long? = null,
)

data class PackJsHttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val finalUrl: String,
    val contentLength: Long?,
    val body: ByteArray,
)

object PackJsHttpJson {

    const val MAX_HTTP_JSON_CHARS: Int = 24_000_000
    const val MAX_REQUEST_BODY_BYTES: Long = 1_048_576L
    const val MAX_RESPONSE_BODY_BYTES: Long = 16_777_216L

    private const val MAX_HEADER_COUNT = 128
    private const val MAX_HEADER_NAME_CHARS = 128
    private const val MAX_HEADER_VALUE_CHARS = 8_192
    private const val MAX_URL_CHARS = 8_192
    private val METHOD = Regex("[A-Z]+")
    private val HEADER_NAME = Regex("[!#\$%&'*+.^_`|~0-9A-Za-z-]+")
    private val FORBIDDEN_REQUEST_HEADERS = setOf("host", "content-length", "transfer-encoding")
    private val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decodeRequest(json: String): PackJsHttpRequest {
        val root = parseObject(json)
        checkAllowed(root, REQUEST_FIELDS, "$")
        val rawMethod = readRequiredString(root, "method", "$")
        val method = rawMethod.uppercase(Locale.ROOT)
        if (rawMethod != method || method.length > 16 || !METHOD.matches(method)) {
            fail("$.method", "method must be an uppercase HTTP token")
        }
        val url = readRequiredString(root, "url", "$")
        validateUrl(url, "$.url")
        val headers = readHeaders(root.entries["headers"], "$.headers", request = true)
        val body = readBody(root.entries["bodyBase64"], "$.bodyBase64", MAX_REQUEST_BODY_BYTES)
        val followRedirects = readOptionalBoolean(root, "followRedirects", "$", true)
        val maxResponseBytes = readOptionalPositiveLong(root, "maxResponseBytes", "$")
        if (maxResponseBytes != null && maxResponseBytes > MAX_RESPONSE_BODY_BYTES) {
            fail("$.maxResponseBytes", "maxResponseBytes exceeds host limit $MAX_RESPONSE_BODY_BYTES")
        }
        return PackJsHttpRequest(
            method = method,
            url = url,
            headers = headers,
            body = body,
            followRedirects = followRedirects,
            maxResponseBytes = maxResponseBytes,
        )
    }

    fun decodeResponse(json: String): PackJsHttpResponse {
        val root = parseObject(json)
        checkAllowed(root, RESPONSE_FIELDS, "$")
        val statusCode = readRequiredInt(root, "statusCode", "$")
        if (statusCode !in 100..599) fail("$.statusCode", "statusCode must be between 100 and 599")
        val headers = readHeaders(root.entries["headers"], "$.headers", request = false)
        val finalUrl = readRequiredString(root, "finalUrl", "$")
        validateUrl(finalUrl, "$.finalUrl")
        val contentLength = readOptionalNonNegativeLong(root, "contentLength", "$")
        val body = readBody(root.entries["bodyBase64"], "$.bodyBase64", MAX_RESPONSE_BODY_BYTES)
            ?: ByteArray(0)
        return PackJsHttpResponse(
            statusCode = statusCode,
            headers = headers,
            finalUrl = finalUrl,
            contentLength = contentLength,
            body = body,
        )
    }

    fun encodeRequest(request: PackJsHttpRequest): String {
        validateRequest(request)
        return buildString {
            append('{')
            appendField("method", request.method)
            append(',')
            appendField("url", request.url)
            if (request.headers.isNotEmpty()) {
                append(',')
                appendFieldName("headers")
                appendHeaders(request.headers)
            }
            val body = request.body
            if (body != null) {
                append(',')
                appendFieldName("bodyBase64")
                appendJsonString(encodeBase64(body))
            }
            append(',')
            appendFieldName("followRedirects")
            append(request.followRedirects)
            if (request.maxResponseBytes != null) {
                append(',')
                appendFieldName("maxResponseBytes")
                append(request.maxResponseBytes)
            }
            append('}')
        }
    }

    fun encodeResponse(response: PackJsHttpResponse): String {
        validateResponse(response)
        return buildString {
            append('{')
            appendFieldName("statusCode")
            append(response.statusCode)
            append(',')
            appendFieldName("headers")
            appendHeaders(response.headers)
            append(',')
            appendField("finalUrl", response.finalUrl)
            append(',')
            appendFieldName("contentLength")
            if (response.contentLength == null) append("null") else append(response.contentLength)
            append(',')
            appendFieldName("bodyBase64")
            appendJsonString(encodeBase64(response.body))
            append('}')
        }
    }

    private fun validateRequest(request: PackJsHttpRequest) {
        val method = request.method.uppercase(Locale.ROOT)
        if (request.method != method || method.length > 16 || !METHOD.matches(method)) {
            fail("$.method", "method must be an uppercase HTTP token")
        }
        validateUrl(request.url, "$.url")
        validateHeaders(request.headers, "$.headers", request = true)
        val body = request.body
        if (body != null && body.size > MAX_REQUEST_BODY_BYTES) {
            fail("$.bodyBase64", "request body exceeds $MAX_REQUEST_BODY_BYTES bytes")
        }
        val maxResponseBytes = request.maxResponseBytes
        if (maxResponseBytes != null &&
            (maxResponseBytes <= 0 || maxResponseBytes > MAX_RESPONSE_BODY_BYTES)
        ) {
            fail("$.maxResponseBytes", "maxResponseBytes must be positive and within host limit")
        }
    }

    private fun validateResponse(response: PackJsHttpResponse) {
        if (response.statusCode !in 100..599) {
            fail("$.statusCode", "statusCode must be between 100 and 599")
        }
        validateHeaders(response.headers, "$.headers", request = false)
        validateUrl(response.finalUrl, "$.finalUrl")
        val contentLength = response.contentLength
        if (contentLength != null && contentLength < 0) {
            fail("$.contentLength", "contentLength must be non-negative or null")
        }
        if (response.body.size > MAX_RESPONSE_BODY_BYTES) {
            fail("$.bodyBase64", "response body exceeds $MAX_RESPONSE_BODY_BYTES bytes")
        }
    }

    private fun parseObject(json: String): JsonValue.Obj {
        if (json.length > MAX_HTTP_JSON_CHARS) {
            fail("$", "HTTP JSON exceeds $MAX_HTTP_JSON_CHARS characters")
        }
        val parsed = try {
            StrictJsonParser(maxStringChars = MAX_HTTP_JSON_CHARS).parse(json)
        } catch (e: JsonStructureException) {
            fail("$", e.message ?: "malformed HTTP JSON")
        }
        return parsed as? JsonValue.Obj ?: fail("$", "HTTP message must be a JSON object")
    }

    private fun checkAllowed(obj: JsonValue.Obj, allowed: Set<String>, path: String) {
        obj.entries.keys.forEach { key ->
            if (key !in allowed) fail("$path.$key", "unknown field '$key'")
        }
    }

    private fun readRequiredString(obj: JsonValue.Obj, key: String, path: String): String {
        val value = obj.entries[key] ?: fail("$path.$key", "missing required field")
        return (value as? JsonValue.Str)?.value ?: fail("$path.$key", "must be a string")
    }

    private fun readOptionalBoolean(
        obj: JsonValue.Obj,
        key: String,
        path: String,
        default: Boolean,
    ): Boolean {
        val value = obj.entries[key] ?: return default
        if (value is JsonValue.Null) return default
        return (value as? JsonValue.Bool)?.value ?: fail("$path.$key", "must be a boolean")
    }

    private fun readOptionalPositiveLong(
        obj: JsonValue.Obj,
        key: String,
        path: String,
    ): Long? {
        val value = obj.entries[key] ?: return null
        if (value is JsonValue.Null) return null
        val number = (value as? JsonValue.Num)?.raw ?: fail("$path.$key", "must be an integer")
        if (!INTEGER.matches(number)) fail("$path.$key", "must be an integer")
        val parsed = number.toLongOrNull() ?: fail("$path.$key", "integer is out of range")
        if (parsed <= 0L) fail("$path.$key", "must be positive")
        return parsed
    }

    private fun readOptionalNonNegativeLong(
        obj: JsonValue.Obj,
        key: String,
        path: String,
    ): Long? {
        val value = obj.entries[key] ?: return null
        if (value is JsonValue.Null) return null
        val number = (value as? JsonValue.Num)?.raw ?: fail("$path.$key", "must be an integer")
        if (!INTEGER.matches(number)) fail("$path.$key", "must be an integer")
        val parsed = number.toLongOrNull() ?: fail("$path.$key", "integer is out of range")
        if (parsed < 0L) fail("$path.$key", "must be non-negative")
        return parsed
    }

    private fun readRequiredInt(obj: JsonValue.Obj, key: String, path: String): Int {
        val value = obj.entries[key] ?: fail("$path.$key", "missing required field")
        val number = (value as? JsonValue.Num)?.raw ?: fail("$path.$key", "must be an integer")
        if (!INTEGER.matches(number)) fail("$path.$key", "must be an integer")
        return number.toIntOrNull() ?: fail("$path.$key", "integer is out of range")
    }

    private fun readBody(value: JsonValue?, path: String, maxBytes: Long): ByteArray? {
        if (value == null || value is JsonValue.Null) return null
        val text = (value as? JsonValue.Str)?.value ?: fail(path, "must be a Base64 string")
        val bytes = decodeBase64(text, path)
        if (bytes.size.toLong() > maxBytes) fail(path, "decoded body exceeds $maxBytes bytes")
        return bytes
    }

    private fun readHeaders(
        value: JsonValue?,
        path: String,
        request: Boolean,
    ): Map<String, List<String>> {
        if (value == null || value is JsonValue.Null) return emptyMap()
        val obj = value as? JsonValue.Obj ?: fail(path, "headers must be an object")
        if (obj.entries.size > MAX_HEADER_COUNT) fail(path, "too many headers")
        val result = LinkedHashMap<String, List<String>>()
        obj.entries.forEach { (name, headerValue) ->
            validateHeaderName(name, path)
            if (request && name.lowercase(Locale.ROOT) in FORBIDDEN_REQUEST_HEADERS) {
                fail("$path.$name", "request header '$name' is controlled by the host")
            }
            val values = when (headerValue) {
                is JsonValue.Str -> {
                    validateHeaderValue(headerValue.value, "$path.$name")
                    listOf(headerValue.value)
                }

                is JsonValue.Arr -> headerValue.items.mapIndexed { index, item ->
                    val text = (item as? JsonValue.Str)?.value
                        ?: fail("$path.$name[$index]", "header value must be a string")
                    validateHeaderValue(text, "$path.$name[$index]")
                    text
                }

                else -> fail("$path.$name", "header value must be a string or string array")
            }
            result[name] = values
        }
        return result
    }

    private fun validateHeaders(
        headers: Map<String, List<String>>,
        path: String,
        request: Boolean,
    ) {
        if (headers.size > MAX_HEADER_COUNT) fail(path, "too many headers")
        headers.forEach { (name, values) ->
            validateHeaderName(name, path)
            if (request && name.lowercase(Locale.ROOT) in FORBIDDEN_REQUEST_HEADERS) {
                fail("$path.$name", "request header '$name' is controlled by the host")
            }
            values.forEachIndexed { index, value ->
                validateHeaderValue(value, "$path.$name[$index]")
            }
        }
    }

    private fun validateHeaderName(name: String, path: String) {
        if (name.length > MAX_HEADER_NAME_CHARS || !HEADER_NAME.matches(name)) {
            fail("$path.$name", "invalid header name")
        }
    }

    private fun validateHeaderValue(value: String, path: String) {
        if (value.length > MAX_HEADER_VALUE_CHARS ||
            value.contains('\r') ||
            value.contains('\n') ||
            value.contains('\u0000')
        ) {
            fail(path, "invalid header value")
        }
    }

    private fun validateUrl(url: String, path: String) {
        if (url.length > MAX_URL_CHARS) fail(path, "URL is too long")
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            fail(path, "invalid URL")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") fail(path, "URL scheme must be http or https")
        if (uri.host.isNullOrBlank()) fail(path, "URL must contain a host")
        if (uri.userInfo != null) fail(path, "URL userinfo is not allowed")
        if (uri.fragment != null) fail(path, "URL fragment is not allowed")
        val rawPath = uri.rawPath
        if (rawPath != null && rawPath.any { it == '\r' || it == '\n' || it == '\u0000' }) {
            fail(path, "invalid URL")
        }
    }

    private fun StringBuilder.appendField(name: String, value: String) {
        appendFieldName(name)
        appendJsonString(value)
    }

    private fun StringBuilder.appendFieldName(name: String) {
        appendJsonString(name)
        append(':')
    }

    private fun StringBuilder.appendHeaders(headers: Map<String, List<String>>) {
        append('{')
        var first = true
        headers.forEach { (name, values) ->
            if (!first) append(',')
            first = false
            appendJsonString(name)
            append(":[")
            values.forEachIndexed { index, value ->
                if (index > 0) append(',')
                appendJsonString(value)
            }
            append(']')
        }
        append('}')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }

    private fun encodeBase64(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index + 2 < bytes.size) {
            val b0 = bytes[index].toInt() and 0xff
            val b1 = bytes[index + 1].toInt() and 0xff
            val b2 = bytes[index + 2].toInt() and 0xff
            out.append(BASE64_ALPHABET[b0 ushr 2])
            out.append(BASE64_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            out.append(BASE64_ALPHABET[((b1 and 0x0f) shl 2) or (b2 ushr 6)])
            out.append(BASE64_ALPHABET[b2 and 0x3f])
            index += 3
        }
        when (bytes.size - index) {
            1 -> {
                val b0 = bytes[index].toInt() and 0xff
                out.append(BASE64_ALPHABET[b0 ushr 2])
                out.append(BASE64_ALPHABET[(b0 and 0x03) shl 4])
                out.append("==")
            }

            2 -> {
                val b0 = bytes[index].toInt() and 0xff
                val b1 = bytes[index + 1].toInt() and 0xff
                out.append(BASE64_ALPHABET[b0 ushr 2])
                out.append(BASE64_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                out.append(BASE64_ALPHABET[(b1 and 0x0f) shl 2])
                out.append('=')
            }
        }
        return out.toString()
    }

    private fun decodeBase64(text: String, path: String): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        if (text.length % 4 != 0) fail(path, "Base64 length must be a multiple of four")
        val padding = when {
            text.endsWith("==") -> 2
            text.endsWith("=") -> 1
            else -> 0
        }
        val output = ByteArray(text.length / 4 * 3 - padding)
        var outputIndex = 0
        var group = 0
        while (group < text.length) {
            val last = group + 4 == text.length
            val c0 = base64Value(text[group], path)
            val c1 = base64Value(text[group + 1], path)
            val c2 = if (text[group + 2] == '=') -1 else base64Value(text[group + 2], path)
            val c3 = if (text[group + 3] == '=') -1 else base64Value(text[group + 3], path)
            if (c0 < 0 || c1 < 0 || (c2 < 0 && c3 >= 0)) {
                fail(path, "invalid Base64 padding")
            }
            if ((c2 < 0 || c3 < 0) && !last) fail(path, "invalid Base64 padding")
            if (c2 < 0 && (c1 and 0x0f) != 0) fail(path, "invalid Base64 trailing bits")
            if (c3 < 0 && c2 >= 0 && (c2 and 0x03) != 0) fail(path, "invalid Base64 trailing bits")
            output[outputIndex++] = ((c0 shl 2) or (c1 ushr 4)).toByte()
            if (c2 >= 0) {
                output[outputIndex++] = (((c1 and 0x0f) shl 4) or (c2 ushr 2)).toByte()
            }
            if (c3 >= 0) {
                output[outputIndex++] = (((c2 and 0x03) shl 6) or c3).toByte()
            }
            group += 4
        }
        return output
    }

    private fun base64Value(char: Char, path: String): Int {
        if (char == '=') return -1
        val index = BASE64_ALPHABET.indexOf(char)
        if (index < 0) fail(path, "invalid Base64 character")
        return index
    }

    private fun fail(path: String, message: String): Nothing =
        throw PackException(PackDiagnostic(PackErrorCode.INVALID_REQUEST, message, path))

    private val REQUEST_FIELDS = setOf(
        "method",
        "url",
        "headers",
        "bodyBase64",
        "followRedirects",
        "maxResponseBytes",
    )
    private val RESPONSE_FIELDS = setOf(
        "statusCode",
        "headers",
        "finalUrl",
        "contentLength",
        "bodyBase64",
    )
    private val INTEGER = Regex("-?(?:0|[1-9][0-9]*)")
}