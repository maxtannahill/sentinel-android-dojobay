package com.samourai.sentinel.ui.collectionDetails.send

import java.math.BigDecimal
import java.math.RoundingMode

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
