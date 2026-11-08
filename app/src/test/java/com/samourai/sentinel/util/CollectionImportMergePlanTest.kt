package com.samourai.sentinel.util

import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge decision, without the repository. The import path applies
 * this plan; CollectionImportMergeTest covers that application.
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
    fun partialOverlapAppendsOnlyMissingKeysAndNewCollections() {
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
    fun overlapTieKeepsTheEarlierCollection() {
        val existing = listOf(
            collection("id-first", "My Wallet", key("k1", "Savings"), key("k2", "Checking")),
            collection("id-second", "My Wallet", key("k2", "Other"), key("k3", "Third")),
        )
        val plan = planCollectionImport(
            existing,
            listOf(collection("b", "My Wallet", key("k2", "renamed"), key("k4", "New"))),
        )
        assertEquals(listOf("id-first"), plan.keysForExisting.map { it.collectionId })
        assertEquals(listOf("k4"), plan.keysForExisting.single().keys.map { it.pubKey })
        assertTrue(plan.collectionsToAdd.isEmpty())
    }

    @Test
    fun overlappingPairInOneBackupBecomesOneCollection() {
        val plan = planCollectionImport(
            emptyList(),
            listOf(
                collection("b1", "My Wallet", key("k1", "Savings"), key("k2", "Checking")),
                collection("b2", "My Wallet", key("k2", "renamed"), key("k3", "New")),
            )
        )
        assertTrue(plan.keysForExisting.isEmpty())
        assertEquals(1, plan.collectionsToAdd.size)
        assertEquals(
            listOf("k1", "k2", "k3"),
            plan.collectionsToAdd.single().pubs.map { it.pubKey }
        )
        assertEquals(
            listOf("Savings", "Checking", "New"),
            plan.collectionsToAdd.single().pubs.map { it.label }
        )
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
