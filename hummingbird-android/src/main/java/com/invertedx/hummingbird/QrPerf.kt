package com.invertedx.hummingbird

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * #71 measurement only. Counts and timings for `adb logcat -s QRPerf`.
 *
 * The analyze() path only increments integers and records how long the
 * frame took. A line is written every [FRAME_LOG_INTERVAL] entries and
 * on session start, stop, and UR complete. Lines carry counters only:
 * no frame bytes, payloads, labels, xpubs, or other user data.
 *
 * `accepted` is still the decoder's processedPartsCount (distinct fountain
 * parts by content hash, not recovered fragments). `newParts` /
 * `repeatParts` / `rejectedParts` are UR frames only. A decoded frame
 * that never entered the UR decoder is the remainder:
 * `decoded - newParts - repeatParts - rejectedParts`.
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
    private val analyzeTimeSumNs = AtomicLong(0)
    private val analyzeSamples = AtomicInteger(0)

    @Volatile
    private var sessionStartElapsedMs: Long = 0L

    @Volatile
    private var completeLogged: Boolean = false

    @Volatile
    private var geometry: FrameGeometry? = null

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
        analyzeTimeSumNs.set(0)
        analyzeSamples.set(0)
        geometry = null
        completeLogged = false
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
     * Latest UR transmission gauge. [processedParts] is the decoder's
     * accepted (non-duplicate) part count. Stores only; milestones log.
     */
    fun noteTransmission(expectedParts: Int, processedParts: Int, progress: Double) {
        expected.set(expectedParts)
        accepted.set(processedParts)
        percent.set((progress * 100.0).roundToInt())
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
                rowStride = frame?.rowStride ?: 0
            )
        )
    }
}
