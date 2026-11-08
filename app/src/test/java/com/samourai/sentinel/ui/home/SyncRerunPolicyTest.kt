package com.samourai.sentinel.ui.home

import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #489: pins the trailing re-run decision extracted into
 * SyncRerunPolicy. A finished round may re-arm ONLY for a genuinely
 * changed list; the round's own per-pub writes (balance,
 * lastRefreshed) must never count, or every failed round loops
 * forever (idle device leg 2026-11-08: a full sync round every ~16s).
 */
class SyncRerunPolicyTest {

    private fun collection(label: String, vararg pubKeys: String): PubKeyCollection {
        val pubs = arrayListOf<PubKeyModel>()
        pubKeys.forEach { pub ->
            pubs.add(
                PubKeyModel(
                    pubKey = pub,
                    label = "$label key",
                    type = AddressTypes.BIP84
                )
            )
        }
        return PubKeyCollection(collectionLabel = label, pubs = pubs)
    }

    /**
     * The regression: a failed round over an unchanged list must not
     * re-arm. The round's own writes - the exact fields fetchFromServer
     * mutates per pub - are the storm, and none of them may look like
     * a list change.
     */
    @Test
    fun failedRoundOverUnchangedListDoesNotRearm() {
        val list = arrayListOf(
            collection("A", "xpub-A1", "xpub-A2"),
            collection("B", "xpub-B1")
        )
        val baseline = SyncRerunPolicy.signatureOf(list)

        // The round ran: same ids, same pubs, new per-pub state.
        list.forEach { it.lastRefreshed = 1L }
        list.forEach { c -> c.pubs.forEach { it.balance = 1_000L } }

        assertFalse(SyncRerunPolicy.shouldRerun(baseline, list))
    }

    /** #47 preserved: a pub added mid-round still re-arms. */
    @Test
    fun pubAddedMidRoundStillRearms() {
        val list = arrayListOf(collection("A", "xpub-A1"))
        val baseline = SyncRerunPolicy.signatureOf(list)

        list[0].pubs.add(
            PubKeyModel(pubKey = "xpub-A2", label = "A key 2", type = AddressTypes.BIP84)
        )

        assertTrue(SyncRerunPolicy.shouldRerun(baseline, list))
    }

    /** #47 preserved: a pub removed mid-round still re-arms. */
    @Test
    fun pubRemovedMidRoundStillRearms() {
        val list = arrayListOf(collection("A", "xpub-A1", "xpub-A2"))
        val baseline = SyncRerunPolicy.signatureOf(list)

        list[0].pubs.removeAt(1)

        assertTrue(SyncRerunPolicy.shouldRerun(baseline, list))
    }

    /** Import replaces ids wholesale; the re-run must still fire. */
    @Test
    fun replaceOnImportStillRearms() {
        val before = arrayListOf(collection("A", "xpub-A1"))
        val baseline = SyncRerunPolicy.signatureOf(before)

        val imported = arrayListOf(collection("A", "xpub-A1"))
        imported[0].id = "imported"

        assertTrue(SyncRerunPolicy.shouldRerun(baseline, imported))
    }

    /** Null baseline (fresh boot / deferred round) fails open. */
    @Test
    fun nullBaselineFailsOpen() {
        assertTrue(
            SyncRerunPolicy.shouldRerun(null, arrayListOf(collection("A", "xpub-A1")))
        )
    }
}
