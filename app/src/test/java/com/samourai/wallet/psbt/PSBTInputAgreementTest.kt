package com.samourai.wallet.psbt

import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.params.MainNetParams
import org.bouncycastle.util.encoders.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #477: the vendored PSBT's input arms keep their utxo fields in
 * agreement - the amount parameter must match the referenced output of
 * the prev tx, and the SegWit arms write both fields from that one tx.
 */
class PSBTInputAgreementTest {

    private val params = MainNetParams.get()
    private val fingerprint = byteArrayOf(0, 0, 0, 0)

    private fun prevTx(value: Long): Transaction {
        val tx = Transaction(params)
        // One dummy OP_TRUE input pins the unambiguous legacy serialization
        // shape: a no-input tx serializes 0x00 0x01 at bytes 4-5, which a
        // segwit parser reads as BIP144 marker+flag and mis-parses
        // (compose fixture CI run 42434828377).
        tx.addInput(TransactionInput(params, tx, byteArrayOf(0x51)))
        tx.addOutput(TransactionOutput(params, tx, Coin.valueOf(value), byteArrayOf(0x51)))
        return tx
    }

    private fun prevHex(tx: Transaction): String = Hex.toHexString(tx.bitcoinSerialize())

    private fun keyTypes(psbt: PSBT): List<Byte> =
        psbt.psbtInputs.mapNotNull { it.keyType?.firstOrNull() }

    private fun entry(psbt: PSBT, type: Byte) =
        psbt.psbtInputs.first { it.keyType?.firstOrNull() == type }

    @Test
    fun `addInput writes both utxo fields from the verified prev tx`() {
        val prev = prevTx(100_000L)
        val hex = prevHex(prev)
        val psbt = PSBT(Transaction(params))
        psbt.addInput(params, fingerprint, ECKey(), 100_000L, 84, 0, 0, 0, 0, hex, 0)
        val types = keyTypes(psbt)
        assertEquals(3, types.size)
        assertTrue(types.contains(PSBT.PSBT_IN_NON_WITNESS_UTXO))
        assertTrue(types.contains(PSBT.PSBT_IN_WITNESS_UTXO))
        assertTrue(types.contains(PSBT.PSBT_IN_BIP32_DERIVATION))
        assertArrayEquals(
            prev.bitcoinSerialize(),
            entry(psbt, PSBT.PSBT_IN_NON_WITNESS_UTXO).data
        )
        assertArrayEquals(
            prev.getOutput(0L).bitcoinSerialize(),
            entry(psbt, PSBT.PSBT_IN_WITNESS_UTXO).data
        )
    }

    @Test
    fun `addInput refuses a disagreeing amount or an out-of-range index`() {
        val hex = prevHex(prevTx(100_000L))
        val psbt = PSBT(Transaction(params))
        try {
            psbt.addInput(params, fingerprint, ECKey(), 99_999L, 84, 0, 0, 0, 0, hex, 0)
            fail("expected the amount disagreement to be refused")
        } catch (expected: Exception) {
        }
        try {
            psbt.addInput(params, fingerprint, ECKey(), 100_000L, 84, 0, 0, 0, 0, hex, 5)
            fail("expected the out-of-range index to be refused")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun `addInputLegacy writes the prev tx and checks the amount`() {
        val prev = prevTx(50_000L)
        val hex = prevHex(prev)
        val psbt = PSBT(Transaction(params))
        psbt.addInputLegacy(params, fingerprint, ECKey(), 50_000L, 44, 0, 0, 0, 0, hex, 0)
        val types = keyTypes(psbt)
        assertTrue(types.contains(PSBT.PSBT_IN_NON_WITNESS_UTXO))
        assertTrue(types.contains(PSBT.PSBT_IN_SIGHASH_TYPE))
        assertTrue(types.contains(PSBT.PSBT_IN_BIP32_DERIVATION))
        assertArrayEquals(
            prev.bitcoinSerialize(),
            entry(psbt, PSBT.PSBT_IN_NON_WITNESS_UTXO).data
        )
        try {
            psbt.addInputLegacy(params, fingerprint, ECKey(), 50_001L, 44, 0, 0, 0, 0, hex, 0)
            fail("expected the amount disagreement to be refused")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun `addInputCompatibility keeps both utxo fields in agreement`() {
        val hex = prevHex(prevTx(70_000L))
        val psbt = PSBT(Transaction(params))
        psbt.addInputCompatibility(params, fingerprint, ECKey(), 70_000L, 49, 0, 0, 0, 0, hex, 0)
        val types = keyTypes(psbt)
        assertTrue(types.contains(PSBT.PSBT_IN_NON_WITNESS_UTXO))
        assertTrue(types.contains(PSBT.PSBT_IN_WITNESS_UTXO))
        assertTrue(types.contains(PSBT.PSBT_IN_SIGHASH_TYPE))
        assertTrue(types.contains(PSBT.PSBT_IN_REDEEM_SCRIPT))
        assertTrue(types.contains(PSBT.PSBT_IN_BIP32_DERIVATION))
        try {
            psbt.addInputCompatibility(params, fingerprint, ECKey(), 70_001L, 49, 0, 0, 0, 0, hex, 0)
            fail("expected the amount disagreement to be refused")
        } catch (expected: Exception) {
        }
    }
}
