package com.samourai.sentinel.service

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * #465: process-local handoff for [ImportSegWitService].
 *
 * The XPUB and account type used to travel as Intent extras ("pubKey",
 * "segWit"), which leave the app sandbox through the system server and
 * can be read on a rooted or hooked device. Now the values stay in this
 * process: the Intent carries only an opaque, random, single-use token.
 */
object ImportSegWitRequests {

    data class Request(val pubKey: String, val segWit: String) {
        // Never print the XPUB through toString() (logs, crash reports).
        override fun toString(): String = "Request(pubKey=<redacted>, segWit=$segWit)"
    }

    private val pending = ConcurrentHashMap<String, Request>()

    /** Stores the request and returns the token to put on the Intent. */
    fun put(pubKey: String, segWit: String): String {
        val token = UUID.randomUUID().toString()
        pending[token] = Request(pubKey, segWit)
        return token
    }

    /** Returns and removes the request; null for an unknown or reused token. */
    fun take(token: String?): Request? = token?.let { pending.remove(it) }

    internal fun clear() = pending.clear()
}
