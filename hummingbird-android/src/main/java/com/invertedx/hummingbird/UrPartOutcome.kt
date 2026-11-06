package com.invertedx.hummingbird

/**
 * One decoded frame, as far as the UR decoder is concerned.
 *
 * [NOT_UR] is a plain QR (XPUB, Dojo pairing, a single label QR). It is
 * not a rejected UR part. [NEW], [REPEAT], and [REJECTED] are UR frames.
 */
internal enum class UrPartOutcome {
    NOT_UR,
    NEW,
    REPEAT,
    REJECTED
}

/**
 * Split one `URDecoder.receivePart` attempt into the QRPerf buckets.
 *
 * Hummingbird 1.7.4 (`URDecoder` / `FountainDecoder`):
 * - `receivePart` returns false immediately when a result is already set.
 * - It returns false for a bad scheme, a malformed part, a type mismatch,
 *   or a sequence header that disagrees with the fountain part.
 * - It returns true for a single-part UR. `processedPartsCount` stays 0
 *   on that path because the fountain decoder never sees the part.
 * - It returns true for a multi-part fountain part even when that part's
 *   content hash was already counted. `processedPartsCount` increments
 *   only when the fountain part's content hash is new, so a duplicate
 *   frame comes back as received=true with an unchanged count.
 *
 * A UR frame that arrives after the decoder already has a result is a
 * repeat. The animated code is still in frame, and `receivePart` refuses
 * it because the transmission is finished, not because the part is malformed.
 */
internal fun classifyUrPartOutcome(
    text: String,
    received: Boolean,
    processedBefore: Int,
    processedAfter: Int,
    hadResultBefore: Boolean,
    hasResultAfter: Boolean
): UrPartOutcome {
    if (!text.regionMatches(0, "ur:", 0, 3, ignoreCase = true)) {
        return UrPartOutcome.NOT_UR
    }
    if (processedAfter > processedBefore) {
        return UrPartOutcome.NEW
    }
    if (received && !hadResultBefore && hasResultAfter) {
        return UrPartOutcome.NEW
    }
    if (received || hadResultBefore) {
        return UrPartOutcome.REPEAT
    }
    return UrPartOutcome.REJECTED
}
