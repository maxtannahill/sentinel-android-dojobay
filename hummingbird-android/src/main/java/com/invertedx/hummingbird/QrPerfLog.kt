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
 * One QRPerf line. Counters and timings only: the arguments are numbers,
 * so a payload, label, or frame cannot land in logcat from here.
 * The original prefix (through `elapsedMs`) stays in place; the #71
 * part-outcome and frame fields are appended.
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
    rowStride: Int
): String =
    "session=$session event=$event attempts=$attempts decoded=$decoded " +
        "accepted=$accepted expected=$expected pct=$percent elapsedMs=$elapsedMs " +
        "newParts=$newParts repeatParts=$repeatParts rejectedParts=$rejectedParts " +
        "avgAnalyzeMs=$avgAnalyzeMs width=$width height=$height rowStride=$rowStride"
