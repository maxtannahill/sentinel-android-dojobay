package com.samourai.sentinel.ui.collectionDetails.send

import org.bitcoinj.core.ECKey
import org.bitcoinj.params.MainNetParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #477: pins the per-purpose script forms of the compose path's derived
 * input script - the independent side of the prev-tx agreement check. The
 * hash-bearing bodies are deliberately not re-derived here; the forms
 * (length, opcodes) and the purpose dispatch are what the composer relies
 * on, and hash agreement is enforced against the real prev tx at compose
 * time.
 */
class DerivedInputScriptTest {

    private val params = MainNetParams.get()
    private val key = ECKey()

    @Test
    fun `purpose 84 derives a native P2WPKH script`() {
        val script = derivedInputScript(84, null, key, params)!!
        assertEquals(22, script.size)
        assertEquals(0x00.toByte(), script[0])
        assertEquals(0x14.toByte(), script[1])
    }

    @Test
    fun `purpose 49 derives a P2SH script`() {
        val script = derivedInputScript(49, null, key, params)!!
        assertEquals(23, script.size)
        assertEquals(0xa9.toByte(), script[0])
        assertEquals(0x14.toByte(), script[1])
        assertEquals(0x87.toByte(), script[22])
    }

    @Test
    fun `purposes 49 and 84 derive distinct scripts for the same key`() {
        val segwit = derivedInputScript(84, null, key, params)!!
        val nested = derivedInputScript(49, null, key, params)!!
        assertFalse(segwit.contentEquals(nested))
    }

    @Test
    fun `unknown purpose or missing derivation returns null`() {
        assertNull(derivedInputScript(44, null, key, params))
        assertNull(derivedInputScript(49, null, null, params))
        assertNull(derivedInputScript(84, null, null, params))
        assertNull(derivedInputScript(0, "any", key, params))
        assertNull(derivedInputScript(86, null, key, params))
    }
}
