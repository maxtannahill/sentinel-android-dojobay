package com.samourai.sentinel.ui.fragments

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

internal const val BBQR_HEADER_LENGTH = 8

/** Decoded label text is capped here so a hostile zlib stream cannot expand without bound. */
private const val BBQR_MAX_DECODED_BYTES = 4 * 1024 * 1024

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
 * Why a BBQr frame was not stored. Collection stays open either way.
 * [Held] and [Keep] are an index already stored (same body, or a later
 * disagreement that keeps the first body). [Decode] is a body that does
 * not decode. [Index] is an index past the header count, or a count of
 * zero. [Closed] is a frame for a series that already finished. [Other]
 * is not a BBQr header.
 */
enum class BbqrSkip {
    Held,
    Keep,
    Decode,
    Index,
    Closed,
    Other,
}

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
    class Complete(
        val fileType: Char,
        val bytes: ByteArray,
        val progress: BbqrProgress
    ) : BbqrReceive()
    data class Rejected(val failure: BbqrFailure) : BbqrReceive()
}

/**
 * Streaming reassembly of one BBQr series while a label QR is in frame.
 *
 * Parts may arrive in any order. A repeat of an index already stored is
 * ignored (animated QR loops). A later scan that disagrees with the body
 * already stored is ignored too; the first body stays. A frame that looks
 * like BBQr but cannot be decoded, or whose index does not address a
 * part, is skipped: nothing is toasted, the series stays open, and parts
 * already held are kept. `Z` is raw deflate of the whole file (zlib
 * window bits 10, no header) before the base32 split, so inflation waits
 * until every part is present. Each part is decoded on its own: base32
 * and hex quanta do not span parts. Join or inflate failure once every
 * index is present still fails the series.
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
    private var seriesId: Int = 0
    private val bodies = HashMap<Int, String>()
    private val parts = HashMap<Int, ByteArray>()

    /** Parts currently held. [BbqrProgress.total] is 0 before the first frame. */
    fun progress(): BbqrProgress = BbqrProgress(parts.size, partCount)

    /** Indexes in `0 until partCount` that are not stored yet, ascending. */
    fun missingIndexes(): List<Int> {
        if (partCount <= 0) return emptyList()
        val missing = ArrayList<Int>(partCount - parts.size)
        for (index in 0 until partCount) {
            if (!parts.containsKey(index)) missing.add(index)
        }
        return missing
    }

    /** Bumps when a new six-character signature replaces the series. */
    fun seriesId(): Int = seriesId

    fun receive(frame: String): BbqrReceive {
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
        if (!switching && closed) return skipped(BbqrSkip.Closed, header.partIndex)
        if (!isBbqrLabelText(header.fileType)) {
            begin(header)
            closed = true
            return BbqrReceive.Rejected(BbqrFailure.FileType(header.fileType))
        }
        if (!switching) {
            val previous = bodies[header.partIndex]
            if (previous != null) {
                val reason = if (previous == header.body) BbqrSkip.Held else BbqrSkip.Keep
                return skipped(reason, header.partIndex)
            }
        }
        val decoded = decodeBody(header.encoding, header.body)
            ?: return skipped(BbqrSkip.Decode, header.partIndex, thisSeries = !switching)
        if (switching) begin(header)
        bodies[header.partIndex] = header.body
        parts[header.partIndex] = decoded
        if (parts.size < partCount) return pending(storedNewPart = true)
        val joined = joinParts()
        if (joined == null || joined.size > BBQR_MAX_DECODED_BYTES) {
            closed = true
            return BbqrReceive.Rejected(BbqrFailure.Decode)
        }
        val raw = if (encoding == 'Z') {
            inflateRaw(joined) ?: run {
                closed = true
                return BbqrReceive.Rejected(BbqrFailure.Decode)
            }
        } else {
            joined
        }
        closed = true
        return BbqrReceive.Complete(fileType, raw, progress())
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
        bodies.clear()
        parts.clear()
    }

    private fun joinParts(): ByteArray? {
        val out = ByteArrayOutputStream()
        for (index in 0 until partCount) {
            val part = parts[index] ?: return null
            out.write(part)
        }
        return out.toByteArray()
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

private fun decodeBody(encoding: Char, body: String): ByteArray? = when (encoding) {
    'H' -> decodeHex(body)
    '2', 'Z' -> decodeBase32(body)
    else -> null
}

private fun decodeHex(body: String): ByteArray? {
    if (body.length % 2 != 0) return null
    val out = ByteArray(body.length / 2)
    var i = 0
    while (i < body.length) {
        val hi = hexValue(body[i]) ?: return null
        val lo = hexValue(body[i + 1]) ?: return null
        out[i / 2] = ((hi shl 4) or lo).toByte()
        i += 2
    }
    return out
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
private fun decodeBase32(body: String): ByteArray? {
    if (body.isEmpty()) return ByteArray(0)
    val rem = body.length % 8
    if (rem == 1 || rem == 3 || rem == 6) return null
    val out = ByteArrayOutputStream(body.length * 5 / 8)
    var acc = 0
    var bits = 0
    for (c in body) {
        val value = BASE32_ALPHABET.indexOf(c)
        if (value < 0) return null
        acc = (acc shl 5) or value
        bits += 5
        if (bits >= 8) {
            bits -= 8
            out.write((acc shr bits) and 0xFF)
        }
        acc = acc and ((1 shl bits) - 1)
    }
    if (bits > 0 && acc != 0) return null
    return out.toByteArray()
}

/**
 * Raw deflate, the `Z` encoding. Producers use zlib window bits 10 and
 * no header; a larger window still inflates that stream. java.util.zip
 * with nowrap sometimes needs one dummy input byte before it reports
 * finished, so that byte is offered only once the inflater asks for more
 * input and has produced nothing.
 */
private fun inflateRaw(data: ByteArray): ByteArray? {
    if (data.isEmpty() || data.size > BBQR_MAX_DECODED_BYTES) return null
    val inflater = Inflater(true)
    return try {
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var dummyFed = false
        var produced = 0
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n > 0) {
                produced += n
                if (produced > BBQR_MAX_DECODED_BYTES) return null
                out.write(buf, 0, n)
                continue
            }
            if (inflater.finished()) break
            if (!dummyFed && inflater.needsInput()) {
                dummyFed = true
                inflater.setInput(byteArrayOf(0))
                continue
            }
            return null
        }
        out.toByteArray()
    } catch (_: Exception) {
        null
    } finally {
        inflater.end()
    }
}
