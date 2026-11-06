package com.invertedx.hummingbird

/**
 * When the normal (dark-module) decode misses, how often to pay for a
 * second decode of the inverted image.
 *
 * Dark-on-light codes, including Sparrow animated UR, never take the
 * inverted pass: a hit returns before it is considered. Misses — blur,
 * the gap between animation frames — used to invert every frame. Those
 * misses are most of a long scan, so the inverted pass now runs on every
 * [INVERT_EVERY_NTH_MISS] consecutive miss.
 *
 * A light-on-dark code still decodes. The first hit lands within
 * [INVERT_EVERY_NTH_MISS] misses. After that, [lastSuccessWasInverted]
 * sticks and every later miss inverts immediately, so an inverted code
 * that stays in frame is not sampled down. A later dark-on-light hit
 * clears the stick.
 */
internal class InvertDecodePolicy(
    private val invertEveryNthMiss: Int = INVERT_EVERY_NTH_MISS
) {
    private var consecutiveMisses = 0
    private var lastSuccessWasInverted = false

    fun onNormalDecoded() {
        consecutiveMisses = 0
        lastSuccessWasInverted = false
    }

    /** Call after the non-inverted decode found nothing. */
    fun onNormalMiss() {
        consecutiveMisses++
    }

    fun shouldInvert(): Boolean {
        if (lastSuccessWasInverted) return true
        return invertEveryNthMiss > 0 &&
            consecutiveMisses > 0 &&
            consecutiveMisses % invertEveryNthMiss == 0
    }

    fun onInvertedDecoded() {
        consecutiveMisses = 0
        lastSuccessWasInverted = true
    }

    companion object {
        const val INVERT_EVERY_NTH_MISS = 4
    }
}
