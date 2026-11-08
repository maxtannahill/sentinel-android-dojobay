package com.samourai.sentinel.ui.home

import com.samourai.sentinel.data.PubKeyCollection

/**
 * #489: the trailing re-run decision for a finished sync round,
 * extracted from HomeViewModel so it is unit-testable without the
 * Koin graph.
 *
 * signatureOf is collection ids + pubkeys ONLY. The round's own
 * writes - per-pub balance, lastRefreshed, account index - never
 * change it, so the emission storm a round produces (~20
 * collectionsLiveData emissions per round, every one arming the
 * re-run) stops counting as change, while a genuine mid-round add,
 * remove or replace-on-import always does (#47).
 *
 * shouldRerun fails OPEN on a null baseline: fresh boot, or a round
 * that deferred before stamping (Tor climb, payload not yet loaded) -
 * in those states the trailing re-run is the only arm left holding a
 * pending list change, and dropping it would strand the change until
 * the next external arm.
 */
internal object SyncRerunPolicy {

    /** Content signature: collection ids + pubkeys, in list order. */
    fun signatureOf(list: List<PubKeyCollection>): String =
        list.joinToString("|") { c ->
            c.id + ":" + c.pubs.joinToString(",") { it.pubKey }
        }

    /**
     * True when the current list differs from the round's start
     * baseline - or when no baseline was stamped (fail open).
     */
    fun shouldRerun(baseline: String?, current: List<PubKeyCollection>): Boolean =
        baseline == null || signatureOf(current) != baseline
}
