package com.samourai.sentinel.ui.fragments

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

internal const val BBQR_HEADER_LENGTH = 8

/**
 * Decoded label text, and the joined bytes fed to inflate, stay under
 * this cap. A hostile stream cannot expand past it, and parts are not
 * stored once the running total would.
 */
internal const val BBQR_MAX_DECODED_BYTES = 4 * 1024 * 1024

/**
 * Encoded part bodies retained next to the decoded bytes. Hex is the
 * widest of the admitted encodings (two characters per byte).
 */
internal const val BBQR_MAX_ENCODED_CHARS = BBQR_MAX_DECODED_BYTES * 2

private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

/**
 * Header of one Coinkite BBQr frame. [signature] is the first six
 * characters (`B$`, encoding, file type, part count): every part of a
 * series carries the same six, and a different six is a different series.
 */
data class BbqrHeader(
    val encoding: Char,
    val fileType: Char,
    val partCount: Int,
    val partIndex: Int,
    val body: String,
    val signature: String,
)

/**
 * Sparrow's BIP-329 export is Unicode (`U`). JSON (`J`) is the spec's
 * JSON file type. Both are text; the labels door-check still has to
 * accept the decoded UTF-8. Every other file type is not a label export.
 */
internal fun isBbqrLabelText(fileType: Char): Boolean =
    fileType == 'U' || fileType == 'J'

/**
 * Eight-character BBQr header, or null when [frame] is not a frame or
 * the base-36 count/index cannot address a part (count 0, index past
 * the end). Encoding is only `H`, `2`, or `Z`; [isBbqrFrame] already
 * rejected anything else.
 */
fun parseBbqrHeader(frame: String): BbqrHeader? {
    if (!isBbqrFrame(frame)) return null
    val partCount = frame.substring(4, 6).toInt(36)
    val partIndex = frame.substring(6, 8).toInt(36)
    if (partCount <= 0 || partIndex >= partCount) return null
    return BbqrHeader(
        encoding = frame[2],
        fileType = frame[3],
        partCount = partCount,
        partIndex = partIndex,
        body = frame.substring(BBQR_HEADER_LENGTH),
        signature = frame.substring(0, 6),
    )
}

sealed class BbqrFailure {
    object Decode : BbqrFailure()
    data class FileType(val fileType: Char) : BbqrFailure()
}

/** Unique parts stored against the header's part count. Not a fountain estimate. */
data class BbqrProgress(val collected: Int, val total: Int)

/**
 * Why a BBQr frame was not stored.
 *
 * [Held] is an index already stored with the same body. [Conflict] is a
 * later frame for an index already stored whose body disagrees; the
 * parts already collected are dropped and the scan has to start over.
 * [Decode] is a body that does not decode, including one that does not
 * fit the byte budget when another series is still open. [Index] is an
 * index past the header count, or a count of zero. [Closed] is a frame
 * for a series that already finished or was rejected. [Other] is not a
 * BBQr header.
 *
 * [Held], [Decode], [Index], and [Other] leave an incomplete series
 * open. [Conflict] does not.
 */
enum class BbqrSkip {
    Held,
    Conflict,
    Decode,
    Index,
    Closed,
    Other,
}

/**
 * [isActive] returned false while a part was being joined or inflated.
 * The part just offered is rolled back. Not a decode failure.
 */
internal class BbqrAbandoned : RuntimeException()

sealed class BbqrReceive {
    /**
     * [storedNewPart] is true only when this frame was a part index the
     * series did not already hold. A skip leaves the count where it is
     * and names [skip]. [partIndex] is the header's index when the frame
     * had one. [thisSeries] is false when the frame's six-character
     * signature is not the series being collected.
     */
    data class Pending(
        val progress: BbqrProgress,
        val storedNewPart: Boolean,
        val skip: BbqrSkip? = null,
        val partIndex: Int? = null,
        val thisSeries: Boolean = true,
    ) : BbqrReceive()

    /**
     * A later body disagreed with the part already stored at [partIndex].
     * Stored parts are dropped. Further frames of this signature do not
     * collect until [BbqrAccumulator.reset].
     */
    data class Conflict(
        val partIndex: Int,
        val progress: BbqrProgress,
    ) : BbqrReceive()

    class Complete(
        val fileType: Char,
        val bytes: ByteArray,
        val progress: BbqrProgress
    ) : BbqrReceive()
    data class Rejected(val failure: BbqrFailure) : BbqrReceive()
}

private sealed class DecodedBody {
    data class Ok(val bytes: ByteArray) : DecodedBody()
    object Invalid : DecodedBody()
    object OverCap : DecodedBody()
}

/**
 * Streaming reassembly of one BBQr series while a label QR is in frame.
 *
 * Parts may arrive in any order. A repeat of an index already stored
 * with the same body is ignored (animated QR loops). A later frame whose
 * body disagrees is a conflict: the collected parts are dropped and
 * nothing from this mix is joined. The format has no export id, so two
 * exports that never repeat an index still cannot be told apart here.
 *
 * A frame that looks like BBQr but cannot be decoded, or whose index
 * does not address a part, is skipped: nothing is toasted, the series
 * stays open, and parts already held are kept. A part that would push
 * the encoded or decoded total past [BBQR_MAX_DECODED_BYTES] is not
 * allocated. `Z` is raw deflate of the whole file (zlib window bits 10,
 * no header) before the base32 split, so inflation waits until every
 * part is present. Each part is decoded on its own: base32 and hex
 * quanta do not span parts. Join or inflate failure once every index is
 * present still fails the series.
 *
 * [receive] polls [isActive] while it joins and inflates so a dismiss
 * or an explicit [reset] can abandon that work. Callers that finish a
 * series move that work off the camera thread.
 *
 * A decodable frame whose first six characters differ from the series in
 * progress starts a new series, so a second export can be scanned without
 * leaving the sheet. This accumulator is only fed frames the plain-QR callback
 * delivers. Once a UR session has started, [com.invertedx.hummingbird.QRScanner]
 * drops non-UR frames, BBQr included, and that split stays in the scanner.
 */
class BbqrAccumulator {
    private var signature: String? = null
    private var encoding: Char = 'H'
    private var fileType: Char = 'U'
    private var partCount: Int = 0
    private var closed: Boolean = false
    private var conflicted: Boolean = false
    private var seriesId: Int = 0
    private var decodedTotal: Int = 0
    private var encodedTotal: Long = 0
    private val bodies = HashMap<Int, String>()
    private val parts = HashMap<Int, ByteArray>()

    /** Parts currently held. [BbqrProgress.total] is 0 before the first frame. */
    fun progress(): BbqrProgress = BbqrProgress(parts.size, partCount)

    /**
     * Indexes in `0 until partCount` that are not stored yet, ascending.
     * A conflict, or a series that already closed, is not collecting, so
     * it reports no holes.
     */
    fun missingIndexes(): List<Int> {
        if (partCount <= 0 || closed || conflicted) return emptyList()
        val missing = ArrayList<Int>(partCount - parts.size)
        for (index in 0 until partCount) {
            if (!parts.containsKey(index)) missing.add(index)
        }
        return missing
    }

    /** Bumps when a new six-character signature replaces the series, and on [reset]. */
    fun seriesId(): Int = seriesId

    /**
     * Drops the series so the same export can be scanned from the start.
     * A conflict stays put until this runs or a different signature arrives.
     */
    fun reset() {
        signature = null
        encoding = 'H'
        fileType = 'U'
        partCount = 0
        closed = false
        conflicted = false
        decodedTotal = 0
        encodedTotal = 0
        bodies.clear()
        parts.clear()
        seriesId += 1
    }

    fun receive(frame: String, isActive: () -> Boolean = { true }): BbqrReceive {
        checkActive(isActive)
        val header = parseBbqrHeader(frame)
        if (header == null) {
            val raw = rawCounts(frame)
            return if (raw != null && (raw.count <= 0 || raw.index >= raw.count)) {
                skipped(BbqrSkip.Index, raw.index, raw.signature == signature)
            } else {
                skipped(BbqrSkip.Other, null, thisSeries = false)
            }
        }
        val switching = header.signature != signature
        if (!switching && conflicted) return skipped(BbqrSkip.Conflict, header.partIndex)
        if (!switching && closed) return skipped(BbqrSkip.Closed, header.partIndex)
        if (!isBbqrLabelText(header.fileType)) {
            begin(header)
            closed = true
            return BbqrReceive.Rejected(BbqrFailure.FileType(header.fileType))
        }
        if (!switching) {
            val previous = bodies[header.partIndex]
            if (previous != null) {
                if (previous == header.body) return skipped(BbqrSkip.Held, header.partIndex)
                return noteConflict(header.partIndex)
            }
        }
        val decodedLen = decodedBodyLen(header.encoding, header.body)
            ?: return skipped(BbqrSkip.Decode, header.partIndex, thisSeries = !switching)
        val remainingDecoded = if (switching) {
            BBQR_MAX_DECODED_BYTES.toLong()
        } else {
            BBQR_MAX_DECODED_BYTES.toLong() - decodedTotal
        }
        val remainingEncoded = if (switching) {
            BBQR_MAX_ENCODED_CHARS.toLong()
        } else {
            BBQR_MAX_ENCODED_CHARS.toLong() - encodedTotal
        }
        val overBudget = decodedLen > remainingDecoded ||
            header.body.length.toLong() > remainingEncoded
        // Negative tells the decoder to validate and not allocate.
        val maxAlloc = if (overBudget) -1 else remainingDecoded.toInt()
        val decoded = when (
            val outcome = decodeBody(header.encoding, header.body, maxAlloc, isActive)
        ) {
            is DecodedBody.Ok -> outcome.bytes
            DecodedBody.Invalid ->
                return skipped(BbqrSkip.Decode, header.partIndex, thisSeries = !switching)
            DecodedBody.OverCap -> return rejectBudget(switching, header)
        }
        if (switching) begin(header)
        val index = header.partIndex
        bodies[index] = header.body
        parts[index] = decoded
        decodedTotal += decoded.size
        encodedTotal += header.body.length.toLong()
        if (parts.size < partCount) return pending(storedNewPart = true)
        try {
            val joined = joinParts(isActive) ?: return failDecode()
            val raw = if (encoding == 'Z') {
                inflateRaw(joined, isActive) ?: return failDecode()
            } else {
                checkActive(isActive)
                joined
            }
            closed = true
            return BbqrReceive.Complete(fileType, raw, progress())
        } catch (abandoned: BbqrAbandoned) {
            unstore(index, header.body, decoded.size)
            throw abandoned
        }
    }

    /** Not stored. Progress stays where it is, and the series stays open. */
    private fun skipped(
        reason: BbqrSkip,
        index: Int?,
        thisSeries: Boolean = true,
    ): BbqrReceive.Pending = BbqrReceive.Pending(
        progress(),
        storedNewPart = false,
        skip = reason,
        partIndex = index,
        thisSeries = thisSeries,
    )

    private fun pending(storedNewPart: Boolean): BbqrReceive.Pending =
        BbqrReceive.Pending(progress(), storedNewPart)

    private fun begin(header: BbqrHeader) {
        seriesId += 1
        signature = header.signature
        encoding = header.encoding
        fileType = header.fileType
        partCount = header.partCount
        closed = false
        conflicted = false
        clearStored()
    }

    private fun noteConflict(index: Int): BbqrReceive {
        conflicted = true
        clearStored()
        return BbqrReceive.Conflict(index, progress())
    }

    /**
     * A part that fits the header but not the byte budget. The series
     * being collected is closed. A different signature does not wipe a
     * series that is still open.
     */
    private fun rejectBudget(switching: Boolean, header: BbqrHeader): BbqrReceive {
        val preserveOpen = switching && signature != null && !closed && !conflicted
        if (preserveOpen) {
            return skipped(BbqrSkip.Decode, header.partIndex, thisSeries = false)
        }
        if (switching) begin(header)
        closed = true
        clearStored()
        return BbqrReceive.Rejected(BbqrFailure.Decode)
    }

    private fun failDecode(): BbqrReceive {
        closed = true
        clearStored()
        return BbqrReceive.Rejected(BbqrFailure.Decode)
    }

    private fun clearStored() {
        bodies.clear()
        parts.clear()
        decodedTotal = 0
        encodedTotal = 0
    }

    private fun unstore(index: Int, body: String, decodedSize: Int) {
        if (bodies[index] != body) return
        bodies.remove(index)
        parts.remove(index)
        decodedTotal -= decodedSize
        encodedTotal -= body.length.toLong()
        if (decodedTotal < 0) decodedTotal = 0
        if (encodedTotal < 0L) encodedTotal = 0L
    }

    /**
     * Sum the parts first. The joined array is allocated only when the
     * total is inside the cap.
     */
    private fun joinParts(isActive: () -> Boolean): ByteArray? {
        checkActive(isActive)
        var total = 0L
        for (index in 0 until partCount) {
            val part = parts[index] ?: return null
            total += part.size.toLong()
            if (total > BBQR_MAX_DECODED_BYTES) return null
        }
        checkActive(isActive)
        val out = ByteArray(total.toInt())
        var offset = 0
        for (index in 0 until partCount) {
            val part = parts[index] ?: return null
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }
}

private data class RawBbqrCount(val signature: String, val count: Int, val index: Int)

/** Header digits even when the index cannot address a part. Null when [frame] is not BBQr. */
private fun rawCounts(frame: String): RawBbqrCount? {
    if (!isBbqrFrame(frame)) return null
    return RawBbqrCount(
        signature = frame.substring(0, 6),
        count = frame.substring(4, 6).toInt(36),
        index = frame.substring(6, 8).toInt(36),
    )
}

private fun checkActive(isActive: () -> Boolean) {
    if (!isActive()) throw BbqrAbandoned()
}

/** Decoded length, or null when the character count cannot land on bytes. */
private fun decodedBodyLen(encoding: Char, body: String): Long? = when (encoding) {
    'H' -> {
        if (body.length % 2 != 0) null
        else body.length.toLong() / 2L
    }
    '2', 'Z' -> {
        if (body.isEmpty()) {
            0L
        } else {
            val rem = body.length % 8
            if (rem == 1 || rem == 3 || rem == 6) null
            else body.length.toLong() * 5L / 8L
        }
    }
    else -> null
}

/**
 * [maxBytes] below zero validates the body and does not allocate. A
 * well-formed body that does not fit is [DecodedBody.OverCap].
 */
private fun decodeBody(
    encoding: Char,
    body: String,
    maxBytes: Int,
    isActive: () -> Boolean,
): DecodedBody = when (encoding) {
    'H' -> decodeHex(body, maxBytes, isActive)
    '2', 'Z' -> decodeBase32(body, maxBytes, isActive)
    else -> DecodedBody.Invalid
}

private fun decodeHex(body: String, maxBytes: Int, isActive: () -> Boolean): DecodedBody {
    if (body.length % 2 != 0) return DecodedBody.Invalid
    val size = body.length.toLong() / 2L
    val allocate = maxBytes >= 0 && size <= maxBytes.toLong()
    val out = if (allocate) ByteArray(size.toInt()) else null
    var i = 0
    var written = 0
    while (i < body.length) {
        if (i != 0 && (i and 0xFFFF) == 0) checkActive(isActive)
        val hi = hexValue(body[i]) ?: return DecodedBody.Invalid
        val lo = hexValue(body[i + 1]) ?: return DecodedBody.Invalid
        if (out != null) {
            if (written >= out.size) return DecodedBody.Invalid
            out[written] = ((hi shl 4) or lo).toByte()
            written += 1
        }
        i += 2
    }
    if (out == null) return DecodedBody.OverCap
    if (written != out.size) return DecodedBody.Invalid
    return DecodedBody.Ok(out)
}

private fun hexValue(c: Char): Int? = when (c) {
    in '0'..'9' -> c - '0'
    in 'A'..'F' -> c - 'A' + 10
    in 'a'..'f' -> c - 'a' + 10
    else -> null
}

/**
 * RFC 4648 base32, padding omitted. A part is its own quantum: lengths
 * that cannot land on a byte (1, 3, or 6 characters) are rejected, and
 * leftover padding bits must be zero.
 */
private fun decodeBase32(body: String, maxBytes: Int, isActive: () -> Boolean): DecodedBody {
    if (body.isEmpty()) {
        return if (maxBytes >= 0) DecodedBody.Ok(ByteArray(0)) else DecodedBody.OverCap
    }
    val rem = body.length % 8
    if (rem == 1 || rem == 3 || rem == 6) return DecodedBody.Invalid
    val size = body.length.toLong() * 5L / 8L
    val allocate = maxBytes >= 0 && size <= maxBytes.toLong()
    val out = if (allocate) ByteArray(size.toInt()) else null
    var acc = 0
    var bits = 0
    var written = 0
    var n = 0
    for (c in body) {
        if (n != 0 && (n and 0xFFFF) == 0) checkActive(isActive)
        n += 1
        val value = BASE32_ALPHABET.indexOf(c)
        if (value < 0) return DecodedBody.Invalid
        acc = (acc shl 5) or value
        bits += 5
        if (bits >= 8) {
            bits -= 8
            if (out != null) {
                if (written >= out.size) return DecodedBody.Invalid
                out[written] = ((acc shr bits) and 0xFF).toByte()
                written += 1
            }
        }
        acc = acc and ((1 shl bits) - 1)
    }
    if (bits > 0 && acc != 0) return DecodedBody.Invalid
    if (out == null) return DecodedBody.OverCap
    if (written != out.size) return DecodedBody.Invalid
    return DecodedBody.Ok(out)
}

/**
 * Raw deflate, the `Z` encoding. Producers use zlib window bits 10 and
 * no header; a larger window still inflates that stream. java.util.zip
 * with nowrap sometimes needs one dummy input byte before it reports
 * finished. That byte is synthetic: it is offered only after the
 * original buffer is empty, and it is not counted as original input.
 * Bytes of the original buffer still unread when the stream finishes
 * are corruption (a trailing tail or a second member), not success.
 * Output is never written past [BBQR_MAX_DECODED_BYTES].
 */
internal fun inflateRaw(data: ByteArray, isActive: () -> Boolean = { true }): ByteArray? {
    if (data.isEmpty() || data.size > BBQR_MAX_DECODED_BYTES) return null
    checkActive(isActive)
    val inflater = Inflater(true)
    return try {
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var dummyFed = false
        var produced = 0
        while (!inflater.finished()) {
            checkActive(isActive)
            val n = inflater.inflate(buf)
            if (n > 0) {
                produced += n
                if (produced > BBQR_MAX_DECODED_BYTES) return null
                out.write(buf, 0, n)
                continue
            }
            if (inflater.finished()) break
            if (!dummyFed && inflater.needsInput()) {
                // needsInput means the current buffer is empty. Refuse to
                // swap in the dummy while any original byte is still queued.
                if (inflater.remaining != 0 || inflater.totalIn != data.size) return null
                dummyFed = true
                inflater.setInput(byteArrayOf(0))
                continue
            }
            return null
        }
        if (unconsumedOriginal(data.size, inflater.totalIn, inflater.remaining, dummyFed) != 0) {
            return null
        }
        out.toByteArray()
    } catch (abandoned: BbqrAbandoned) {
        throw abandoned
    } catch (_: Exception) {
        null
    } finally {
        inflater.end()
    }
}

/**
 * Original compressed bytes not consumed. The synthetic dummy is one
 * byte and is subtracted when [dummyFed] is set; a leftover dummy byte
 * is not original input.
 */
private fun unconsumedOriginal(
    dataSize: Int,
    totalIn: Int,
    remaining: Int,
    dummyFed: Boolean,
): Int {
    if (!dummyFed) {
        if (remaining != 0) return remaining
        return dataSize - totalIn
    }
    if (remaining != 0 && remaining != 1) return dataSize
    val dummyConsumed = 1 - remaining
    val consumedOriginal = totalIn - dummyConsumed
    return dataSize - consumedOriginal
}
