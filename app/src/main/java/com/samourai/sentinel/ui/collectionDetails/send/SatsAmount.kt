package com.samourai.sentinel.ui.collectionDetails.send

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat

/**
 * #612: the Send amount as whole sats, parsed from and formatted to the
 * BTC text the field shows without going through a Double.
 *
 * The round trip through a Double lost a sat for some values: 0.29 BTC
 * became 28,999,999.999999996 sats and truncated to 28,999,999.
 */
internal object SatsAmount {

    const val SATS_PER_BTC = 100_000_000L

    /** 21 million BTC. Anything above this is not an amount. */
    const val MAX_SATS = 21_000_000L * SATS_PER_BTC

    /**
     * Cap input before regex/BigDecimal work so a huge paste (for example
     * 1M digits) cannot stall the UI thread. 21000000.00000000 is 17 chars.
     */
    const val MAX_INPUT_LENGTH = 32

    private val PLAIN_DECIMAL = Regex("^[0-9]*(\\.[0-9]*)?$")

    private val SPACE_VARIANTS = charArrayOf(' ', '\u00A0', '\u202F', '\u2009')

    /**
     * [text] in BTC as sats. Null when it exceeds [MAX_INPUT_LENGTH], is
     * not a plain non-negative decimal (digits and at most one '.'), has a
     * non-zero digit past the eighth decimal place, or is more than [MAX_SATS].
     */
    fun parseBtc(text: String): Long? {
        if (text.length > MAX_INPUT_LENGTH) return null
        val t = text.trim()
        if (t.isEmpty() || t == "." || !PLAIN_DECIMAL.matches(t)) return null
        val sats = try {
            BigDecimal(t).movePointRight(8).longValueExact()
        } catch (e: ArithmeticException) {
            return null
        } catch (e: NumberFormatException) {
            return null
        }
        if (sats < 0L || sats > MAX_SATS) return null
        return sats
    }

    /**
     * [text] as a plain non-negative decimal fiat amount, or null when
     * unparsable or over [MAX_INPUT_LENGTH].
     */
    fun parseFiat(text: String): BigDecimal? {
        if (text.length > MAX_INPUT_LENGTH) return null
        val t = text.trim()
        if (t.isEmpty() || t == "." || !PLAIN_DECIMAL.matches(t)) return null
        return try {
            BigDecimal(t)
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * #622 review: the fiat field shows the amount in the user's locale
     * (1.234,56 in de-DE, 1 234,56 in fr-FR) but [parseFiat] only reads a
     * plain '.' decimal, so editing the app's own figure, or typing 10,50,
     * parsed as nothing.
     *
     * Turns what the field holds into the plain decimal [parseFiat] reads,
     * using [symbols] (the same locale the field is formatted with):
     *  - the locale's grouping separator is dropped, as are the space
     *    variants (space, no-break space, narrow no-break space, thin space)
     *    some locales and keyboards use for it;
     *  - the locale's decimal separator becomes '.';
     *  - the other of '.' and ',' is taken as a decimal separator too, unless
     *    it is this locale's grouping separator, in which case it is dropped
     *    as grouping (a typed '.' in de-DE is a thousands separator, a typed
     *    ',' in en-US likewise). Grouping after the decimal separator is
     *    refused.
     *
     * Null when the text is over [MAX_INPUT_LENGTH] or is anything but
     * digits and one decimal separator after that. Blank text gives "".
     */
    fun normalizeFiat(text: String, symbols: DecimalFormatSymbols): String? {
        if (text.length > MAX_INPUT_LENGTH) return null
        val decimal = symbols.decimalSeparator
        val grouping = symbols.groupingSeparator
        val otherDecimal = if (decimal == '.') ',' else '.'
        val out = StringBuilder(text.length)
        for (c in text) {
            when {
                c == decimal -> out.append('.')
                // Grouping after the decimal separator is not grouping: 1,234.56
                // typed in de-DE must not be read as 1.23456.
                c == grouping -> if (out.contains('.')) return null
                c in SPACE_VARIANTS -> Unit
                c == otherDecimal -> out.append('.')
                else -> {
                    val digit = Character.digit(c, 10)
                    if (digit < 0) return null
                    out.append(('0' + digit))
                }
            }
        }
        val plain = out.toString()
        if (plain.isEmpty()) return ""
        return if (PLAIN_DECIMAL.matches(plain)) plain else null
    }

    /**
     * The field text for the plain decimal [plain] (as [normalizeFiat]
     * returns it), grouped and separated for the locale of [format]. The
     * fraction is kept as typed, so a trailing separator or a trailing zero
     * (10, or 10,0 on the way to 10,05) is not lost while typing.
     */
    fun formatFiatInput(plain: String, format: NumberFormat): String {
        val dot = plain.indexOf('.')
        val whole = (if (dot < 0) plain else plain.substring(0, dot)).ifEmpty { "0" }
        val grouped = format.format(BigDecimal(whole).setScale(0, RoundingMode.DOWN))
        if (dot < 0) return grouped
        val decimal = (format as? DecimalFormat)?.decimalFormatSymbols?.decimalSeparator
            ?: DecimalFormatSymbols.getInstance().decimalSeparator
        return grouped + decimal + plain.substring(dot + 1)
    }

    /**
     * Converts fiat [text] at whole-currency-per-BTC [ratePerBtc] into whole
     * sats without Float/Double rounding loss. Null when unparsable, when
     * [ratePerBtc] is not positive, or when the BTC value exceeds 21M BTC.
     */
    fun fiatToSats(text: String, ratePerBtc: Long): Long? {
        if (ratePerBtc <= 0L) return null
        val fiat = parseFiat(text) ?: return null
        val maxFiat = BigDecimal.valueOf(21_000_000L).multiply(BigDecimal.valueOf(ratePerBtc))
        if (fiat > maxFiat) return null
        val sats = try {
            fiat.movePointRight(8)
                .divide(BigDecimal.valueOf(ratePerBtc), 0, RoundingMode.DOWN)
                .longValueExact()
        } catch (e: ArithmeticException) {
            return null
        }
        if (sats < 0L || sats > MAX_SATS) return null
        return sats
    }

    /** Exact fiat display amount for [sats] at [ratePerBtc], without Float/Double. */
    fun satsToFiat(sats: Long, ratePerBtc: Long): BigDecimal {
        if (sats <= 0L || ratePerBtc <= 0L) return BigDecimal.ZERO
        return toBtc(sats).multiply(BigDecimal.valueOf(ratePerBtc))
    }

    /** [sats] as BTC text with eight decimal places, always with a '.'. */
    fun formatBtc(sats: Long): String = BigDecimal.valueOf(sats, 8).toPlainString()

    /** [sats] as BTC, for display formatters that take a number. Exact. */
    fun toBtc(sats: Long): BigDecimal = BigDecimal.valueOf(sats, 8)
}
