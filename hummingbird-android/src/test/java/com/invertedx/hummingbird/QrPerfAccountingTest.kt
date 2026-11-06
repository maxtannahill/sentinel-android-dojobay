package com.invertedx.hummingbird

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class QrPerfAccountingTest {

    @Test
    fun plainQrIsNotAUrRejection() {
        assertEquals(
            UrPartOutcome.NOT_UR,
            classifyUrPartOutcome(
                text = "xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWZiP5e",
                received = false,
                processedBefore = 0,
                processedAfter = 0,
                hadResultBefore = false,
                hasResultAfter = false
            )
        )
    }

    @Test
    fun newFountainPartIsTheProcessedCountIncrease() {
        assertEquals(
            UrPartOutcome.NEW,
            classifyUrPartOutcome(
                text = "UR:BYTES/1-396/AE",
                received = true,
                processedBefore = 4,
                processedAfter = 5,
                hadResultBefore = false,
                hasResultAfter = false
            )
        )
    }

    @Test
    fun duplicateFountainPartIsARepeatWhenReceivePartReturnsTrue() {
        // Hummingbird 1.7.4 returns true for a part whose content hash
        // was already counted, and leaves processedPartsCount unchanged.
        assertEquals(
            UrPartOutcome.REPEAT,
            classifyUrPartOutcome(
                text = "ur:bytes/3-396/AE",
                received = true,
                processedBefore = 12,
                processedAfter = 12,
                hadResultBefore = false,
                hasResultAfter = false
            )
        )
    }

    @Test
    fun malformedUrIsRejected() {
        assertEquals(
            UrPartOutcome.REJECTED,
            classifyUrPartOutcome(
                text = "UR:BYTES/not-a-part",
                received = false,
                processedBefore = 2,
                processedAfter = 2,
                hadResultBefore = false,
                hasResultAfter = false
            )
        )
    }

    @Test
    fun singlePartUrIsNewEvenThoughTheFountainCountStaysZero() {
        assertEquals(
            UrPartOutcome.NEW,
            classifyUrPartOutcome(
                text = "UR:BYTES/AE0",
                received = true,
                processedBefore = 0,
                processedAfter = 0,
                hadResultBefore = false,
                hasResultAfter = true
            )
        )
    }

    @Test
    fun frameAfterTheDecoderFinishesIsARepeat() {
        assertEquals(
            UrPartOutcome.REPEAT,
            classifyUrPartOutcome(
                text = "UR:BYTES/1-23/AE",
                received = false,
                processedBefore = 40,
                processedAfter = 40,
                hadResultBefore = true,
                hasResultAfter = true
            )
        )
    }

    @Test
    fun averageAnalyzeTimeRoundsToTheNearestMillisecond() {
        assertEquals(0, averageAnalyzeMs(0L, 0))
        assertEquals(0, averageAnalyzeMs(-1L, 3))
        assertEquals(2, averageAnalyzeMs(1_500_000L, 1))
        assertEquals(1, averageAnalyzeMs(1_499_999L, 1))
        assertEquals(10, averageAnalyzeMs(20_000_000L, 2))
    }

    @Test
    fun frameGeometryIsSampledOnce() {
        val first = captureFrameGeometry(null, 640, 480, 640)
        val again = captureFrameGeometry(first, 1280, 720, 1504)
        assertSame(first, again)
        assertEquals(640, again!!.width)
        assertEquals(480, again.height)
        assertEquals(640, again.rowStride)
        assertNull(captureFrameGeometry(null, 0, 480, 640))
        assertNull(captureFrameGeometry(null, 640, 480, 0))
    }

    @Test
    fun logLineKeepsTheOriginalPrefixAndAddsPartOutcomes() {
        val line = qrPerfLogLine(
            session = 2,
            event = "frames",
            attempts = 913,
            decoded = 128,
            accepted = 63,
            expected = 396,
            percent = 9,
            elapsedMs = 444468,
            newParts = 63,
            repeatParts = 50,
            rejectedParts = 15,
            avgAnalyzeMs = 12,
            width = 1280,
            height = 720,
            rowStride = 1504
        )
        assertEquals(
            "session=2 event=frames attempts=913 decoded=128 accepted=63 " +
                "expected=396 pct=9 elapsedMs=444468 newParts=63 repeatParts=50 " +
                "rejectedParts=15 avgAnalyzeMs=12 width=1280 height=720 rowStride=1504 " +
                "bbqrMiss=0 bbqrMissIdx=- bbqrHeld=0 bbqrKeep=0 bbqrDecode=0 " +
                "bbqrIndex=0 bbqrClosed=0 bbqrOther=0 bbqrDecodeIdx=-",
            line
        )
    }

    @Test
    fun fewMissingBbqrIndexesAreNamedAndALongGapIsACount() {
        assertEquals("bbqrMiss=0 bbqrMissIdx=-", formatBbqrMissing(emptyList()))
        assertEquals("bbqrMiss=1 bbqrMissIdx=70", formatBbqrMissing(listOf(70)))
        assertEquals("bbqrMiss=3 bbqrMissIdx=0,2,70", formatBbqrMissing(listOf(70, 0, 2)))
        assertEquals("bbqrMiss=70 bbqrMissIdx=*", formatBbqrMissing((0 until 70).toList()))
        assertEquals("-", formatBbqrIndexList(emptyList()))
        assertEquals("70", formatBbqrIndexList(listOf(70)))
        assertEquals("*", formatBbqrIndexList((0 until 9).toList()))
    }

    @Test
    fun logLineNamesTheLastMissingBbqrIndexAndTheSkipCounters() {
        val line = qrPerfLogLine(
            session = 4,
            event = "stop",
            attempts = 900,
            decoded = 438,
            accepted = 70,
            expected = 71,
            percent = 99,
            elapsedMs = 150000,
            newParts = 0,
            repeatParts = 0,
            rejectedParts = 0,
            avgAnalyzeMs = 8,
            width = 1280,
            height = 720,
            rowStride = 1504,
            bbqrMissing = intArrayOf(70),
            bbqrHeld = 360,
            bbqrKeep = 2,
            bbqrDecode = 5,
            bbqrIndex = 1,
            bbqrClosed = 0,
            bbqrOther = 0,
            bbqrDecodeIdx = intArrayOf(70)
        )
        assertEquals(
            "session=4 event=stop attempts=900 decoded=438 accepted=70 " +
                "expected=71 pct=99 elapsedMs=150000 newParts=0 repeatParts=0 " +
                "rejectedParts=0 avgAnalyzeMs=8 width=1280 height=720 rowStride=1504 " +
                "bbqrMiss=1 bbqrMissIdx=70 bbqrHeld=360 bbqrKeep=2 bbqrDecode=5 " +
                "bbqrIndex=1 bbqrClosed=0 bbqrOther=0 bbqrDecodeIdx=70",
            line
        )
        assertEquals(
            "session=4 event=bbqrSkip reason=decode index=70",
            qrPerfBbqrSkipLine(4, BbqrSkipReason.Decode, 70)
        )
        assertEquals(
            "session=4 event=bbqrSkip reason=index index=80",
            qrPerfBbqrSkipLine(4, BbqrSkipReason.Index, 80)
        )
    }
}
