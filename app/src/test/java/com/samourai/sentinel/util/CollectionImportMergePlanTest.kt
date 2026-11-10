package com.samourai.sentinel.util

import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The merge decision, without the repository. An exact key set, or the
 * only subset/superset pair under that label, merges on its own. A
 * partial overlap stays unresolved until the caller keeps it separate
 * or names a collection. CollectionImportMergeTest covers the write.
 */
class CollectionImportMergePlanTest {

    @Test
    fun reimportOfTheSameBackupAddsNothing() {
        val existing = listOf(
            collection("id-my", "My Wallet", key("k1", "Savings"), key("k2", "Checking")),
            collection("id-cold", "Cold Storage", key("k3", "Vault")),
        )
        val backup = listOf(
            collection(
                "backup-my", "My Wallet",
                key("k2", "renamed"), key("k1", "renamed")
            ),
            collection("backup-cold", "Cold Storage", key("k3", "renamed")),
        )

        val once = planCollectionImport(existing, backup)
        val twice = planCollectionImport(existing, backup + backup)

        assertTrue(once.keysForExisting.isEmpty())
        assertTrue(once.collectionsToAdd.isEmpty())
        assertTrue(twice.keysForExisting.isEmpty())
        assertTrue(twice.collectionsToAdd.isEmpty())
    }

    @Test
    fun uniqueSupersetAppendsOnlyMissingKeysAndNewCollections() {
        val existing = listOf(
            collection("id-my", "My Wallet", key("k1", "Savings"), key("k2", "Checking")),
            collection("id-cold", "Cold Storage", key("k3", "Vault")),
            collection("id-watch", "Watch only", key("k6", "Donate")),
        )
        val hardware = key("k4", "Hardware account")
        val plan = planCollectionImport(
            existing,
            listOf(
                collection("b-cold", "Cold Storage", key("k3", "renamed")),
                collection(
                    "b-my", "My Wallet",
                    key("k2", "renamed"), key("k2", "again"), key("k1", "renamed"), hardware
                ),
                collection("b-paper", "Paper", key("k5", "Paper")),
            )
        )

        assertEquals(1, plan.keysForExisting.size)
        assertEquals("id-my", plan.keysForExisting[0].collectionId)
        assertEquals(listOf("k4"), plan.keysForExisting[0].keys.map { it.pubKey })
        assertEquals("Hardware account", plan.keysForExisting[0].keys.single().label)
        assertEquals(listOf("Paper"), plan.collectionsToAdd.map { it.collectionLabel })
        assertEquals(listOf("k5"), plan.collectionsToAdd.single().pubs.map { it.pubKey })
    }

    @Test
    fun subsetOfAnExistingCollectionAddsNothing() {
        val existing = listOf(
            collection("id-my", "My Wallet", key("k1", "Savings"), key("k2", "Checking")),
        )
        val plan = planCollectionImport(
            existing,
            listOf(collection("b", "My Wallet", key("k1", "renamed"))),
        )
        assertTrue(plan.keysForExisting.isEmpty())
        assertTrue(plan.collectionsToAdd.isEmpty())
    }

    @Test
    fun nameClashWithDifferentKeysAddsASeparateCollection() {
        val existing = listOf(
            collection("id-my", "My Wallet", key("k1", "Savings"), key("k2", "Checking")),
        )
        val plan = planCollectionImport(
            existing,
            listOf(collection("b", "My Wallet", key("k9", "Alpha"), key("k10", "Beta"))),
        )
        assertTrue(plan.keysForExisting.isEmpty())
        assertEquals(1, plan.collectionsToAdd.size)
        val added = plan.collectionsToAdd.single()
        assertEquals("My Wallet", added.collectionLabel)
        assertEquals(listOf("k9", "k10"), added.pubs.map { it.pubKey })
        assertEquals(listOf("Alpha", "Beta"), added.pubs.map { it.label })
    }

    @Test
    fun sameKeysUnderADifferentNameAreAdded() {
        val existing = listOf(
            collection("id-my", "My Wallet", key("k1", "Savings")),
        )
        val plan = planCollectionImport(
            existing,
            listOf(collection("b", "Renamed", key("k1", "From backup"))),
        )
        assertTrue(plan.keysForExisting.isEmpty())
        assertEquals("Renamed", plan.collectionsToAdd.single().collectionLabel)
        assertEquals("From backup", plan.collectionsToAdd.single().pubs.single().label)
    }

    @Test
    fun exactMatchWinsOverAnotherCollectionWithTheSameName() {
        val existing = listOf(
            collection("id-partial", "My Wallet", key("k1", "Savings")),
            collection("id-exact", "My Wallet", key("k1", "Savings"), key("k2", "Checking")),
        )
        val plan = planCollectionImport(
            existing,
            listOf(collection("b", "My Wallet", key("k2", "x"), key("k1", "y"))),
        )
        assertTrue(plan.keysForExisting.isEmpty())
        assertTrue(plan.collectionsToAdd.isEmpty())
    }

    @Test
    fun oneKeyOverlapBetweenDifferentSetsDoesNotAppendUnrelatedKeys() {
        val existing = listOf(
            collection("id-existing", "My Wallet", key("k-a", "Alpha"), key("k-b", "Beta")),
        )
        val incoming = listOf(
            collection("b", "My Wallet", key("k-b", "renamed"), key("k-c", "Gamma")),
        )

        val plan = planCollectionImport(existing, incoming)

        assertTrue(plan.keysForExisting.isEmpty())
        assertTrue(plan.collectionsToAdd.isEmpty())
        assertEquals(1, plan.unresolved.size)
        val ambiguity = plan.unresolved.single()
        assertEquals(0, ambiguity.incomingIndex)
        assertEquals("My Wallet", ambiguity.label)
        assertEquals(listOf("k-b", "k-c"), ambiguity.incomingKeys)
        assertEquals(listOf("id-existing"), ambiguity.candidateIds)
        assertEquals(listOf("k-a", "k-b"), existing.single().pubs.map { it.pubKey })
        assertEquals(listOf("Alpha", "Beta"), existing.single().pubs.map { it.label })
    }

    @Test
    fun oneKeyOverlapKeptSeparateDoesNotTouchTheExistingKeys() {
        val plan = planCollectionImport(
            listOf(collection("id-existing", "My Wallet", key("k-a", "Alpha"), key("k-b", "Beta"))),
            listOf(collection("b", "My Wallet", key("k-b", "From backup"), key("k-c", "Gamma"))),
            mapOf(0 to CollectionImportDecision.KeepSeparate),
        )
        assertTrue(plan.keysForExisting.isEmpty())
        assertTrue(plan.unresolved.isEmpty())
        val added = plan.collectionsToAdd.single()
        assertEquals("My Wallet", added.collectionLabel)
        assertEquals(listOf("k-b", "k-c"), added.pubs.map { it.pubKey })
        assertEquals(listOf("From backup", "Gamma"), added.pubs.map { it.label })
    }

    @Test
    fun multiplePartialOverlapCandidatesRequireAnExplicitDecision() {
        val existing = listOf(
            collection("id-ab", "Savings", key("k-a", "Alpha"), key("k-b", "Beta")),
            collection("id-cd", "Savings", key("k-c", "Cold"), key("k-d", "Desk")),
        )
        val plan = planCollectionImport(
            existing,
            listOf(
                collection(
                    "b", "Savings",
                    key("k-b", "renamed"), key("k-d", "renamed"), key("k-e", "Extra")
                )
            ),
        )
        assertTrue(plan.keysForExisting.isEmpty())
        assertTrue(plan.collectionsToAdd.isEmpty())
        val ambiguity = plan.unresolved.single()
        assertEquals(0, ambiguity.incomingIndex)
        assertEquals(listOf("k-b", "k-d", "k-e"), ambiguity.incomingKeys)
        assertEquals(listOf("id-ab", "id-cd"), ambiguity.candidateIds)
        assertEquals(listOf("k-a", "k-b"), existing[0].pubs.map { it.pubKey })
        assertEquals(listOf("k-c", "k-d"), existing[1].pubs.map { it.pubKey })
    }

    @Test
    fun multiplePartialOverlapsStaySeparateWhenThatIsTheDecision() {
        val plan = planCollectionImport(
            listOf(
                collection("id-ab", "Savings", key("k-a", "Alpha"), key("k-b", "Beta")),
                collection("id-cd", "Savings", key("k-c", "Cold"), key("k-d", "Desk")),
            ),
            listOf(
                collection(
                    "b", "Savings",
                    key("k-b", "From backup"), key("k-d", "From backup"), key("k-e", "Extra")
                )
            ),
            mapOf(0 to CollectionImportDecision.KeepSeparate),
        )
        assertTrue(plan.keysForExisting.isEmpty())
        assertTrue(plan.unresolved.isEmpty())
        assertEquals(
            listOf("k-b", "k-d", "k-e"),
            plan.collectionsToAdd.single().pubs.map { it.pubKey }
        )
    }

    @Test
    fun multiplePartialOverlapsMergeOnlyIntoTheChosenCollection() {
        val existing = listOf(
            collection("id-ab", "Savings", key("k-a", "Alpha"), key("k-b", "Beta")),
            collection("id-cd", "Savings", key("k-c", "Cold"), key("k-d", "Desk")),
        )
        val incoming = listOf(
            collection(
                "b", "Savings",
                key("k-b", "renamed"), key("k-d", "renamed"), key("k-e", "Extra")
            )
        )
        val intoFirst = planCollectionImport(
            existing,
            incoming,
            mapOf(0 to CollectionImportDecision.MergeInto("id-ab")),
        )
        assertTrue(intoFirst.collectionsToAdd.isEmpty())
        assertTrue(intoFirst.unresolved.isEmpty())
        assertEquals(listOf("id-ab"), intoFirst.keysForExisting.map { it.collectionId })
        assertEquals(listOf("k-d", "k-e"), intoFirst.keysForExisting.single().keys.map { it.pubKey })
        assertEquals(listOf("renamed", "Extra"), intoFirst.keysForExisting.single().keys.map { it.label })

        val intoSecond = planCollectionImport(
            existing,
            incoming,
            mapOf(0 to CollectionImportDecision.MergeInto("id-cd")),
        )
        assertTrue(intoSecond.collectionsToAdd.isEmpty())
        assertEquals(listOf("id-cd"), intoSecond.keysForExisting.map { it.collectionId })
        assertEquals(listOf("k-b", "k-e"), intoSecond.keysForExisting.single().keys.map { it.pubKey })
    }

    @Test
    fun aChoiceThatNamesNeitherOverlapStaysUnresolved() {
        val plan = planCollectionImport(
            listOf(
                collection("id-ab", "Savings", key("k-a", "Alpha"), key("k-b", "Beta")),
                collection("id-other", "Other", key("k-z", "Zed")),
            ),
            listOf(collection("b", "Savings", key("k-b", "Beta"), key("k-e", "Extra"))),
            mapOf(0 to CollectionImportDecision.MergeInto("id-other")),
        )
        assertTrue(plan.keysForExisting.isEmpty())
        assertTrue(plan.collectionsToAdd.isEmpty())
        assertEquals(listOf("id-ab"), plan.unresolved.single().candidateIds)
    }

    @Test
    fun multipleSupersetCandidatesRequireAnExplicitDecision() {
        val plan = planCollectionImport(
            listOf(
                collection("id-small", "My Wallet", key("k1", "Savings")),
                collection("id-large", "My Wallet", key("k1", "Savings"), key("k2", "Checking")),
            ),
            listOf(
                collection("b", "My Wallet", key("k1", "x"), key("k2", "y"), key("k3", "New"))
            ),
        )
        assertTrue(plan.keysForExisting.isEmpty())
        assertTrue(plan.collectionsToAdd.isEmpty())
        assertEquals(listOf("id-small", "id-large"), plan.unresolved.single().candidateIds)
    }

    @Test
    fun supersetIsNotAutomaticWhenAnotherCollectionOverlapsInPart() {
        val plan = planCollectionImport(
            listOf(
                collection("id-super", "My Wallet", key("k1", "Savings"), key("k2", "Checking")),
                collection("id-partial", "My Wallet", key("k2", "Other"), key("k9", "Ninth")),
            ),
            listOf(
                collection("b", "My Wallet", key("k1", "x"), key("k2", "y"), key("k3", "New"))
            ),
        )
        assertTrue(plan.keysForExisting.isEmpty())
        assertTrue(plan.collectionsToAdd.isEmpty())
        assertEquals(listOf("id-super", "id-partial"), plan.unresolved.single().candidateIds)
    }

    @Test
    fun uniqueSupersetStillMergesWhenAnotherSameNameSharesNoKeys() {
        val plan = planCollectionImport(
            listOf(
                collection("id-mine", "My Wallet", key("k1", "Savings"), key("k2", "Checking")),
                collection("id-other", "My Wallet", key("k9", "Other")),
            ),
            listOf(
                collection("b", "My Wallet", key("k2", "x"), key("k1", "y"), key("k3", "New"))
            ),
        )
        assertTrue(plan.unresolved.isEmpty())
        assertTrue(plan.collectionsToAdd.isEmpty())
        assertEquals(listOf("id-mine"), plan.keysForExisting.map { it.collectionId })
        assertEquals(listOf("k3"), plan.keysForExisting.single().keys.map { it.pubKey })
    }

    @Test
    fun partialOverlapInsideOneBackupStaysTwoCollections() {
        val incoming = listOf(
            collection("b1", "My Wallet", key("k1", "Savings"), key("k2", "Checking")),
            collection("b2", "My Wallet", key("k2", "renamed"), key("k3", "New")),
        )
        val unresolved = planCollectionImport(emptyList(), incoming)
        assertTrue(unresolved.keysForExisting.isEmpty())
        assertEquals(1, unresolved.collectionsToAdd.size)
        assertEquals(
            listOf("k1", "k2"),
            unresolved.collectionsToAdd.single().pubs.map { it.pubKey }
        )
        assertEquals(1, unresolved.unresolved.single().incomingIndex)
        assertEquals(
            unresolved.collectionsToAdd.single().id,
            unresolved.unresolved.single().candidateIds.single()
        )

        val separate = planCollectionImport(
            emptyList(),
            incoming,
            mapOf(1 to CollectionImportDecision.KeepSeparate),
        )
        assertTrue(separate.keysForExisting.isEmpty())
        assertTrue(separate.unresolved.isEmpty())
        assertEquals(
            listOf(listOf("k1", "k2"), listOf("k2", "k3")),
            separate.collectionsToAdd.map { collection -> collection.pubs.map { it.pubKey } }
        )
        assertEquals(
            listOf("Savings", "Checking"),
            separate.collectionsToAdd[0].pubs.map { it.label }
        )
        assertEquals(
            listOf("renamed", "New"),
            separate.collectionsToAdd[1].pubs.map { it.label }
        )
    }

    @Test
    fun unresolvedPlanIsNotAppliedAndDoesNotRewriteTheExistingList() {
        val before = listOf(
            collection("id-existing", "My Wallet", key("k-a", "Alpha"), key("k-b", "Beta")),
        )
        val plan = planCollectionImport(
            before,
            listOf(
                collection("other", "Paper", key("k-z", "Zed")),
                collection("b", "My Wallet", key("k-b", "renamed"), key("k-c", "Gamma")),
            ),
        )
        assertEquals(listOf("Paper"), plan.collectionsToAdd.map { it.collectionLabel })
        assertEquals(1, plan.unresolved.size)
        try {
            applyCollectionImportPlan(before, plan)
            fail("an unresolved overlap should not be applied")
        } catch (expected: CollectionImportNeedsDecision) {
            assertEquals(plan.unresolved, expected.unresolved)
        }
        assertEquals(listOf("k-a", "k-b"), before.single().pubs.map { it.pubKey })
        assertEquals("Alpha", before.single().pubs[0].label)
    }

    @Test
    fun missingMergeTargetIsNotApplied() {
        val before = listOf(
            collection("id-existing", "My Wallet", key("k-a", "Alpha")),
        )
        val plan = CollectionImportPlan(
            keysForExisting = listOf(
                ExistingKeyAddition("missing", listOf(key("k-c", "Gamma")))
            ),
            collectionsToAdd = emptyList(),
        )
        try {
            applyCollectionImportPlan(before, plan)
            fail("a missing collection should not be applied")
        } catch (expected: CollectionImportConflict) {
        }
        assertEquals(listOf("k-a"), before.single().pubs.map { it.pubKey })
        assertEquals(1, before.single().pubs.size)
    }

    private fun collection(
        id: String,
        label: String,
        vararg keys: PubKeyModel,
    ): PubKeyCollection = PubKeyCollection(
        id = id,
        collectionLabel = label,
        pubs = arrayListOf(*keys),
    )

    private fun key(pub: String, label: String): PubKeyModel = PubKeyModel(
        pubKey = pub,
        label = label,
        type = AddressTypes.BIP84,
    )
}
