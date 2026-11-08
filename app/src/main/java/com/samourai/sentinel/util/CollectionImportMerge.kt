package com.samourai.sentinel.util

import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel

/**
 * How a non-clearing import decides that a backup collection is
 * already on the device.
 *
 * A collection is the same one when its label is exactly the same
 * and its set of public keys is exactly the same. Order, key labels,
 * balances, indexes, and fingerprints are not part of identity.
 * That collection is left untouched: it is not added again, and its
 * keys are not added again, so labels and settings on it stay.
 *
 * The same label with a partial overlap (some public key in common,
 * but not the same set) is still that collection. Keys already
 * present stay as they are. Keys the device does not have yet are
 * appended, in backup order, once each. When several collections
 * share the label, the one with the most keys in common is the one
 * that receives the new keys, and an exact set match wins over a
 * partial one. A tie keeps the earlier collection.
 *
 * The same label with no public key in common is a different
 * collection that happens to share a name. It is added beside the
 * existing one, and the keys are not mixed. A different label is
 * always added, even when the public keys match.
 *
 * "Clear existing" does not use this. That path still deletes the
 * wallet and adds the backup as a replacement.
 */
internal data class ExistingKeyAddition(
    val collectionId: String,
    val keys: List<PubKeyModel>,
)

internal data class CollectionImportPlan(
    val keysForExisting: List<ExistingKeyAddition>,
    val collectionsToAdd: List<PubKeyCollection>,
)

private class WorkingCollection(
    val id: String?,
    val label: String,
    val keys: MutableList<PubKeyModel>,
    val added: PubKeyCollection?,
    val appended: MutableList<PubKeyModel> = mutableListOf(),
)

private fun pubKeySet(keys: List<PubKeyModel>): HashSet<String> =
    keys.mapTo(HashSet(keys.size)) { it.pubKey }

internal fun planCollectionImport(
    existing: List<PubKeyCollection>,
    incoming: List<PubKeyCollection>,
): CollectionImportPlan {
    val working = existing.map { collection ->
        WorkingCollection(
            id = collection.id,
            label = collection.collectionLabel,
            keys = collection.pubs.mapTo(ArrayList(collection.pubs.size)) { it.copy() },
            added = null,
        )
    }.toMutableList()
    val toAdd = ArrayList<PubKeyCollection>()

    for (candidate in incoming) {
        val sameLabel = working.withIndex().filter { it.value.label == candidate.collectionLabel }
        val candidateSet = pubKeySet(candidate.pubs)
        if (sameLabel.any { pubKeySet(it.value.keys) == candidateSet }) {
            continue
        }

        var best: IndexedValue<WorkingCollection>? = null
        var bestShared = 0
        for (indexed in sameLabel) {
            val have = pubKeySet(indexed.value.keys)
            val shared = candidateSet.count { it in have }
            if (shared > bestShared) {
                bestShared = shared
                best = indexed
            }
        }

        if (best != null && bestShared > 0) {
            val target = best.value
            val present = pubKeySet(target.keys)
            for (key in candidate.pubs) {
                if (present.add(key.pubKey)) {
                    val copy = key.copy()
                    target.keys.add(copy)
                    target.appended.add(copy)
                }
            }
            continue
        }

        val freshKeys = candidate.pubs.mapTo(ArrayList(candidate.pubs.size)) { it.copy() }
        val fresh = candidate.copy(pubs = freshKeys)
        toAdd.add(fresh)
        working.add(
            WorkingCollection(
                id = null,
                label = fresh.collectionLabel,
                keys = freshKeys,
                added = fresh,
            )
        )
    }

    for (collection in working) {
        val added = collection.added ?: continue
        if (collection.appended.isNotEmpty()) {
            added.updateBalance()
        }
    }

    val additions = working.mapNotNull { collection ->
        val id = collection.id ?: return@mapNotNull null
        if (collection.appended.isEmpty()) {
            null
        } else {
            ExistingKeyAddition(id, collection.appended.toList())
        }
    }
    return CollectionImportPlan(additions, toAdd)
}
