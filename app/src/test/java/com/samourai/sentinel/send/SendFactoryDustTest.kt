package com.samourai.sentinel.send

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.wallet.SamouraiWalletConst
import com.samourai.wallet.segwit.bech32.Bech32UtilGeneric
import com.samourai.wallet.send.MyTransactionOutPoint
import com.samourai.wallet.util.FormatsUtilGeneric
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutput
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.math.BigInteger

/**
 * #463: [SendFactory.makeTransaction] rejects a spendable output below
 * [SamouraiWalletConst.bDust] and still builds a transaction for a payable
 * amount or a BIP47 OP_RETURN.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SendFactoryDustTest {

    @Before
    fun setUp() {
        ensureRuntime()
    }

    @Test
    fun dustSizedNonOpReturnOutputIsRejected() {
        val below = SamouraiWalletConst.bDust.toLong() - 1L
        assertDust(BECH32 to below)
        assertDust(BECH32 to 1L)
        assertDust(LEGACY to below)
    }

    @Test
    fun thresholdAndLargerAmountsCompose() {
        val atThreshold = compose(BECH32 to SamouraiWalletConst.bDust.toLong())
        assertPayment(atThreshold, SamouraiWalletConst.bDust.toLong())

        val larger = compose(LEGACY to 100_000L)
        assertPayment(larger, 100_000L)
        assertEquals(1, larger.inputs.size)
        assertUntouchedLocktime(larger)
    }

    @Test
    fun zeroAmountStaysInvalidRatherThanDust() {
        val error = assertThrows(Exception::class.java) {
            compose(BECH32 to 0L)
        }
        assertEquals("Invalid amount", error.message)
    }

    @Test
    fun bip47OpReturnStillComposes() {
        assertTrue(FormatsUtilGeneric.getInstance().isValidBIP47OpReturn(bip47OpReturn()))

        val alone = compose(bip47OpReturn() to 0L)
        val opReturn = opReturnOutput(alone)
        assertNotNull(opReturn)
        assertEquals(0L, opReturn!!.value.value)
        assertEquals(1, alone.outputs.size)
        assertUntouchedLocktime(alone)

        val both = compose(bip47OpReturn() to 0L, BECH32 to 50_000L)
        assertNotNull(opReturnOutput(both))
        assertPayment(both, 50_000L)
        assertEquals(2, both.outputs.size)
        assertUntouchedLocktime(both)
    }

    @Test
    fun dustAlongsideAValidOutputOrOpReturnIsRejected() {
        assertDust(BECH32 to 50_000L, LEGACY to 1L)
        assertDust(bip47OpReturn() to 0L, BECH32 to SamouraiWalletConst.bDust.toLong() - 1L)
    }

    private fun assertDust(vararg outputs: Pair<String, Long>) {
        val error = assertThrows(Exception::class.java) {
            compose(*outputs)
        }
        assertEquals("Dust amount", error.message)
    }

    private fun compose(vararg outputs: Pair<String, Long>): Transaction {
        val receivers = HashMap<String, BigInteger>()
        for ((address, sats) in outputs) {
            receivers[address] = BigInteger.valueOf(sats)
        }
        return SendFactory.getInstance().makeTransaction(0, listOf(input()), receivers)
    }

    private fun input(): MyTransactionOutPoint {
        val params = SentinelState.getNetworkParam()!!
        val script = Bech32UtilGeneric.getInstance().computeScriptPubKey(BECH32, params)
        return MyTransactionOutPoint(
            params,
            Sha256Hash.wrap(TXID),
            0,
            BigInteger.valueOf(100_000L),
            script,
            BECH32,
            1
        )
    }

    private fun assertPayment(tx: Transaction, sats: Long) {
        val payment = tx.outputs.firstOrNull {
            !it.scriptPubKey.isOpReturn && it.value.value == sats
        }
        assertNotNull("missing $sats sat output", payment)
    }

    private fun opReturnOutput(tx: Transaction): TransactionOutput? =
        tx.outputs.firstOrNull { it.scriptPubKey.isOpReturn }

    private fun assertUntouchedLocktime(tx: Transaction) {
        assertEquals(1L, tx.version)
        assertEquals(0L, tx.lockTime)
    }

    companion object {
        private const val BECH32 = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
        private const val LEGACY = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
        private const val TXID = "598cbf9f11ab9a1a5e788dbd11a7cf970089cec43e04fc073eb91c0a5717fd0e"

        @Volatile
        private var startedKoin = false

        private fun bip47OpReturn(): String {
            val payload = StringBuilder(160)
            payload.append("010002")
            for (i in 3 until 80) {
                payload.append("%02x".format(i))
            }
            return payload.toString()
        }

        private fun ensureRuntime() {
            if (GlobalContext.getOrNull() == null) {
                val app = ApplicationProvider.getApplicationContext<Application>()
                val prefs = PrefsUtil(app)
                prefs.testnet = false
                startKoin {
                    androidContext(app)
                    modules(
                        module {
                            single { prefs }
                            single { CollectionRepository() }
                        }
                    )
                }
                startedKoin = true
            }
            check(!SentinelState.isTestNet()) { "fixture addresses are mainnet" }
            shadowOf(Looper.getMainLooper()).idle()
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            if (startedKoin) {
                stopKoin()
                startedKoin = false
            }
        }
    }
}
