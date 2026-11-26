package io.legado.pack.primitives

import io.legado.pack.api.DomainPattern
import io.legado.pack.api.NetworkPolicy
import io.legado.pack.api.PackCallContext
import io.legado.pack.api.PackCaller
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackHttpRequest
import io.legado.pack.api.PackResourceLimits
import io.legado.pack.TestPacks
import io.legado.pack.diagnostics.PackMetricsTracker
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class PackHttpClientTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @After
    fun tearDown() {
    }

    private fun client(
        policy: NetworkPolicy,
        approved: Boolean = true,
        limits: PackResourceLimits = PackResourceLimits(maxHttpResponseBytes = 1024),
        cookieFile: File = File(temp.newFolder(), "cookies.json"),
    ): PackHttpClientImpl = PackHttpClientImpl(
        policy = policy,
        openNetworkApproved = approved,
        cookieFile = cookieFile,
        limits = limits,
        guard = PackEpochGuard { true },
        tracker = PackMetricsTracker(),
    )

    private fun call(): PackCallContext = PackCallContext.after(
        requestId = "req-http",
        packRef = TestPacks.ref("http-pack", 'a'),
        caller = PackCaller.Host,
        timeoutMs = 5_000,
    )

    @Test
    fun getRequestUsesHeadersAndReturnsBody() {
        val server = TestHttpServer { request ->
            if (request.path == "/hello") {
                TestHttpServer.Response(
                    status = 200,
                    headers = mapOf("X-Reply" to "yes", "Content-Type" to "text/plain"),
                    body = "world",
                )
            } else {
                TestHttpServer.Response(404, emptyMap(), "")
            }
        }
        try {
            val response = client(allowLocalhost()).execute(
                PackHttpRequest(
                    method = "GET",
                    url = server.baseUrl + "/hello",
                    headers = mapOf("X-Input" to listOf("v")),
                ),
                call(),
            )
            try {
                assertEquals(200, response.statusCode)
                assertEquals("world", response.bodyStream().readBytes().toString(Charsets.UTF_8))
                assertEquals(
                    listOf("yes"),
                    response.headers.entries.firstOrNull { it.key.equals("X-Reply", ignoreCase = true) }?.value,
                )
            } finally {
                response.close()
            }
            assertEquals(1, server.requests.size)
            assertEquals("v", server.requests.single().headers["X-Input"])
        } finally {
            server.close()
        }
    }

    @Test
    fun redirectToHostOutsideAllowlistIsRejectedPerHop() {
        val server = TestHttpServer { request ->
            when (request.path) {
                "/start" -> TestHttpServer.Response(
                    302,
                    mapOf("Location" to "http://example.com/secret"),
                    "",
                )

                else -> TestHttpServer.Response(200, emptyMap(), "secret")
            }
        }
        try {
            try {
                client(allowLocalhost()).execute(
                    PackHttpRequest("GET", server.baseUrl + "/start", followRedirects = true),
                    call(),
                )
                fail("expected redirect policy failure")
            } catch (e: PackException) {
                assertEquals(PackErrorCode.PERMISSION_DENIED, e.diagnostic.code)
            }
            assertEquals(1, server.requests.size)
        } finally {
            server.close()
        }
    }

    @Test
    fun sameHostRedirectIsFollowedAndFinalUrlIsReported() {
        val server = TestHttpServer { request ->
            when (request.path) {
                "/start" -> TestHttpServer.Response(302, mapOf("Location" to "/final"), "")
                "/final" -> TestHttpServer.Response(200, emptyMap(), "done")
                else -> TestHttpServer.Response(404, emptyMap(), "")
            }
        }
        try {
            val response = client(allowLocalhost()).execute(
                PackHttpRequest("GET", server.baseUrl + "/start", followRedirects = true),
                call(),
            )
            try {
                assertEquals(200, response.statusCode)
                assertEquals("done", response.bodyStream().readBytes().toString(Charsets.UTF_8))
                assertTrue(response.finalUrl.endsWith("/final"))
            } finally {
                response.close()
            }
            assertEquals(2, server.requests.size)
        } finally {
            server.close()
        }
    }

    @Test
    fun responseSizeLimitIsEnforced() {
        val server = TestHttpServer {
            TestHttpServer.Response(200, emptyMap(), "x".repeat(4_096))
        }
        try {
            try {
                client(allowLocalhost()).execute(
                    PackHttpRequest(
                        method = "GET",
                        url = server.baseUrl + "/big",
                        maxResponseBytes = 512,
                    ),
                    call(),
                )
                fail("expected response limit failure")
            } catch (e: PackException) {
                assertEquals(PackErrorCode.IMPORT_LIMIT_EXCEEDED, e.diagnostic.code)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun networkNoneAndUnapprovedOpenAreDeniedBeforeConnecting() {
        val server = TestHttpServer {
            TestHttpServer.Response(200, emptyMap(), "unexpected")
        }
        try {
            try {
                client(NetworkPolicy.None).execute(
                    PackHttpRequest("GET", server.baseUrl + "/x"),
                    call(),
                )
                fail("expected network deny")
            } catch (e: PackException) {
                assertEquals(PackErrorCode.PERMISSION_DENIED, e.diagnostic.code)
            }
            try {
                client(NetworkPolicy.Open("dynamic"), approved = false).execute(
                    PackHttpRequest("GET", server.baseUrl + "/x"),
                    call(),
                )
                fail("expected open-network consent failure")
            } catch (e: PackException) {
                assertEquals(PackErrorCode.NETWORK_OPEN_REQUIRES_CONSENT, e.diagnostic.code)
            }
            assertEquals(0, server.requests.size)
        } finally {
            server.close()
        }
    }

    @Test
    fun hostControlledHeadersAreRejected() {
        try {
            client(allowLocalhost()).execute(
                PackHttpRequest(
                    method = "GET",
                    url = "http://127.0.0.1:1/x",
                    headers = mapOf("Host" to listOf("evil.example")),
                ),
                call(),
            )
            fail("expected invalid header failure")
        } catch (e: PackException) {
            assertEquals(PackErrorCode.INVALID_REQUEST, e.diagnostic.code)
        }
    }

    @Test
    fun cookiesAreIsolatedPerPackAndPersisted() {
        val directory = temp.newFolder("cookie-packs")
        val fileA = File(directory, "a.json")
        val fileB = File(directory, "b.json")
        val url = "http://localhost/app".toHttpUrl()
        val cookie = Cookie.parse(url, "sid=abc; Path=/")!!
        val jarA = PackCookieJar(fileA)
        val jarB = PackCookieJar(fileB)
        jarA.saveFromResponse(url, listOf(cookie))
        assertTrue(jarB.loadForRequest(url).isEmpty())
        assertEquals("abc", jarA.loadForRequest(url).single().value)
        assertTrue(fileA.isFile)

        val reloaded = PackCookieJar(fileA)
        assertEquals("abc", reloaded.loadForRequest(url).single().value)
    }

    private fun allowLocalhost(): NetworkPolicy = NetworkPolicy.Allowlist(
        listOf(DomainPattern.require("127.0.0.1")),
    )

    private class TestHttpServer(
        private val handler: (Request) -> Response,
    ) : AutoCloseable {

        data class Request(
            val method: String,
            val path: String,
            val headers: Map<String, String>,
        )

        data class Response(
            val status: Int,
            val headers: Map<String, String>,
            val body: String,
        )

        private val serverSocket = ServerSocket(
            0,
            50,
            InetAddress.getByName("127.0.0.1"),
        )
        val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"
        val requests: MutableList<Request> = CopyOnWriteArrayList()
        private val thread = Thread {
            while (!serverSocket.isClosed) {
                try {
                    serverSocket.accept().use { socket -> handle(socket) }
                } catch (_: Exception) {
                    if (serverSocket.isClosed) break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        private fun handle(socket: java.net.Socket) {
            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
            )
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
                }
            }
            val request = Request(
                method = parts[0],
                path = parts[1].substringBefore('?'),
                headers = headers,
            )
            requests += request
            val response = try {
                handler(request)
            } catch (_: Exception) {
                Response(500, emptyMap(), "")
            }
            val body = response.body.toByteArray(StandardCharsets.UTF_8)
            val output = socket.getOutputStream()
            val statusText = when (response.status) {
                200 -> "OK"
                302 -> "Found"
                404 -> "Not Found"
                else -> "Error"
            }
            val head = StringBuilder()
                .append("HTTP/1.1 ${response.status} $statusText\r\n")
                .append("Content-Length: ${body.size}\r\n")
                .append("Connection: close\r\n")
            response.headers.forEach { (name, value) ->
                if (!name.equals("Content-Length", ignoreCase = true) &&
                    !name.equals("Connection", ignoreCase = true)
                ) {
                    head.append(name).append(": ").append(value).append("\r\n")
                }
            }
            head.append("\r\n")
            output.write(head.toString().toByteArray(StandardCharsets.US_ASCII))
            output.write(body)
            output.flush()
        }

        override fun close() {
            serverSocket.close()
            thread.interrupt()
        }
    }
}