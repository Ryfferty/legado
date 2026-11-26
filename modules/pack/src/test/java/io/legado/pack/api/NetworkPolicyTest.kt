package io.legado.pack.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {

    @Test
    fun `wildcard matches subdomains but not the bare domain`() {
        val pattern = DomainPattern.require("*.example.com")

        assertTrue(pattern.matches("api.example.com"))
        assertTrue(pattern.matches("a.b.example.com"))
        assertFalse(pattern.matches("example.com"))
        assertFalse(pattern.matches("notexample.com"))
        assertFalse(pattern.matches("example.com.evil"))
    }

    @Test
    fun `exact pattern matches only that host`() {
        val pattern = DomainPattern.require("api.example.com")

        assertTrue(pattern.matches("api.example.com"))
        assertTrue(pattern.matches("API.EXAMPLE.COM"))
        assertFalse(pattern.matches("cdn.example.com"))
    }

    @Test
    fun `invalid and over broad patterns are rejected`() {
        assertNull(DomainPattern.parse(""))
        assertNull(DomainPattern.parse("*.com"))
        assertNull(DomainPattern.parse("*example.com"))
        assertNull(DomainPattern.parse("http://example.com"))
        assertNull(DomainPattern.parse("example.com/path"))
        assertNull(DomainPattern.parse("exa_mple.com"))
        assertNull(DomainPattern.parse("example..com"))
        assertNull(DomainPattern.parse(" example.com"))
    }

    @Test
    fun `default policy denies every host`() {
        assertFalse(NetworkPolicy.None.permits("example.com"))
        assertFalse(NetworkPolicy.DEFAULT.permits("127.0.0.1"))
    }

    @Test
    fun `open policy permits arbitrary hosts`() {
        val policy = NetworkPolicy.Open("test")
        assertTrue(policy.permits("example.com"))
        assertTrue(policy.permits("127.0.0.1"))
    }

    @Test
    fun `allowlist uses pattern boundaries`() {
        val policy = NetworkPolicy.Allowlist(
            listOf(
                DomainPattern.require("*.example.com"),
                DomainPattern.require("api.test"),
            ),
        )

        assertTrue(policy.permits("a.example.com"))
        assertFalse(policy.permits("example.com"))
        assertTrue(policy.permits("api.test"))
        assertFalse(policy.permits("other.test"))
    }
}