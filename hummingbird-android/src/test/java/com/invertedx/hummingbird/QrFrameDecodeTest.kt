package com.invertedx.hummingbird

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QrFrameDecodeTest {

    @Test
    fun widerRowStrideIsTheLuminanceDataWidth() {
        val width = 4
        val height = 2
        val rowStride = 8
        // Last row has no padding byte past `width`. That is the usual
        // camera layout: (height - 1) * rowStride + width.
        val bytes = ByteArray((height - 1) * rowStride + width) { it.toByte() }
        val frame = yLuminance(
            yBytes = bytes,
            rowStride = rowStride,
            pixelStride = 1,
            cropLeft = 0,
            cropTop = 0,
            cropWidth = width,
            cropHeight = height
        )
        assertSame(bytes, frame.bytes)
        assertEquals(rowStride, frame.dataWidth)
        assertEquals(height, frame.dataHeight)
        assertEquals(0, frame.left)
        assertEquals(0, frame.top)
        assertEquals(width, frame.width)
        assertEquals(height, frame.height)
        // Row 1 starts at rowStride. dataWidth = width would read the
        // padding at index 4 as the first pixel of row 1.
        assertEquals(rowStride, frame.dataWidth)
        assertEquals(8.toByte(), frame.bytes[(1 * frame.dataWidth) + frame.left])
    }

    @Test
    fun cropOriginIsPassedThroughWhenThePlaneIsStrided() {
        val rowStride = 10
        val bytes = ByteArray(rowStride * 4) { (it + 1).toByte() }
        val frame = yLuminance(
            yBytes = bytes,
            rowStride = rowStride,
            pixelStride = 1,
            cropLeft = 2,
            cropTop = 1,
            cropWidth = 3,
            cropHeight = 2
        )
        assertSame(bytes, frame.bytes)
        assertEquals(rowStride, frame.dataWidth)
        assertEquals(2, frame.left)
        assertEquals(1, frame.top)
        assertEquals(3, frame.width)
        assertEquals(2, frame.height)
        val zxingOffsetOfOrigin = frame.top * frame.dataWidth + frame.left
        assertEquals(12, zxingOffsetOfOrigin)
        assertEquals(bytes[12], frame.bytes[zxingOffsetOfOrigin])
    }

    @Test
    fun pixelStrideGreaterThanOneIsPacked() {
        val bytes = byteArrayOf(1, 9, 2, 9, 3, 9, 0, 0)
        val frame = yLuminance(
            yBytes = bytes,
            rowStride = 8,
            pixelStride = 2,
            cropLeft = 0,
            cropTop = 0,
            cropWidth = 3,
            cropHeight = 1
        )
        assertEquals(3, frame.dataWidth)
        assertEquals(0, frame.left)
        assertEquals(0, frame.top)
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.bytes)
    }

    @Test
    fun shortBufferIsPackedInsteadOfReadingOffTheEnd() {
        val frame = yLuminance(
            yBytes = byteArrayOf(4, 5, 6, 7),
            rowStride = 8,
            pixelStride = 1,
            cropLeft = 0,
            cropTop = 0,
            cropWidth = 4,
            cropHeight = 2
        )
        assertEquals(4, frame.dataWidth)
        assertEquals(8, frame.bytes.size)
        assertEquals(4.toByte(), frame.bytes[0])
        assertEquals(0.toByte(), frame.bytes[4])
    }

    @Test
    fun invertRunsEveryFourthMissUntilAnInvertedCodeHits() {
        val policy = InvertDecodePolicy()
        assertFalse(policy.shouldInvert())
        repeat(3) {
            policy.onNormalMiss()
            assertFalse(policy.shouldInvert())
        }
        policy.onNormalMiss()
        assertTrue(policy.shouldInvert())

        policy.onInvertedDecoded()
        policy.onNormalMiss()
        assertTrue(
            "after an inverted hit, the next miss inverts immediately",
            policy.shouldInvert()
        )

        policy.onNormalDecoded()
        policy.onNormalMiss()
        assertFalse(policy.shouldInvert())
    }

    @Test
    fun aNormalHitDoesNotAskForAnInvert() {
        val policy = InvertDecodePolicy()
        policy.onNormalMiss()
        policy.onNormalMiss()
        policy.onNormalDecoded()
        assertFalse(policy.shouldInvert())
        assertEquals(4, InvertDecodePolicy.INVERT_EVERY_NTH_MISS)
    }
}
