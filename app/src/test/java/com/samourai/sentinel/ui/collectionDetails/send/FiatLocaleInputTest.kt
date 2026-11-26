package com.samourai.sentinel.ui.collectionDetails.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/**
 * #622 review: the fiat field is shown in the user's locale, so it has to be
 * read back in that locale. In de-DE and fr-FR a typed or edited amount used
 * to parse as nothing because only a '.' decimal was understood.
 */
class FiatLocaleInputTest {

    private val de = Locale.GERMANY
    private val fr = Locale.FRANCE
    private val us = Locale.US
    private val ch = Locale.forLanguageTag("de-CH")
    private val inIndia = Locale.forLanguageTag("en-IN")

    private fun symbols(locale: Locale) = DecimalFormatSymbols.getInstance(locale)
    private fun format(locale: Locale) = NumberFormat.getNumberInstance(locale)
    private fun norm(locale: Locale, text: String) = SatsAmount.normalizeFiat(text, symbols(locale))

    /** What the field does on each key press: read the text, show it formatted. */
    private fun type(locale: Locale, keys: String): String {
        var field = ""
        for (key in keys) {
            val plain = norm(locale, field + key)
            assertNotNull("'$key' after '$field' was refused", plain)
            field = SatsAmount.formatFiatInput(plain!!, format(locale))
        }
        return field
    }

    @Test
    fun commaDecimalLocalesReadTheirOwnDecimalComma() {
        assertEquals("10.50", norm(de, "10,50"))
        assertEquals("1234.56", norm(de, "1.234,56"))
        assertEquals("1234567.89", norm(de, "1.234.567,89"))
        assertEquals("10.50", norm(fr, "10,50"))
        assertEquals("0.5", norm(de, "0,5"))
        assertEquals(".5", norm(de, ",5"))
        assertEquals("10.", norm(de, "10,"))
        assertEquals("10.", norm(fr, "10,"))
    }

    @Test
    fun deDeFiatTextConvertsToSats() {
        assertEquals(10_500L, SatsAmount.fiatToSats(norm(de, "10,50")!!, 100_000L))
        assertEquals(1_000_000L, SatsAmount.fiatToSats(norm(de, "999,00")!!, 99_900L))
        assertEquals(1_234_560L, SatsAmount.fiatToSats(norm(de, "1.234,56")!!, 100_000L))
    }

    @Test
    fun frFrGroupingSpacesAreIgnored() {
        val narrow = symbols(fr).groupingSeparator
        assertEquals("1234.56", norm(fr, "1${narrow}234,56"))
        assertEquals("1234567.89", norm(fr, "1${narrow}234${narrow}567,89"))
        // Older JDK and device data use a no-break space; a keyboard types a plain one.
        assertEquals("1234.56", norm(fr, "1\u00A0234,56"))
        assertEquals("1234.56", norm(fr, "1\u202F234,56"))
        assertEquals("1234.56", norm(fr, "1 234,56"))
        assertEquals("1234.56", norm(fr, "1\u2009234,56"))
    }

    @Test
    fun enUsKeepsWorkingWithDotDecimalAndCommaGrouping() {
        assertEquals("10.50", norm(us, "10.50"))
        assertEquals("1234.56", norm(us, "1,234.56"))
        assertEquals("1234567.89", norm(us, "1,234,567.89"))
        assertEquals("10.", norm(us, "10."))
        assertEquals(".5", norm(us, ".5"))
        assertEquals("1234", norm(us, "1,234"))
    }

    @Test
    fun otherGroupingStylesAreIgnored() {
        assertEquals("1234567.89", norm(ch, "1\u2019234\u2019567.89"))
        assertEquals("1234567.89", norm(inIndia, "12,34,567.89"))
        assertEquals("1234.5", norm(ch, "1\u2019234.5"))
    }

    @Test
    fun aTypedDotIsADecimalWhenTheLocaleDoesNotGroupWithDots() {
        assertEquals("10.50", norm(fr, "10.50"))
        assertEquals("1234.56", norm(fr, "1 234.56"))
        assertEquals("10.", norm(fr, "10."))
        assertEquals("10.50", norm(ch, "10.50"))
    }

    @Test
    fun aTypedCommaIsADecimalWhenTheLocaleDoesNotGroupWithCommas() {
        assertEquals("10.50", norm(ch, "10,50"))
        assertEquals("10.50", norm(Locale.forLanguageTag("en-CH"), "10,50"))
    }

    @Test
    fun aTypedDotIsGroupingInALocaleThatGroupsWithDots() {
        // 1.234 is what the app shows for 1234 in de-DE, so it has to read back as 1234.
        assertEquals("1234", norm(de, "1.234"))
        assertEquals("1050", norm(de, "10.50"))
    }

    @Test
    fun aTypedCommaIsGroupingInALocaleThatGroupsWithCommas() {
        assertEquals("1050", norm(us, "10,50"))
    }

    @Test
    fun textThatIsNotAnAmountIsRefused() {
        for (locale in listOf(de, fr, us, ch)) {
            assertNull(norm(locale, "abc"))
            assertNull(norm(locale, "-1"))
            assertNull(norm(locale, "1e3"))
            assertNull(norm(locale, "+5"))
            assertNull(norm(locale, "1%"))
        }
        // Two decimal separators.
        assertNull(norm(de, "1,2,3"))
        assertNull(norm(us, "1.2.3"))
        assertNull(norm(fr, "1,2,3"))
        assertNull(norm(fr, "1,2.3"))
        assertNull(norm(de, "1,234.56"))
        assertNull(norm(us, "1.234,56"))
        // Over the length cap.
        assertNull(norm(de, "1".repeat(SatsAmount.MAX_INPUT_LENGTH + 1)))
        assertNull(norm(fr, "1,".repeat(SatsAmount.MAX_INPUT_LENGTH)))
    }

    @Test
    fun blankTextIsEmptyNotRefused() {
        assertEquals("", norm(de, ""))
        assertEquals("", norm(fr, " "))
        assertEquals("", norm(us, ""))
    }

    @Test
    fun aLoneSeparatorIsNotAnAmount() {
        for (locale in listOf(de, fr, us)) {
            assertNull(SatsAmount.parseFiat(norm(locale, ",")!!))
            assertNull(SatsAmount.parseFiat(norm(locale, ".")!!))
        }
    }

    @Test
    fun fieldTextIsFormattedInTheLocale() {
        assertEquals("1.234,56", SatsAmount.formatFiatInput("1234.56", format(de)))
        assertEquals("1,234.56", SatsAmount.formatFiatInput("1234.56", format(us)))
        assertEquals("1${symbols(fr).groupingSeparator}234,56", SatsAmount.formatFiatInput("1234.56", format(fr)))
        assertEquals("1.234.567", SatsAmount.formatFiatInput("1234567", format(de)))
        assertEquals("0,5", SatsAmount.formatFiatInput(".5", format(de)))
        assertEquals("0.5", SatsAmount.formatFiatInput(".5", format(us)))
    }

    @Test
    fun trailingSeparatorAndZeroSurviveWhileTyping() {
        assertEquals("10,", SatsAmount.formatFiatInput("10.", format(de)))
        assertEquals("10,0", SatsAmount.formatFiatInput("10.0", format(de)))
        assertEquals("10.", SatsAmount.formatFiatInput("10.", format(us)))
        assertEquals("10.0", SatsAmount.formatFiatInput("10.0", format(us)))
        assertEquals("10,00", SatsAmount.formatFiatInput("10.00", format(fr)))
    }

    @Test
    fun typingKeyByKeyReachesTheAmountInEachLocale() {
        assertEquals("1.234,05", type(de, "1234,05"))
        assertEquals("1,234.05", type(us, "1234.05"))
        assertEquals("1${symbols(fr).groupingSeparator}234,05", type(fr, "1234,05"))
        // A dot typed on a French keyboard is read as the decimal and shown as a comma.
        assertEquals("1${symbols(fr).groupingSeparator}234,05", type(fr, "1234.05"))
        assertEquals("10,5", type(de, "10,5"))
        assertEquals("1.234.567,89", type(de, "1234567,89"))
        assertEquals("1,234,567.89", type(us, "1234567.89"))
        assertEquals("1.234", type(de, "1234"))
    }

    @Test
    fun theTypedFigureIsTheAmount() {
        val rate = 100_000L
        for ((locale, keys) in listOf(de to "1234,05", fr to "1234,05", us to "1234.05")) {
            val shown = type(locale, keys)
            val sats = SatsAmount.fiatToSats(norm(locale, shown)!!, rate)
            assertEquals(1_234_050L, sats)
        }
    }

    @Test
    fun editingTheFieldsOwnTextKeepsTheAmount() {
        // A rate that divides evenly, so the sats are exactly the fiat figure.
        val rate = 100_000L
        for (locale in listOf(de, fr, us, ch, inIndia)) {
            for (fiat in listOf("12.50", "1234.50", "999", "0.01", "1234567.89")) {
                val sats = SatsAmount.fiatToSats(fiat, rate)!!
                val shown = format(locale).format(SatsAmount.satsToFiat(sats, rate))
                val plain = norm(locale, shown)!!
                assertEquals(
                    "$fiat in $locale shown as '$shown'",
                    0,
                    BigDecimal(fiat).setScale(3, RoundingMode.HALF_EVEN)
                        .compareTo(BigDecimal(plain).setScale(3, RoundingMode.HALF_EVEN))
                )
                // Showing it again gives the same field text, so an edit does not drift.
                assertEquals(shown, SatsAmount.formatFiatInput(plain, format(locale)))
            }
        }
    }

    @Test
    fun satsToFiatAndBackIsStableInEachLocale() {
        val rate = 100_000L
        val samples = listOf(1_000L, 25_000L, 1_000_000L, 12_345_678L, 100_000_000L, 2_100_000_000L)
        for (locale in listOf(de, fr, us, ch)) {
            val nf = format(locale) as DecimalFormat
            for (sats in samples) {
                val shown = nf.format(SatsAmount.satsToFiat(sats, rate))
                val plain = norm(locale, shown)
                assertNotNull("'$shown' in $locale", plain)
                val back = SatsAmount.fiatToSats(plain!!, rate)
                assertNotNull("'$shown' in $locale", back)
                // The field shows up to 3 decimals and an edit keeps 2, so the
                // round trip is within one cent of the rate, never above the amount.
                val centInSats = SatsAmount.SATS_PER_BTC / rate
                assert(back!! <= sats) { "$sats -> '$shown' -> $back" }
                assert(sats - back <= centInSats + 1) { "$sats -> '$shown' -> $back" }
            }
        }
    }

    @Test
    fun theOldLocaleBlindReadingIsGone() {
        // Before the fix 10,50 in de-DE was not a plain decimal, so the amount became 0.
        assertNull(SatsAmount.parseFiat("10,50"))
        assertNotNull(SatsAmount.parseFiat(norm(de, "10,50")!!))
    }
}
