package com.samourai.sentinel.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #584 review: Tor's NOTICE and WARN lines are masked before they reach
 * logcat in debug builds. The inputs are Tor 0.4.9 log lines in the shape
 * kmp-tor delivers them (message text, no timestamp or severity prefix).
 * Fingerprints and addresses are documentation values, not real relays.
 */
class TorLogMaskingTest {

    private val fp = "9695DFC35FFEB861329B9F1AB04C46397020CE31"
    private val appDir = "/data/user/0/com.samourai.sentinel.staging"

    private fun assertGone(out: String, vararg secrets: String) {
        for (s in secrets) assertFalse("'$s' must not survive in: $out", out.contains(s))
    }

    @Test
    fun `problem bootstrapping warning loses the guard fingerprint and address`() {
        val line = "Problem bootstrapping. Stuck at 14% (handshake): Handshaking with a relay. " +
            "(DONE; DONE; count 1; recommendation warn; host $fp at 192.0.2.34:9101)"
        val out = TorLogMasking.mask(line)
        assertGone(out, fp, "192.0.2.34", "9101")
        assertEquals(
            "Problem bootstrapping. Stuck at 14% (handshake): Handshaking with a relay. " +
                "(DONE; DONE; count 1; recommendation warn; host FINGERPRINT_REDACTED at IP_REDACTED)",
            out,
        )
    }

    @Test
    fun `problem bootstrapping warning with an IPv6 guard is masked`() {
        val line = "Problem bootstrapping. Stuck at 10% (conn_done): Connected to a relay. " +
            "(DONE; DONE; count 3; recommendation warn; host ${fp.lowercase()} at [2001:db8:85a3::8a2e:370:7334]:443)"
        val out = TorLogMasking.mask(line)
        assertGone(out, fp.lowercase(), "2001:db8", "7334", ":443")
        assertTrue(out.endsWith("host FINGERPRINT_REDACTED at IP_REDACTED)"))
        assertTrue(out.startsWith("Problem bootstrapping. Stuck at 10% (conn_done)"))
    }

    @Test
    fun `dollar fingerprints with nicknames and bare IPv6 are masked`() {
        val tilde = TorLogMasking.mask("Your Guard \$$fp~SomeGuardRelay (\$$fp) is failing a very large amount of circuits.")
        assertGone(tilde, fp, "SomeGuardRelay")
        assertEquals("Your Guard FINGERPRINT_REDACTED (FINGERPRINT_REDACTED) is failing a very large amount of circuits.", tilde)

        val equals = TorLogMasking.mask("Circuit build failed at \$$fp=relay2 on 2001:db8::1")
        assertEquals("Circuit build failed at FINGERPRINT_REDACTED on IP_REDACTED", equals)
    }

    @Test
    fun `ed25519 relay identities are masked`() {
        val ed = "3e2Z8pKMzJ0Vb7q0V1z0hXqoYvTQ0YbF2y8b9mH1aWk"
        assertEquals(43, ed.length)
        val out = TorLogMasking.mask("Received a bad CERTS cell from relay with ed25519 id $ed: Ed25519 identity mismatch")
        assertGone(out, ed)
        assertTrue(out.contains("ed25519 id ED25519_REDACTED: Ed25519 identity mismatch"))
    }

    @Test
    fun `socks and control listener addresses and ports are masked`() {
        assertEquals("Opening Socks listener on IP_REDACTED", TorLogMasking.mask("Opening Socks listener on 127.0.0.1:43217"))
        assertEquals(
            "Opened Socks listener connection (ready) on IP_REDACTED.",
            TorLogMasking.mask("Opened Socks listener connection (ready) on 127.0.0.1:43217."),
        )
        assertEquals("Socks listener listening on port PORT_REDACTED.", TorLogMasking.mask("Socks listener listening on port 43217."))
        assertEquals("Control listener listening on port PORT_REDACTED.", TorLogMasking.mask("Control listener listening on port 9051."))
        assertEquals(
            "Opening Control listener on PATH_REDACTED",
            TorLogMasking.mask("Opening Control listener on $appDir/app_tor/ctrl.sock"),
        )
        assertEquals("SocksPort PORT_REDACTED", TorLogMasking.mask("SocksPort 43217"))
    }

    @Test
    fun `data directory and cache paths are masked`() {
        val geoip = TorLogMasking.mask("Parsing GEOIP IPv4 file $appDir/app_tor/geoip.")
        assertEquals("Parsing GEOIP IPv4 file PATH_REDACTED.", geoip)

        val torrc = TorLogMasking.mask("Read configuration file \"$appDir/app_tor/torrc-defaults\".")
        assertEquals("Read configuration file \"PATH_REDACTED\".", torrc)

        val cache = TorLogMasking.mask(
            "Unable to open file \"$appDir/app_tor_cache/cached-microdesc-consensus.tmp\" in mode 'w': Permission denied"
        )
        assertGone(cache, appDir, "app_tor_cache", "com.samourai")
        assertTrue(cache.endsWith("in mode 'w': Permission denied"))

        val storage = TorLogMasking.mask("DataDirectory /storage/emulated/0/Android/data/com.samourai.sentinel/tor is not writable")
        assertEquals("DataDirectory PATH_REDACTED is not writable", storage)
    }

    @Test
    fun `stall diagnostics stay readable`() {
        val kept = listOf(
            "Bootstrapped 70% (loading_descriptors): Loading relay descriptors",
            "Bootstrapped 100% (done): Done",
            "Expiring wedged directory conn (fd 87, purpose 14)",
            "I learned some more directory information, but not enough to build a circuit: We need more microdescriptors: we have 0/7426, and can only build 0% of likely paths.",
            "Tor 0.4.9.16 running on Linux with Libevent 2.1.12-stable, OpenSSL 3.0.15, Zlib 1.3.1, Liblzma N/A, Libzstd N/A and Unknown N/A as libc.",
            "Tried for 120 seconds to get a connection to [scrubbed]:443. Giving up. (waiting for circuit)",
            "Our clock is 2 hours, 0 minutes behind the time published in the consensus at 12:34:56.",
        )
        for (line in kept) assertEquals(line, TorLogMasking.mask(line))
    }

    @Test
    fun `app-wide redaction still applies to Tor lines`() {
        val host = "abcdefghijklmnopqrstuvwxyz234567"
        val txid = "a".repeat(64)
        val out = TorLogMasking.mask("Closing stream to '$host.onion' for $txid: hidden service is unavailable")
        assertGone(out, "$host.onion", txid)
        assertTrue(out.contains("ONION_REDACTED"))
        assertTrue(out.contains("HEX_REDACTED"))
        assertTrue(out.endsWith("hidden service is unavailable"))
    }

    @Test
    fun `both Tor log observers forward the masked line`() {
        val path = "java/com/samourai/sentinel/tor/SentinelTorManager.kt"
        val src = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.isFile }.readText()
        assertTrue(src.contains("Timber.tag(TOR_LOG_TAG).i(TorLogMasking.mask(line))"))
        assertTrue(src.contains("Timber.tag(TOR_LOG_TAG).w(TorLogMasking.mask(line))"))
        assertFalse("no unmasked Tor line", Regex("""Timber\.tag\(TOR_LOG_TAG\)\.\w\(line\)""").containsMatchIn(src))
    }

    // #588 D1: the guard nickname in the path-bias warning.

    @Test
    fun `path-bias warning loses the guard nickname as well as the fingerprint`() {
        val tail = " is failing a very large amount of circuits. Most likely this means the Tor network is " +
            "overloaded, but it could also mean an attack against you or potentially the guard itself. " +
            "Success counts are 52/181. Use counts are 23/23. 167 circuits completed, 0 were unusable, " +
            "115 collapsed, and 15 timed out. For reference, your timeout cutoff is 60 seconds."
        val out = TorLogMasking.mask("Your Guard SomeGuardRelay (\$$fp)$tail")
        assertGone(out, fp, "SomeGuardRelay")
        assertEquals("Your Guard NICKNAME_REDACTED (FINGERPRINT_REDACTED)$tail", out)

        val usual = TorLogMasking.mask("Your Guard relay42 ($fp) is failing more circuits than usual. Success counts are 120/151.")
        assertEquals("Your Guard NICKNAME_REDACTED (FINGERPRINT_REDACTED) is failing more circuits than usual. Success counts are 120/151.", usual)

        val bare = TorLogMasking.mask("Guard MyGuard \$$fp is unusable")
        assertEquals("Guard NICKNAME_REDACTED FINGERPRINT_REDACTED is unusable", bare)
    }

    @Test
    fun `Tor's own words before a fingerprint stay`() {
        val out = TorLogMasking.mask("Problem bootstrapping. Stuck at 14% (handshake): Handshaking with a relay. " +
            "(DONE; DONE; count 1; recommendation warn; host $fp at 192.0.2.34:9101)")
        assertTrue(out.endsWith("host FINGERPRINT_REDACTED at IP_REDACTED)"))
    }

    // #588 D2: long lines are cut, and the patterns stay fast on a small stack.

    @Test
    fun `a line past the cap is cut with a marker and a secret after the cap is dropped`() {
        val head = "Bootstrapped 70% (loading_descriptors): Loading relay descriptors "
        val line = head + "word ".repeat(500) + "host $fp at 192.0.2.34:9101 /data/user/0/x/app_tor/state"
        assertTrue(line.length > TorLogMasking.MAX_LINE)
        val out = TorLogMasking.mask(line)
        assertGone(out, fp, "192.0.2.34", "9101", "/data/user")
        assertTrue(out.startsWith(head))
        assertTrue(out.endsWith(TorLogMasking.TRUNCATED))
        assertTrue(out.length <= TorLogMasking.MAX_LINE + TorLogMasking.TRUNCATED.length)

        // A fingerprint straddling the cap is dropped whole, not left as a fragment.
        val pad = "x ".repeat((TorLogMasking.MAX_LINE - 10) / 2)
        val straddle = TorLogMasking.mask(pad + "host $fp at 192.0.2.34:9101")
        assertGone(straddle, fp.take(8), "192.0.2.34")
        assertTrue(straddle.endsWith(TorLogMasking.TRUNCATED))

        val short = "Bootstrapped 100% (done): Done"
        assertEquals(short, TorLogMasking.mask(short))
    }

    @Test
    fun `100k-character lines finish fast without overflowing a 256 KiB stack`() {
        val inputs = listOf(
            "1:".repeat(50_000),
            "[" + "1:".repeat(50_000),
            "[1:".repeat(33_000),
            "::" + "ab:".repeat(33_000),
            "a".repeat(100_000),
            "host " + "0123456789abcdef:".repeat(6_000) + " at [" + ":".repeat(100_000),
        )
        var failure: Throwable? = null
        val started = System.nanoTime()
        val worker = Thread(null, {
            try {
                for (input in inputs) {
                    TorLogMasking.maskRelayDetails(input)
                    TorLogMasking.mask(input)
                }
            } catch (t: Throwable) {
                failure = t
            }
        }, "tor-mask-small-stack", 256L * 1024)
        worker.start()
        worker.join(30_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertFalse("still running after $elapsedMs ms", worker.isAlive)
        failure?.let { throw AssertionError("masking threw on a long line", it) }
        assertTrue("took $elapsedMs ms", elapsedMs < 5_000)
    }

    @Test
    fun `linear IPv6 patterns still mask real addresses and keep clock times`() {
        assertEquals("on IP_REDACTED", TorLogMasking.maskRelayDetails("on 2001:db8:85a3:0:0:8a2e:370:7334"))
        assertEquals("on IP_REDACTED.", TorLogMasking.maskRelayDetails("on fe80::1."))
        assertEquals("on IP_REDACTED", TorLogMasking.maskRelayDetails("on ::1"))
        assertEquals("at IP_REDACTED)", TorLogMasking.maskRelayDetails("at [2001:db8::1]:9001)"))
        assertEquals("at IP_REDACTED", TorLogMasking.maskRelayDetails("at [::1]"))
        for (kept in listOf("at 12:34:56.", "cafe: open", "Foo::bar", "a:b:c:d", "[scrubbed]:443")) {
            assertEquals(kept, TorLogMasking.maskRelayDetails(kept))
        }
    }
}
