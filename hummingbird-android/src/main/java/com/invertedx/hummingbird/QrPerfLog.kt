package com.invertedx.hummingbird

/**
 * Y-plane size captured once per QRPerf session. Later frames do not
 * overwrite it: the log line stays comparable for the whole scan.
 */
internal data class FrameGeometry(
    val width: Int,
    val height: Int,
    val rowStride: Int
)

internal fun captureFrameGeometry(
    current: FrameGeometry?,
    width: Int,
    height: Int,
    rowStride: Int
): FrameGeometry? {
    if (current != null) return current
    if (width <= 0 || height <= 0 || rowStride <= 0) return null
    return FrameGeometry(width, height, rowStride)
}

/** Rounded mean of analyze() durations. Zero when nothing has been timed. */
internal fun averageAnalyzeMs(totalNs: Long, samples: Int): Int {
    if (samples <= 0 || totalNs <= 0L) return 0
    val avgNs = totalNs / samples.toLong()
    return ((avgNs + 500_000L) / 1_000_000L).toInt()
}

/**
 * How many missing BBQr indexes are still written out as numbers.
 * More than this is a count only.
 */
internal const val BBQR_MISS_LIST_LIMIT = 8

/**
 * `bbqrMiss=N bbqrMissIdx=...`. A handful of holes names the indexes.
 * A long gap keeps the count and sets `bbqrMissIdx=*`. None is `-`.
 */
internal fun formatBbqrMissing(indexes: Collection<Int>): String {
    val unique = indexes.toSortedSet()
    return "bbqrMiss=${unique.size} bbqrMissIdx=${formatBbqrIndexList(unique)}"
}

/**
 * Indexes joined in order, `-` when there are none, `*` when there are
 * too many to list. Built only from the integers passed in.
 */
internal fun formatBbqrIndexList(indexes: Collection<Int>): String {
    val unique = indexes.toSortedSet()
    if (unique.isEmpty()) return "-"
    if (unique.size > BBQR_MISS_LIST_LIMIT) return "*"
    return unique.joinToString(",")
}

/**
 * One QRPerf line. Counters, timings, and BBQr part indexes only: the
 * arguments are numbers, so a payload, label, or frame cannot land in
 * logcat from here. The original prefix (through `elapsedMs`) stays in
 * place; the #71 part-outcome and frame fields follow, then the BBQr
 * missing-index and skip counters.
 */
internal fun qrPerfLogLine(
    session: Int,
    event: String,
    attempts: Int,
    decoded: Int,
    accepted: Int,
    expected: Int,
    percent: Int,
    elapsedMs: Long,
    newParts: Int,
    repeatParts: Int,
    rejectedParts: Int,
    avgAnalyzeMs: Int,
    width: Int,
    height: Int,
    rowStride: Int,
    bbqrMissing: IntArray = intArrayOf(),
    bbqrHeld: Int = 0,
    bbqrKeep: Int = 0,
    bbqrDecode: Int = 0,
    bbqrIndex: Int = 0,
    bbqrClosed: Int = 0,
    bbqrOther: Int = 0,
    bbqrDecodeIdx: IntArray = intArrayOf(),
): String =
    "session=$session event=$event attempts=$attempts decoded=$decoded " +
        "accepted=$accepted expected=$expected pct=$percent elapsedMs=$elapsedMs " +
        "newParts=$newParts repeatParts=$repeatParts rejectedParts=$rejectedParts " +
        "avgAnalyzeMs=$avgAnalyzeMs width=$width height=$height rowStride=$rowStride " +
        "${formatBbqrMissing(bbqrMissing.toList())} " +
        "bbqrHeld=$bbqrHeld bbqrKeep=$bbqrKeep bbqrDecode=$bbqrDecode " +
        "bbqrIndex=$bbqrIndex bbqrClosed=$bbqrClosed bbqrOther=$bbqrOther " +
        "bbqrDecodeIdx=${formatBbqrIndexList(bbqrDecodeIdx.toList())}"

/** First time a this-series BBQr index fails to decode. Index and reason only. */
internal fun qrPerfBbqrSkipLine(session: Int, reason: BbqrSkipReason, index: Int): String =
    "session=$session event=bbqrSkip reason=${reason.logName()} index=$index"
