package com.samourai.sentinel.ui.collectionDetails.send

import java.math.BigDecimal

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

    private val PLAIN_DECIMAL = Regex("^[0-9]*(\\.[0-9]*)?$")

    /**
     * [text] in BTC as sats. Null when it is not a plain non-negative
     * decimal (digits and at most one '.'), has a non-zero digit past the
     * eighth decimal place, or is more than [MAX_SATS].
     */
    fun parseBtc(text: String): Long? {
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

    /** [sats] as BTC text with eight decimal places, always with a '.'. */
    fun formatBtc(sats: Long): String = BigDecimal.valueOf(sats, 8).toPlainString()

    /** [sats] as BTC, for display formatters that take a number. Exact. */
    fun toBtc(sats: Long): BigDecimal = BigDecimal.valueOf(sats, 8)
}
