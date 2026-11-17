package com.samourai.sentinel.tor

import com.samourai.sentinel.util.LogRedaction

/**
 * #584 review: masking for Tor's own NOTICE and WARN lines before they
 * reach logcat in debug builds. Debug APKs are public CI artifacts, and
 * Tor's SafeLogging does not scrub everything: the "Problem bootstrapping"
 * warning names the guard relay by fingerprint and IP:port, and notices
 * show the app's data paths and the SOCKS port.
 *
 * Only the identifying values are replaced. The rest of each line, such
 * as "Bootstrapped 70%" or "Expiring wedged directory conn", is kept so a
 * stall can still be read from a capture. The app-wide rules in
 * [LogRedaction] (onion hosts, xpubs, txids, addresses) run afterwards,
 * so this does not depend on which Timber trees are planted.
 *
 * Each line is cut to [MAX_LINE] characters first, and the IPv6 patterns
 * are linear (no nested repetition), so a very long line cannot make the
 * masking slow or overflow the stack (#588).
 *
 * Only SentinelTorManager's BuildConfig.DEBUG block calls this.
 */
internal object TorLogMasking {

    /** Longest line kept; Tor's real lines are well under this (#588). */
    const val MAX_LINE = 2_000

    /** Appended when a line is cut at [MAX_LINE]. */
    const val TRUNCATED = " [truncated]"

    /**
     * Absolute filesystem paths (/data/..., /storage/..., any /a/b). The
     * slash must not follow a word character, so "N/A" and "1/2" stay.
     * A trailing full stop is left outside the match.
     */
    private val PATH = Regex("""(?<![\w./])/[\w.\-+@~%/]*[\w\-+@~%/]""")

    /**
     * Relay identity fingerprints: 40 hex characters, with or without a
     * leading $, upper or lower case, with an optional ~nickname or
     * =nickname. Exactly 40: a 64-character txid is not touched here.
     */
    private val FINGERPRINT = Regex("""(?<![0-9A-Za-z$])\$?[0-9A-Fa-f]{40}(?:[~=][A-Za-z0-9]{1,19})?(?![0-9A-Za-z])""")

    /** Base64 ed25519 relay identities (43 characters, unpadded). */
    private val ED25519_ID = Regex("""(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{43}(?![A-Za-z0-9+/=])""")

    /**
     * Bracketed IPv6, optionally with a port: [2001:db8::1]:443. The
     * part before the first colon cannot contain a colon and both runs
     * are possessive, so a failed match never backtracks (#588).
     */
    private val IPV6_BRACKETED = Regex("""\[[0-9A-Fa-f.]*+:[0-9A-Fa-f:.]*+](?::\d{1,5}+)?""")

    /**
     * IPv4, optionally with a port. Not after "Tor " or "version ", so
     * the four-part Tor version number stays readable.
     */
    private val IPV4 = Regex("""(?<![\w.])(?<!Tor )(?<!version )(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?(?!\w)(?!\.\d)""")

    /**
     * Bare IPv6 candidates: one possessive run of hex digits and colons,
     * with at least one colon. [isIpv6] then keeps the full eight groups
     * or a compressed form with "::" and at least one group, so clock
     * times (12:34:56) stay. No nested repetition, so the cost is linear
     * and the stack depth is constant (#588).
     */
    private val IPV6_BARE = Regex("""(?<![\w:])[0-9A-Fa-f]*+:[0-9A-Fa-f:]*+(?!\w)""")

    private fun isIpv6(candidate: String): Boolean {
        val groups = candidate.split(':')
        if (groups.size > 9 || groups.any { it.length > 4 }) return false
        val compressed = candidate.indexOf("::")
        return if (compressed >= 0) {
            candidate.indexOf("::", compressed + 1) < 0 && groups.count { it.isNotEmpty() } in 1..7
        } else {
            groups.size == 8 && groups.all { it.isNotEmpty() }
        }
    }

    /**
     * The guard nickname Tor prints just before the fingerprint, as in the
     * path-bias warning "Your Guard <nick> ($FP)" (#588). Runs after
     * [FINGERPRINT], so it looks for the word before the marker, with or
     * without the parenthesis. Tor's own words in that spot ("host",
     * "Guard" and so on) stay.
     */
    private val NICKNAME_BEFORE_FINGERPRINT =
        Regex("""(?<![\w$~=.\-/])([A-Za-z0-9]{1,19})(\s++\(?)(?=FINGERPRINT_REDACTED)""")

    private val NOT_A_NICKNAME = setOf(
        "a", "and", "at", "bridge", "by", "digest", "exit", "fingerprint", "for", "from", "guard",
        "host", "id", "identity", "is", "key", "node", "of", "on", "or", "relay", "router", "server",
        "the", "to", "via", "with",
    )

    /** "Socks listener listening on port 9050", "Control listener ... port 9051". */
    private val LISTENER_PORT = Regex(
        """(?i)(\b(?:socks|control|dns|trans|natd|http tunnel|metrics|extor)\w*\s+listener\b[^\n]*?\bport\s+)\d{1,5}\b"""
    )

    /** Port options as Tor echoes them: SocksPort 9050, ControlPort=9051. */
    private val PORT_OPTION = Regex(
        """(?i)(\b(?:Socks|Control|DNS|Trans|NATD|HTTPTunnel|Metrics)Port\s*[= ]\s*)\d{1,5}\b"""
    )

    fun mask(line: String): String = LogRedaction.redact(maskRelayDetails(cap(line)))

    /**
     * Cuts a line longer than [MAX_LINE] and appends [TRUNCATED]. The cut
     * goes back to the last whitespace, so a value that straddles the
     * limit is dropped whole rather than left as an unmaskable fragment.
     */
    internal fun cap(line: String): String {
        if (line.length <= MAX_LINE) return line
        val cut = line.substring(0, MAX_LINE)
        val space = cut.indexOfLast { it.isWhitespace() }
        return (if (space > 0) cut.substring(0, space) else cut) + TRUNCATED
    }

    /** The Tor-specific rules, without the cap or [LogRedaction]. */
    internal fun maskRelayDetails(line: String): String =
        line
            .replace(PATH, "PATH_REDACTED")
            .replace(FINGERPRINT, "FINGERPRINT_REDACTED")
            .replace(NICKNAME_BEFORE_FINGERPRINT) { m ->
                val word = m.groupValues[1]
                if (word.lowercase() in NOT_A_NICKNAME) m.value else "NICKNAME_REDACTED${m.groupValues[2]}"
            }
            .replace(ED25519_ID, "ED25519_REDACTED")
            .replace(IPV6_BRACKETED, "IP_REDACTED")
            .replace(IPV4, "IP_REDACTED")
            .replace(IPV6_BARE) { if (isIpv6(it.value)) "IP_REDACTED" else it.value }
            .replace(LISTENER_PORT) { "${it.groupValues[1]}PORT_REDACTED" }
            .replace(PORT_OPTION) { "${it.groupValues[1]}PORT_REDACTED" }
}
