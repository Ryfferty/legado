package io.legado.pack.primitives

import io.legado.pack.api.NetworkPolicy
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackHttpClient
import io.legado.pack.api.PackHttpRequest
import io.legado.pack.api.PackHttpResponse
import io.legado.pack.api.PackCallContext
import io.legado.pack.api.PackResourceLimits
import io.legado.pack.diagnostics.PackMetricsTracker
import io.legado.pack.diagnostics.Redaction
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One isolated OkHttp stack per pack revision.
 *
 * * The client has its own [ConnectionPool] and [PackCookieJar]; the host's
 *   global client/cookies are never touched.
 * * Redirects are followed manually, so every hop re-checks the domain policy,
 *   cancellation and deadline, and credentials/cookies are stripped when the
 *   host changes.
 * * Response bodies are bounded while streaming, not after reading everything.
 */
public class PackHttpClientImpl(
    private val policy: NetworkPolicy,
    private val openNetworkApproved: Boolean,
    private val cookieFile: java.io.File,
    private val limits: PackResourceLimits,
    private val guard: PackEpochGuard,
    private val tracker: PackMetricsTracker,
    onDiagnostic: (PackDiagnostic) -> Unit = {},
) : PackHttpClient, AutoCloseable {

    private val lock = Any()
    private var client: OkHttpClient? = null
    private val closed = AtomicBoolean(false)
    private val activeCalls: MutableSet<Call> =
        Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())
    private val cookieJar = PackCookieJar(cookieFile, onDiagnostic)

    override fun execute(request: PackHttpRequest, call: PackCallContext): PackHttpResponse {
        guard.ensureActive()
        if (closed.get()) throw cancelled("HTTP client is closed")
        call.checkActive()
        var url = parseUrl(request.url)
        var method = validateMethod(request.method)
        var body = request.body
        if (body != null && body.size.toLong() > io.legado.pack.api.PackJsHttpJson.MAX_REQUEST_BODY_BYTES) {
            throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                    message = "HTTP request body exceeds ${io.legado.pack.api.PackJsHttpJson.MAX_REQUEST_BODY_BYTES} bytes",
                    path = "http",
                ),
            )
        }
        if (body != null && method !in METHODS_WITH_BODY) {
            throw invalid("HTTP method $method must not carry a request body")
        }
        var headers = validateHeaders(request.headers)
        val maxResponseBytes = minOf(
            request.maxResponseBytes ?: limits.maxHttpResponseBytes,
            limits.maxHttpResponseBytes,
        )
        var redirects = 0
        while (true) {
            guard.ensureActive()
            call.checkActive()
            checkNetwork(url)
            val okRequest = buildRequest(url, method, headers, body)
            val okCall = client().newCall(okRequest)
            activeCalls += okCall
            val cancelHandle = call.cancellation.onCancel { okCall.cancel() }
            tracker.addNetworkOut(body?.size?.toLong() ?: 0L)
            val response = try {
                okCall.execute()
            } catch (e: IOException) {
                cancelHandle.close()
                activeCalls.remove(okCall)
                throw mapTransportFailure(e, call)
            } catch (e: Exception) {
                cancelHandle.close()
                activeCalls.remove(okCall)
                throw mapTransportFailure(e, call)
            }

            val location = response.header("Location")
            val shouldFollow = request.followRedirects &&
                response.code in 300..399 &&
                location != null
            if (shouldFollow) {
                val next = url.resolve(location)
                if (next == null ||
                    next.fragment != null ||
                    next.username.isNotEmpty() ||
                    next.password.isNotEmpty()
                ) {
                    response.close()
                    cancelHandle.close()
                    activeCalls.remove(okCall)
                    throw invalid("HTTP redirect location is not an acceptable http(s) URL")
                }
                redirects++
                if (redirects > MaxRedirects) {
                    response.close()
                    cancelHandle.close()
                    activeCalls.remove(okCall)
                    throw invalid("HTTP redirect limit ($MaxRedirects) exceeded")
                }
                if (!next.host.equals(url.host, ignoreCase = true) || (url.isHttps && !next.isHttps)) {
                    headers = stripSensitiveHeaders(headers)
                }
                val nextMethod = when {
                    response.code == 303 -> "GET"
                    (response.code == 301 || response.code == 302) && method == "POST" -> "GET"
                    else -> method
                }
                if (nextMethod == "GET" && method != "GET") {
                    body = null
                }
                response.close()
                cancelHandle.close()
                activeCalls.remove(okCall)
                url = next
                method = nextMethod
                continue
            }

            val responseBody = response.body
            val contentLength = responseBody?.contentLength()?.takeIf { it >= 0L }
            if (contentLength != null && contentLength > maxResponseBytes) {
                response.close()
                cancelHandle.close()
                activeCalls.remove(okCall)
                throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                        message = "HTTP response exceeds $maxResponseBytes bytes",
                        path = "http",
                        details = mapOf("host" to url.host, "contentLength" to contentLength.toString()),
                    ),
                )
            }
            val stream = responseBody?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
            return PackHttpResponseImpl(
                statusCode = response.code,
                headers = response.headers.toMultimap(),
                finalUrl = response.request.url.toString(),
                contentLength = contentLength,
                rawStream = stream,
                maxBytes = maxResponseBytes,
                call = call,
                tracker = tracker,
                onClose = {
                    cancelHandle.close()
                    activeCalls.remove(okCall)
                    response.close()
                },
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val calls = activeCalls.toList()
        calls.forEach { it.cancel() }
        activeCalls.clear()
        synchronized(lock) {
            client?.let { okHttp ->
                okHttp.connectionPool.evictAll()
                try {
                    okHttp.dispatcher.executorService.shutdown()
                } catch (_: Exception) {
                    // Nothing else to release; no background writer is owned.
                }
            }
            client = null
        }
        cookieJar.close()
    }

    private fun client(): OkHttpClient {
        synchronized(lock) {
            client?.let { return it }
            val created = OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
                .build()
            client = created
            return created
        }
    }

    private fun parseUrl(raw: String): HttpUrl {
        if (raw.length > MaxUrlChars) {
            throw invalid("HTTP URL is too long")
        }
        val url = raw.toHttpUrlOrNull()
            ?: throw invalid("HTTP URL must be absolute http(s)")
        if (url.scheme != "http" && url.scheme != "https") {
            throw invalid("HTTP URL scheme must be http or https")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw invalid("HTTP URL userinfo is not allowed")
        }
        if (url.fragment != null) {
            throw invalid("HTTP URL fragment is not allowed")
        }
        return url
    }

    private fun checkNetwork(url: HttpUrl) {
        when (val current = policy) {
            NetworkPolicy.None -> throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.PERMISSION_DENIED,
                    message = "pack did not declare network access",
                    path = "network",
                    details = mapOf("host" to url.host),
                ),
            )

            is NetworkPolicy.Allowlist -> {
                if (!current.permits(url.host)) {
                    throw PackException(
                        PackDiagnostic(
                            code = PackErrorCode.PERMISSION_DENIED,
                            message = "host '${url.host}' is not in the pack network allowlist",
                            path = "network",
                            details = mapOf("host" to url.host),
                        ),
                    )
                }
            }

            is NetworkPolicy.Open -> {
                if (!openNetworkApproved) {
                    throw PackException(
                        PackDiagnostic(
                            code = PackErrorCode.NETWORK_OPEN_REQUIRES_CONSENT,
                            message = "open network access was not approved for this pack",
                            path = "network",
                        ),
                    )
                }
            }
        }
    }

    private fun buildRequest(
        url: HttpUrl,
        method: String,
        headers: Map<String, List<String>>,
        body: ByteArray?,
    ): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, values) ->
            values.forEach { value -> builder.addHeader(name, value) }
        }
        val requestBody = when {
            body != null -> body.toRequestBody("application/octet-stream".toMediaType())
            method in METHODS_WITH_BODY -> ByteArray(0).toRequestBody("application/octet-stream".toMediaType())
            else -> null
        }
        return try {
            builder.method(method, requestBody).build()
        } catch (e: IllegalArgumentException) {
            throw invalid("invalid HTTP request: ${Redaction.redactText(e.message ?: "bad request")}")
        }
    }

    private fun validateMethod(raw: String): String {
        val method = raw.uppercase(Locale.ROOT)
        if (raw != method || method.length > 16 || !METHOD.matches(method)) {
            throw invalid("HTTP method must be an uppercase HTTP token")
        }
        if (method !in SUPPORTED_METHODS) {
            throw invalid("HTTP method $method is not supported")
        }
        return method
    }

    private fun validateHeaders(headers: Map<String, List<String>>): Map<String, List<String>> {
        if (headers.size > MaxHeaders) throw invalid("too many HTTP headers")
        val result = LinkedHashMap<String, List<String>>()
        headers.forEach { (name, values) ->
            if (name.length > MaxHeaderNameChars || !HEADER_NAME.matches(name)) {
                throw invalid("invalid HTTP header name")
            }
            val lower = name.lowercase(Locale.ROOT)
            if (lower in FORBIDDEN_REQUEST_HEADERS) {
                throw invalid("HTTP header '$name' is controlled by the host")
            }
            if (values.size > MaxHeaderValues) throw invalid("too many values for HTTP header '$name'")
            result[name] = values.map { value ->
                if (value.length > MaxHeaderValueChars ||
                    value.contains('\r') ||
                    value.contains('\n') ||
                    value.contains('\u0000')
                ) {
                    throw invalid("invalid value for HTTP header '$name'")
                }
                value
            }
        }
        return result
    }

    private fun stripSensitiveHeaders(headers: Map<String, List<String>>): Map<String, List<String>> =
        headers.filterKeys { it.lowercase(Locale.ROOT) !in CROSS_HOST_STRIPPED_HEADERS }

    private fun mapTransportFailure(error: Exception, call: PackCallContext): Exception {
        if (call.cancellation.isCancelled) {
            return cancelled(call.cancellation.reason ?: "cancelled")
        }
        if (call.isExpired()) {
            return io.legado.pack.api.PackTimeoutException(call.requestId)
        }
        val safe = Redaction.redactText(error.message ?: error.javaClass.simpleName).take(512)
        return PackException(
            PackDiagnostic(
                code = PackErrorCode.IO_FAILED,
                message = "HTTP request failed: $safe",
                path = "http",
            ),
            error,
        )
    }

    private fun cancelled(reason: String): PackException =
        PackException(
            PackDiagnostic(
                code = PackErrorCode.CANCELLED,
                message = "pack HTTP call cancelled: $reason",
                path = "http",
            ),
        )

    private fun invalid(message: String): PackException =
        PackException(PackDiagnostic(PackErrorCode.INVALID_REQUEST, message, path = "http"))

    private class PackHttpResponseImpl(
        override val statusCode: Int,
        override val headers: Map<String, List<String>>,
        override val finalUrl: String,
        override val contentLength: Long?,
        private val rawStream: InputStream,
        maxBytes: Long,
        private val call: PackCallContext,
        private val tracker: PackMetricsTracker,
        private val onClose: () -> Unit,
    ) : PackHttpResponse {

        private val closed = AtomicBoolean(false)
        private val body = BoundedBodyStream(rawStream, maxBytes, call, tracker, ::close)

        override fun bodyStream(): InputStream {
            if (closed.get()) throw PackException(
                PackDiagnostic(
                    code = PackErrorCode.IO_FAILED,
                    message = "HTTP response is already closed",
                    path = "http",
                ),
            )
            return body
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                rawStream.close()
            } catch (_: IOException) {
                // Closing is best effort; the call and connection are released below.
            }
            onClose()
        }
    }

    private class BoundedBodyStream(
        private val delegate: InputStream,
        private val maxBytes: Long,
        private val call: PackCallContext,
        private val tracker: PackMetricsTracker,
        private val closeResponse: () -> Unit,
    ) : InputStream() {

        private var total = 0L
        private var closed = false

        override fun read(): Int {
            val buffer = ByteArray(1)
            val read = read(buffer, 0, 1)
            return if (read < 0) -1 else buffer[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (closed) throw IOException("stream is closed")
            call.checkActive()
            val read = try {
                delegate.read(buffer, offset, length)
            } catch (e: IOException) {
                if (call.cancellation.isCancelled) {
                    throw io.legado.pack.api.PackCancelledException(
                        call.cancellation.reason ?: "cancelled",
                    )
                }
                if (call.isExpired()) {
                    throw io.legado.pack.api.PackTimeoutException(call.requestId)
                }
                throw e
            }
            if (read > 0) {
                total += read
                if (total > maxBytes) {
                    closeResponse()
                    throw PackException(
                        PackDiagnostic(
                            code = PackErrorCode.IMPORT_LIMIT_EXCEEDED,
                            message = "HTTP response exceeds $maxBytes bytes",
                            path = "http",
                        ),
                    )
                }
                tracker.addNetworkIn(read.toLong())
            }
            return read
        }

        override fun close() {
            if (closed) return
            closed = true
            closeResponse()
        }
    }

    companion object {
        private const val MaxRedirects = 10
        private const val MaxUrlChars = 8_192
        private const val MaxHeaders = 128
        private const val MaxHeaderValues = 32
        private const val MaxHeaderNameChars = 128
        private const val MaxHeaderValueChars = 8_192
        private val METHOD = Regex("[A-Z]+")
        private val SUPPORTED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        private val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH")
        private val FORBIDDEN_REQUEST_HEADERS = setOf("host", "content-length", "transfer-encoding")
        private val CROSS_HOST_STRIPPED_HEADERS = setOf(
            "authorization",
            "proxy-authorization",
            "cookie",
        )
        private val HEADER_NAME = Regex("[!#\$%&'*+.^_`|~0-9A-Za-z-]+")
    }
}