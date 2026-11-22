package com.samourai.sentinel.ui.collectionDetails.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #612: the Send amount is parsed to whole sats from the BTC text, with
 * no Double in between.
 */
class SatsAmountTest {

    @Test
    fun typedAmountsParseToExactSats() {
        assertEquals(29_000_000L, SatsAmount.parseBtc("0.29"))
        assertEquals(57_000_000L, SatsAmount.parseBtc("0.57"))
        assertEquals(58_000_000L, SatsAmount.parseBtc("0.58"))
        assertEquals(19_506_493L, SatsAmount.parseBtc("0.19506493"))
        assertEquals(8_000_028L, SatsAmount.parseBtc("0.08000028"))
        assertEquals(100_000_000L, SatsAmount.parseBtc("1"))
        assertEquals(50_000_000L, SatsAmount.parseBtc(".5"))
        assertEquals(500_000_000L, SatsAmount.parseBtc("5."))
        assertEquals(1L, SatsAmount.parseBtc("0.00000001"))
        assertEquals(0L, SatsAmount.parseBtc("0"))
        assertEquals(29_000_000L, SatsAmount.parseBtc("0.290000000"))
        assertEquals(SatsAmount.MAX_SATS, SatsAmount.parseBtc("21000000"))
    }

    @Test
    fun anythingThatIsNotAPlainBtcAmountIsRefused() {
        assertNull(SatsAmount.parseBtc(""))
        assertNull(SatsAmount.parseBtc("."))
        assertNull(SatsAmount.parseBtc("abc"))
        assertNull(SatsAmount.parseBtc("-1"))
        assertNull(SatsAmount.parseBtc("1e3"))
        assertNull(SatsAmount.parseBtc("0,29"))
        assertNull(SatsAmount.parseBtc("1.2.3"))
        assertNull(SatsAmount.parseBtc("0.000000001"))
        assertNull(SatsAmount.parseBtc("21000000.00000001"))
        assertNull(SatsAmount.parseBtc("99999999999999999999"))
    }

    @Test
    fun formattedFiguresParseBackToTheSameSats() {
        assertEquals("0.29000000", SatsAmount.formatBtc(29_000_000L))
        assertEquals("0.00000001", SatsAmount.formatBtc(1L))
        assertEquals("0.00000000", SatsAmount.formatBtc(0L))
        assertEquals("21000000.00000000", SatsAmount.formatBtc(SatsAmount.MAX_SATS))
        val samples = (0L..20_000L) + listOf(
            29_000_000L, 57_000_000L, 58_000_000L, 8_000_028L, 19_506_493L,
            123_456_789L, SatsAmount.MAX_SATS - 1, SatsAmount.MAX_SATS
        )
        for (sats in samples) {
            assertEquals(sats, SatsAmount.parseBtc(SatsAmount.formatBtc(sats)))
        }
    }

    @Test
    fun theDoubleRoundTripTheScreenUsedToMakeLosesASat() {
        // What the screen did before: sats / 1e8 into setAmount, * 1e8 in
        // the ViewModel, toLong() in the composer.
        for (sats in listOf(29_000_000L, 57_000_000L, 58_000_000L, 8_000_028L)) {
            assertNotEquals(sats, ((sats / 1e8) * 1e8).toLong())
            assertEquals(sats, SatsAmount.parseBtc(SatsAmount.formatBtc(sats)))
        }
        assertNotEquals(29_000_000L, ("0.29".toDouble() * 1e8).toLong())
    }
}
