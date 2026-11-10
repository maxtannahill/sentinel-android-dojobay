package com.samourai.sentinel.util

import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import java.util.UUID

/**
 * How a non-clearing import decides whether a backup collection is
 * already on the device.
 *
 * Automatic merge is limited to two cases, and only under the same
 * label. Key order, key labels, balances, indexes, and fingerprints
 * are not part of the comparison.
 *
 * - The public keys are exactly the same set. That collection is left
 *   untouched: it is not added again, and its keys are not added again,
 *   so the labels and settings already on it stay.
 * - One collection's keys are a subset of the other's, and that pair is
 *   the only overlap under the label. A backup that only repeats keys
 *   already there adds nothing. A backup that carries extra keys appends
 *   those, in backup order, once each. Keys already present keep their
 *   labels and settings.
 *
 * Anything else that shares some keys is not identity. One shared key
 * between otherwise different sets is not enough, and neither is more
 * than one subset, superset, or partial candidate. The import leaves
 * that collection unresolved until the caller decides. Keeping it
 * separate adds the backup collection beside the ones already there.
 * Merging into a chosen collection appends only the keys that chosen
 * collection does not already have. A choice that does not name one of
 * the overlapping collections stays unresolved, and unrelated keys are
 * not appended anywhere.
 *
 * The same label with no key in common is a different collection and
 * is added. A different label is added even when the public keys match.
 *
 * "Clear existing" does not use this. That path still deletes the
 * wallet and adds the backup as a replacement.
 */
internal sealed class CollectionImportDecision {
    /** Add the backup collection beside the ones already on the device. */
    data object KeepSeparate : CollectionImportDecision()

    /**
     * Append the backup's missing keys to this collection. The id has
     * to be one of the overlapping collections named on the ambiguity.
     */
    data class MergeInto(val collectionId: String) : CollectionImportDecision()
}

internal data class ExistingKeyAddition(
    val collectionId: String,
    val keys: List<PubKeyModel>,
)

/**
 * A same-label backup collection that shares keys with one or more
 * collections on the device, without an exact match and without a
 * single subset/superset pair. Nothing is appended and nothing is
 * added until [CollectionImportDecision] says which.
 */
internal data class AmbiguousCollectionImport(
    val incomingIndex: Int,
    val label: String,
    val incomingKeys: List<String>,
    val candidateIds: List<String>,
)

internal data class CollectionImportPlan(
    val keysForExisting: List<ExistingKeyAddition>,
    val collectionsToAdd: List<PubKeyCollection>,
    val unresolved: List<AmbiguousCollectionImport> = emptyList(),
)

/**
 * The backup shares keys in a way that has no automatic result, and
 * the caller did not decide what to do with every such collection.
 * Nothing from that import should be written.
 */
internal class CollectionImportNeedsDecision(
    val unresolved: List<AmbiguousCollectionImport>,
) : IllegalStateException(
    "Import needs an explicit decision for ${unresolved.size} collection(s)"
)

/**
 * The planned import does not match the collections it would be
 * written onto. Nothing from that import should be written.
 */
internal class CollectionImportConflict(message: String) : IllegalStateException(message)

private class WorkingCollection(
    val id: String,
    val label: String,
    val keys: MutableList<PubKeyModel>,
    val added: PubKeyCollection?,
    val appended: MutableList<PubKeyModel> = mutableListOf(),
)

private enum class KeyOverlap {
    EXACT,
    SUBSET,
    SUPERSET,
    PARTIAL,
    DISJOINT,
}

private fun pubKeySet(keys: List<PubKeyModel>): HashSet<String> =
    keys.mapTo(HashSet(keys.size)) { it.pubKey }

/**
 * Empty against non-empty shares no key, so it is a different
 * collection. A vacuous subset is not a subset update.
 */
private fun keyOverlap(existing: Set<String>, incoming: Set<String>): KeyOverlap {
    if (existing == incoming) return KeyOverlap.EXACT
    if (existing.isEmpty() || incoming.isEmpty()) return KeyOverlap.DISJOINT
    if (existing.all { it in incoming }) return KeyOverlap.SUPERSET
    if (incoming.all { it in existing }) return KeyOverlap.SUBSET
    if (existing.any { it in incoming }) return KeyOverlap.PARTIAL
    return KeyOverlap.DISJOINT
}

private fun distinctPubsInOrder(keys: List<PubKeyModel>): List<String> {
    val seen = HashSet<String>(keys.size)
    val ordered = ArrayList<String>(keys.size)
    for (key in keys) {
        if (seen.add(key.pubKey)) ordered.add(key.pubKey)
    }
    return ordered
}

private fun appendMissing(target: WorkingCollection, keys: List<PubKeyModel>) {
    val present = pubKeySet(target.keys)
    for (key in keys) {
        if (present.add(key.pubKey)) {
            val copy = key.copy()
            target.keys.add(copy)
            target.appended.add(copy)
        }
    }
}

internal fun planCollectionImport(
    existing: List<PubKeyCollection>,
    incoming: List<PubKeyCollection>,
    decisions: Map<Int, CollectionImportDecision> = emptyMap(),
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
    val unresolved = ArrayList<AmbiguousCollectionImport>()

    fun addFresh(candidate: PubKeyCollection) {
        val freshKeys = candidate.pubs.mapTo(ArrayList(candidate.pubs.size)) { it.copy() }
        val fresh = candidate.copy(
            id = UUID.randomUUID().toString(),
            pubs = freshKeys,
        )
        toAdd.add(fresh)
        working.add(
            WorkingCollection(
                id = fresh.id,
                label = fresh.collectionLabel,
                keys = freshKeys,
                added = fresh,
            )
        )
    }

    for ((index, candidate) in incoming.withIndex()) {
        val candidateSet = pubKeySet(candidate.pubs)
        val overlaps = working.map { collection ->
            collection to keyOverlap(pubKeySet(collection.keys), candidateSet)
        }.filter { (collection, relation) ->
            collection.label == candidate.collectionLabel && relation != KeyOverlap.DISJOINT
        }

        if (overlaps.any { it.second == KeyOverlap.EXACT }) {
            continue
        }

        val contained = overlaps.filter {
            it.second == KeyOverlap.SUBSET || it.second == KeyOverlap.SUPERSET
        }
        val partial = overlaps.filter { it.second == KeyOverlap.PARTIAL }
        if (contained.size == 1 && partial.isEmpty()) {
            val (target, relation) = contained.single()
            if (relation == KeyOverlap.SUPERSET) {
                appendMissing(target, candidate.pubs)
            }
            continue
        }

        if (contained.isNotEmpty() || partial.isNotEmpty()) {
            val candidates = contained.map { it.first } + partial.map { it.first }
            when (val decision = decisions[index]) {
                CollectionImportDecision.KeepSeparate -> addFresh(candidate)
                is CollectionImportDecision.MergeInto -> {
                    val target = candidates.find { it.id.isNotEmpty() && it.id == decision.collectionId }
                    if (target == null) {
                        unresolved.add(ambiguity(index, candidate, candidates))
                    } else {
                        appendMissing(target, candidate.pubs)
                    }
                }
                null -> unresolved.add(ambiguity(index, candidate, candidates))
            }
            continue
        }

        addFresh(candidate)
    }

    for (collection in working) {
        val added = collection.added ?: continue
        if (collection.appended.isNotEmpty()) {
            added.updateBalance()
        }
    }

    val additions = working.mapNotNull { collection ->
        if (collection.added != null || collection.appended.isEmpty()) {
            null
        } else {
            ExistingKeyAddition(collection.id, collection.appended.toList())
        }
    }
    return CollectionImportPlan(additions, toAdd, unresolved)
}

private fun ambiguity(
    index: Int,
    candidate: PubKeyCollection,
    candidates: List<WorkingCollection>,
): AmbiguousCollectionImport = AmbiguousCollectionImport(
    incomingIndex = index,
    label = candidate.collectionLabel,
    incomingKeys = distinctPubsInOrder(candidate.pubs),
    candidateIds = candidates.map { it.id }.filter { it.isNotEmpty() }.distinct(),
)

/**
 * Builds the list an import would persist.
 *
 * Refuses when a collection is still unresolved, when a merge target
 * is not in [before], when an existing key would be rewritten or
 * removed, or when a new id is blank or already in use. [before] is
 * not modified.
 */
internal fun applyCollectionImportPlan(
    before: List<PubKeyCollection>,
    plan: CollectionImportPlan,
): ArrayList<PubKeyCollection> {
    if (plan.unresolved.isNotEmpty()) {
        throw CollectionImportNeedsDecision(plan.unresolved)
    }
    val beforeById = HashMap<String, PubKeyCollection>(before.size)
    for (collection in before) {
        if (collection.id.isEmpty() || beforeById.put(collection.id, collection) != null) {
            throw CollectionImportConflict("Collections on the device need distinct ids")
        }
    }
    if (plan.keysForExisting.map { it.collectionId }.distinct().size != plan.keysForExisting.size) {
        throw CollectionImportConflict("The same collection is updated more than once")
    }
    for (addition in plan.keysForExisting) {
        if (beforeById[addition.collectionId] == null) {
            throw CollectionImportConflict(
                "Collection ${addition.collectionId} is not on the device"
            )
        }
    }
    val newIds = plan.collectionsToAdd.map { it.id }
    if (newIds.any { it.isEmpty() } || newIds.distinct().size != newIds.size) {
        throw CollectionImportConflict("Imported collections need distinct ids")
    }
    if (newIds.any { it in beforeById }) {
        throw CollectionImportConflict("Imported collection id already exists")
    }

    val next = before.mapTo(ArrayList(before.size + plan.collectionsToAdd.size)) { collection ->
        collection.copy(pubs = ArrayList(collection.pubs.map { it.copy() }))
    }
    for (addition in plan.keysForExisting) {
        val target = next.single { it.id == addition.collectionId }
        val present = target.pubs.mapTo(HashSet()) { it.pubKey }
        var changed = false
        for (key in addition.keys) {
            if (present.add(key.pubKey)) {
                target.pubs.add(key.copy())
                changed = true
            }
        }
        if (changed) target.updateBalance()
    }
    for (added in plan.collectionsToAdd) {
        next.add(added.copy(pubs = ArrayList(added.pubs.map { it.copy() })))
    }

    if (next.size != before.size + plan.collectionsToAdd.size) {
        throw CollectionImportConflict("Import did not keep the collections already on the device")
    }
    for (index in before.indices) {
        val was = before[index]
        val now = next[index]
        if (now.id != was.id ||
            now.collectionLabel != was.collectionLabel ||
            now.lastRefreshed != was.lastRefreshed ||
            now.isImportFromWallet != was.isImportFromWallet
        ) {
            throw CollectionImportConflict("Import changed an existing collection")
        }
        if (now.pubs.size < was.pubs.size) {
            throw CollectionImportConflict("Import removed keys from an existing collection")
        }
        for (keyIndex in was.pubs.indices) {
            if (!sameStoredKey(was.pubs[keyIndex], now.pubs[keyIndex])) {
                throw CollectionImportConflict("Import rewrote a key that was already on the device")
            }
        }
    }
    return next
}

private fun sameStoredKey(before: PubKeyModel, after: PubKeyModel): Boolean =
    before.pubKey == after.pubKey &&
        before.label == after.label &&
        before.balance == after.balance &&
        before.change_index == after.change_index &&
        before.account_index == after.account_index &&
        before.fingerPrint == after.fingerPrint &&
        before.type == after.type
