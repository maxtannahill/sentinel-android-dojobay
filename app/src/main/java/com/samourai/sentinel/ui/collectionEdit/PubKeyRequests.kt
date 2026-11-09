package com.samourai.sentinel.ui.collectionEdit

import com.samourai.sentinel.data.PubKeyModel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * #484: process-local handoff for the add-key flow.
 *
 * The whole PubKeyModel - XPUB plus label, balance, account and change
 * indices, and fingerprint - used to ride the Intent to
 * CollectionEditActivity as a Parcelable extra. Intent extras transit the
 * system server and can be read on a rooted or hooked device.
 *
 * Same shape as #465's ImportSegWitRequests (#482): the Intent carries
 * only an opaque, random, single-use token; the model stays in-process.
 */
object PubKeyRequests {

    data class Request(val pubKeyModel: PubKeyModel) {
        // Never print the model through toString() (logs, crash reports).
        override fun toString(): String = "Request(pubKeyModel=<redacted>)"
    }

    private val pending = ConcurrentHashMap<String, Request>()

    /** Stores the model and returns the token to put on the Intent. */
    fun put(pubKeyModel: PubKeyModel): String {
        val token = UUID.randomUUID().toString()
        pending[token] = Request(pubKeyModel)
        return token
    }

    /** Returns and removes the request; null for an unknown or reused token. */
    fun take(token: String?): Request? = token?.let { pending.remove(it) }

    internal fun contains(token: String?): Boolean =
        token != null && pending.containsKey(token)

    /** Puts [pubKeyModel] back under a known token. Tests use this to see a second take. */
    internal fun putAt(token: String, pubKeyModel: PubKeyModel) {
        pending[token] = Request(pubKeyModel)
    }

    internal fun clear() = pending.clear()
}
