package com.invertedx.hummingbird

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Why a BBQr frame was not stored. Logged as a counter name, never as
 * frame bytes. [Held] is an index already stored with the same body.
 * [Conflict] is an index already stored whose new body disagrees; the
 * series is discarded. [Decode] is a body that does not decode. [Index]
 * is past the header count. [Closed] is a finished series. [Other] is
 * not a header.
 */
enum class BbqrSkipReason {
    Held,
    Conflict,
    Decode,
    Index,
    Closed,
    Other;

    internal fun logName(): String = when (this) {
        Held -> "held"
        Conflict -> "conflict"
        Decode -> "decode"
        Index -> "index"
        Closed -> "closed"
        Other -> "other"
    }
}

/**
 * #71 measurement only. Counts and timings for `adb logcat -s QRPerf`.
 *
 * The analyze() path only increments integers and records how long the
 * frame took. A line is written every [FRAME_LOG_INTERVAL] entries and
 * on session start, stop, and UR complete. Lines carry counters and
 * BBQr part indexes only: no frame bytes, payloads, labels, xpubs, or
 * other user data.
 *
 * `accepted` / `expected` are the latest transmission gauge. UR reports
 * the decoder's processedPartsCount and expectedPartCount. BBQr label
 * import reports unique parts stored and the header's part count, and
 * only when a new index is stored. `newParts` / `repeatParts` /
 * `rejectedParts` are UR frames only. BBQr skips use `bbqrHeld` /
 * `bbqrConflict` / `bbqrDecode` / `bbqrIndex` / `bbqrClosed` / `bbqrOther`,
 * and `bbqrMiss` / `bbqrMissIdx` name the indexes still missing when
 * only a few remain. A decoded frame that never entered the UR decoder
 * is the remainder: `decoded - newParts - repeatParts - rejectedParts`.
 * `width` / `height` / `rowStride` are sampled from the first YUV frame.
 */
object QrPerf {
    const val TAG = "QRPerf"

    private const val FRAME_LOG_INTERVAL = 100

    private val sessionId = AtomicInteger(0)
    private val attempts = AtomicInteger(0)
    private val decoded = AtomicInteger(0)
    private val accepted = AtomicInteger(0)
    private val expected = AtomicInteger(0)
    private val percent = AtomicInteger(0)
    private val newParts = AtomicInteger(0)
    private val repeatParts = AtomicInteger(0)
    private val rejectedParts = AtomicInteger(0)
    private val bbqrHeld = AtomicInteger(0)
    private val bbqrConflict = AtomicInteger(0)
    private val bbqrDecode = AtomicInteger(0)
    private val bbqrIndex = AtomicInteger(0)
    private val bbqrClosed = AtomicInteger(0)
    private val bbqrOther = AtomicInteger(0)
    private val analyzeTimeSumNs = AtomicLong(0)
    private val analyzeSamples = AtomicInteger(0)

    @Volatile
    private var sessionStartElapsedMs: Long = 0L

    @Volatile
    private var completeLogged: Boolean = false

    @Volatile
    private var geometry: FrameGeometry? = null

    @Volatile
    private var bbqrMissing = IntArray(0)

    @Volatile
    private var bbqrDecodeIdx = IntArray(0)

    @Volatile
    private var bbqrSeries = Int.MIN_VALUE
    private val decodeNoted = HashSet<Int>()

    internal fun onSessionStart() {
        sessionId.incrementAndGet()
        attempts.set(0)
        decoded.set(0)
        accepted.set(0)
        expected.set(0)
        percent.set(0)
        newParts.set(0)
        repeatParts.set(0)
        rejectedParts.set(0)
        bbqrHeld.set(0)
        bbqrConflict.set(0)
        bbqrDecode.set(0)
        bbqrIndex.set(0)
        bbqrClosed.set(0)
        bbqrOther.set(0)
        analyzeTimeSumNs.set(0)
        analyzeSamples.set(0)
        geometry = null
        completeLogged = false
        bbqrMissing = IntArray(0)
        bbqrSeries = Int.MIN_VALUE
        synchronized(decodeNoted) {
            decodeNoted.clear()
            bbqrDecodeIdx = IntArray(0)
        }
        sessionStartElapsedMs = SystemClock.elapsedRealtime()
        log("start")
    }

    /**
     * One analyze() entry. Returns true on the periodic boundary so the
     * caller can log after this frame's decode result is recorded.
     */
    internal fun noteAttempt(): Boolean {
        val n = attempts.incrementAndGet()
        return n % FRAME_LOG_INTERVAL == 0
    }

    internal fun noteDecoded() {
        decoded.incrementAndGet()
    }

    /** One analyze() duration, in nanoseconds. Failures count too. */
    internal fun noteAnalyzeDurationNs(elapsedNs: Long) {
        if (elapsedNs < 0L) return
        analyzeTimeSumNs.addAndGet(elapsedNs)
        analyzeSamples.incrementAndGet()
    }

    /**
     * First valid frame wins. A later resolution change in the same
     * session does not rewrite the sample.
     */
    internal fun noteFrameGeometry(width: Int, height: Int, yRowStride: Int) {
        val current = geometry
        val next = captureFrameGeometry(current, width, height, yRowStride)
        if (next !== current) {
            geometry = next
        }
    }

    internal fun noteUrOutcome(outcome: UrPartOutcome) {
        when (outcome) {
            UrPartOutcome.NEW -> newParts.incrementAndGet()
            UrPartOutcome.REPEAT -> repeatParts.incrementAndGet()
            UrPartOutcome.REJECTED -> rejectedParts.incrementAndGet()
            UrPartOutcome.NOT_UR -> Unit
        }
    }

    /**
     * Latest transmission gauge. UR passes the decoder's accepted
     * (non-duplicate) part count. BBQr label import passes unique parts
     * stored and the header part count. Stores only; milestones log.
     */
    fun noteTransmission(expectedParts: Int, processedParts: Int, progress: Double) {
        expected.set(expectedParts)
        accepted.set(processedParts)
        percent.set((progress * 100.0).roundToInt())
    }

    /**
     * Indexes still missing from the BBQr series in progress. A new
     * [seriesGeneration] drops the decode-failure notes from the previous
     * series. Stores only; milestones log.
     */
    fun noteBbqrMissing(indexes: Collection<Int>, seriesGeneration: Int) {
        if (seriesGeneration != bbqrSeries) {
            bbqrSeries = seriesGeneration
            synchronized(decodeNoted) {
                decodeNoted.clear()
                bbqrDecodeIdx = IntArray(0)
            }
        }
        bbqrMissing = indexes.toSortedSet().toIntArray()
    }

    /**
     * One skipped BBQr frame. Counters stay until the next milestone.
     * The first time a this-series index fails to decode, [oneShot]
     * writes a single line with that index and no frame bytes.
     */
    fun noteBbqrSkip(reason: BbqrSkipReason, index: Int?, oneShot: Boolean) {
        when (reason) {
            BbqrSkipReason.Held -> bbqrHeld.incrementAndGet()
            BbqrSkipReason.Conflict -> bbqrConflict.incrementAndGet()
            BbqrSkipReason.Decode -> bbqrDecode.incrementAndGet()
            BbqrSkipReason.Index -> bbqrIndex.incrementAndGet()
            BbqrSkipReason.Closed -> bbqrClosed.incrementAndGet()
            BbqrSkipReason.Other -> bbqrOther.incrementAndGet()
        }
        if (reason != BbqrSkipReason.Decode || index == null || !oneShot) return
        val first = synchronized(decodeNoted) {
            if (!decodeNoted.add(index)) {
                false
            } else {
                bbqrDecodeIdx = decodeNoted.toIntArray()
                true
            }
        }
        if (first) {
            Log.i(TAG, qrPerfBbqrSkipLine(sessionId.get(), reason, index))
        }
    }

    internal fun logPeriodic() {
        log("frames")
    }

    internal fun onSessionComplete() {
        if (completeLogged) return
        completeLogged = true
        log("complete")
    }

    internal fun onSessionStop() {
        log("stop")
    }

    private fun log(event: String) {
        val elapsed = SystemClock.elapsedRealtime() - sessionStartElapsedMs
        val frame = geometry
        Log.i(
            TAG,
            qrPerfLogLine(
                session = sessionId.get(),
                event = event,
                attempts = attempts.get(),
                decoded = decoded.get(),
                accepted = accepted.get(),
                expected = expected.get(),
                percent = percent.get(),
                elapsedMs = elapsed,
                newParts = newParts.get(),
                repeatParts = repeatParts.get(),
                rejectedParts = rejectedParts.get(),
                avgAnalyzeMs = averageAnalyzeMs(analyzeTimeSumNs.get(), analyzeSamples.get()),
                width = frame?.width ?: 0,
                height = frame?.height ?: 0,
                rowStride = frame?.rowStride ?: 0,
                bbqrMissing = bbqrMissing,
                bbqrHeld = bbqrHeld.get(),
                bbqrConflict = bbqrConflict.get(),
                bbqrDecode = bbqrDecode.get(),
                bbqrIndex = bbqrIndex.get(),
                bbqrClosed = bbqrClosed.get(),
                bbqrOther = bbqrOther.get(),
                bbqrDecodeIdx = bbqrDecodeIdx
            )
        )
    }
}
