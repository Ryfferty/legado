package io.legado.pack.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PackJsHttpProtocolTest {

    @Test
    fun `request round trip preserves headers body and options`() {
        val request = PackJsHttpRequest(
            method = "POST",
            url = "https://example.com/a?x=1",
            headers = mapOf("X-Test" to listOf("a", "b")),
            body = byteArrayOf(0, 1, 2, 255.toByte()),
            followRedirects = false,
            maxResponseBytes = 4096L,
        )

        val decoded = PackJsHttpJson.decodeRequest(PackJsHttpJson.encodeRequest(request))

        assertEquals("POST", decoded.method)
        assertEquals("https://example.com/a?x=1", decoded.url)
        assertEquals(listOf("a", "b"), decoded.headers["X-Test"])
        assertArrayEquals(byteArrayOf(0, 1, 2, 255.toByte()), decoded.body!!)
        assertFalse(decoded.followRedirects)
        assertEquals(4096L, decoded.maxResponseBytes)
    }

    @Test
    fun `request defaults and string header values are accepted`() {
        val decoded = PackJsHttpJson.decodeRequest(
            """{"method":"GET","url":"https://example.com","headers":{"X-Test":"one"}}""",
        )

        assertEquals("GET", decoded.method)
        assertEquals(listOf("one"), decoded.headers["X-Test"])
        assertNull(decoded.body)
        assertTrue(decoded.followRedirects)
        assertNull(decoded.maxResponseBytes)

        val nullHeaders = PackJsHttpJson.decodeRequest(
            """{"method":"GET","url":"https://example.com","headers":null}""",
        )
        assertTrue(nullHeaders.headers.isEmpty())
    }

    @Test
    fun `response round trip preserves non 2xx status and body`() {
        val response = PackJsHttpResponse(
            statusCode = 404,
            headers = mapOf("Content-Type" to listOf("text/plain")),
            finalUrl = "https://example.com/missing",
            contentLength = 5L,
            body = byteArrayOf(1, 2, 3, 4, 5),
        )

        val decoded = PackJsHttpJson.decodeResponse(PackJsHttpJson.encodeResponse(response))

        assertEquals(404, decoded.statusCode)
        assertEquals(listOf("text/plain"), decoded.headers["Content-Type"])
        assertEquals("https://example.com/missing", decoded.finalUrl)
        assertEquals(5L, decoded.contentLength)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), decoded.body)
    }

    @Test
    fun `invalid request body base64 and url are rejected`() {
        val base64 = assertThrows(PackException::class.java) {
            PackJsHttpJson.decodeRequest(
                """{"method":"POST","url":"https://example.com","bodyBase64":"%%%"}""",
            )
            Unit
        }
        assertEquals(PackErrorCode.INVALID_REQUEST, base64.diagnostic.code)

        val url = assertThrows(PackException::class.java) {
            PackJsHttpJson.decodeRequest("""{"method":"GET","url":"file:///etc/passwd"}""")
            Unit
        }
        assertEquals(PackErrorCode.INVALID_REQUEST, url.diagnostic.code)
    }

    @Test
    fun `host controlled headers and oversized limits are rejected`() {
        val host = assertThrows(PackException::class.java) {
            PackJsHttpJson.decodeRequest(
                """{"method":"GET","url":"https://example.com","headers":{"Host":"evil"}}""",
            )
            Unit
        }
        assertEquals(PackErrorCode.INVALID_REQUEST, host.diagnostic.code)

        val limit = assertThrows(PackException::class.java) {
            PackJsHttpJson.decodeRequest(
                """{"method":"GET","url":"https://example.com","maxResponseBytes":0}""",
            )
            Unit
        }
        assertEquals(PackErrorCode.INVALID_REQUEST, limit.diagnostic.code)
    }

    @Test
    fun `unknown fields bad status and negative length are rejected`() {
        val unknown = assertThrows(PackException::class.java) {
            PackJsHttpJson.decodeRequest(
                """{"method":"GET","url":"https://example.com","extra":1}""",
            )
            Unit
        }
        assertEquals(PackErrorCode.INVALID_REQUEST, unknown.diagnostic.code)

        val status = assertThrows(PackException::class.java) {
            PackJsHttpJson.decodeResponse(
                """{"statusCode":99,"headers":{},"finalUrl":"https://example.com","contentLength":null,"bodyBase64":""}""",
            )
            Unit
        }
        assertEquals(PackErrorCode.INVALID_REQUEST, status.diagnostic.code)

        val length = assertThrows(PackException::class.java) {
            PackJsHttpJson.decodeResponse(
                """{"statusCode":200,"headers":{},"finalUrl":"https://example.com","contentLength":-1,"bodyBase64":""}""",
            )
            Unit
        }
        assertEquals(PackErrorCode.INVALID_REQUEST, length.diagnostic.code)
    }
}