package com.samourai.sentinel.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #468. TokenAuthenticator exists to re-authenticate one origin: the
 * configured Dojo endpoint. Everything else that can answer 401 - the
 * five exchange providers routed through ApiService.request() - must
 * fail the call instead of burning a Dojo login and retrying a
 * third-party URL with `at=<Dojo access token>`.
 *
 * The comparator is the credential gate, so it is tested on its own:
 * strict on scheme, host and effective port, and failing closed on
 * null, blank and unparseable configuration. Hosts are RFC 2606
 * example names - the comparator is origin-agnostic, no real endpoint
 * needed.
 */
class TokenAuthenticatorOriginGateTest {

    private fun target(url: String) = url.toHttpUrl()

    @Test
    fun `the configured endpoint itself is a Dojo target`() {
        assertTrue(
            TokenAuthenticator.isDojoTarget(
                "http://dojo.example:8080",
                target("http://dojo.example:8080/tx/abc?fees=1"),
            ),
        )
    }

    @Test
    fun `default ports resolve before comparing`() {
        assertTrue(
            TokenAuthenticator.isDojoTarget(
                "https://dojo.example",
                target("https://dojo.example:443/wallet"),
            ),
        )
    }

    @Test
    fun `host comparison ignores case`() {
        assertTrue(
            TokenAuthenticator.isDojoTarget(
                "http://Dojo.Example:8080",
                target("http://dojo.example:8080/"),
            ),
        )
    }

    @Test
    fun `a different host is not a Dojo target`() {
        assertFalse(
            TokenAuthenticator.isDojoTarget(
                "http://dojo.example:8080",
                target("http://exchange.example:8080/simple/price"),
            ),
        )
    }

    @Test
    fun `a port mismatch is not a Dojo target`() {
        assertFalse(
            TokenAuthenticator.isDojoTarget(
                "http://dojo.example:8080",
                target("http://dojo.example:9090/"),
            ),
        )
    }

    @Test
    fun `a scheme change is not a Dojo target`() {
        assertFalse(
            TokenAuthenticator.isDojoTarget(
                "https://dojo.example",
                target("http://dojo.example/"),
            ),
        )
    }

    @Test
    fun `null blank and garbage configuration fail closed`() {
        assertFalse(TokenAuthenticator.isDojoTarget(null, target("http://dojo.example/")))
        assertFalse(TokenAuthenticator.isDojoTarget("", target("http://dojo.example/")))
        assertFalse(TokenAuthenticator.isDojoTarget("dojo.example", target("http://dojo.example/")))
        assertFalse(
            TokenAuthenticator.isDojoTarget(
                "http://dojo.example:not-a-port",
                target("http://dojo.example/"),
            ),
        )
    }
}
