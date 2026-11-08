package com.samourai.sentinel.ui.collectionDetails.send

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.hd.HD_Account
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.send.FeeUtil
import com.samourai.sentinel.send.SuggestedFee
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.wallet.psbt.PSBT
import com.samourai.wallet.segwit.SegwitAddress
import com.samourai.wallet.segwit.bech32.Bech32UtilGeneric
import com.samourai.wallet.util.XPUB
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.HDKeyDerivation
import org.bouncycastle.util.encoders.Hex
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
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
import java.net.InetAddress

/**
 * #469: compose() must fetch the prev transaction for every input and
 * check it against what Dojo reported before any input map is built. A
 * SegWit signature commits to PSBT_IN_WITNESS_UTXO, so a wallet that signs
 * the reported amount signs whatever the server said.
 *
 * The fake Dojo routes by txid (see installDispatcher), so fetch order
 * does not matter; lie tests override the next response via serveOnce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ComposeInputVerificationTest {

    private lateinit var wallet: Wallet

    @Before
    fun setUp() {
        ensureRuntime(serverUrl())
        val suggested = SuggestedFee()
        suggested.defaultPerKB = BigInteger.valueOf(1000L)
        FeeUtil.getInstance().suggestedFee = suggested
        wallet = Wallet(SentinelState.getNetworkParam()!!)
    }

    @Test
    fun honestPrevTxPutsTheChainValueInTheWitnessUtxo() {
        val funded = wallet.funded(value = 10_000_000L, index = 0)
        val psbt = composeOk(funded)
        val witnessUtxo = inputEntry(psbt, PSBT.PSBT_IN_WITNESS_UTXO.toInt() and 0xff)
        assertEquals(10_000_000L, serializedOutputValue(witnessUtxo))
    }

    @Test
    fun aDojoValueLieIsRefused() {
        val funded = wallet.funded(value = 10_000_000L, index = 0)
        val lie = funded.utxo.copy(value = 10_001_000L)
        val message = composeFail(lie)
        assertTrue("expected value mismatch, got: $message", message.contains("value mismatch"))
    }

    @Test
    fun aDifferentPrevTxIsRefused() {
        val funded = wallet.funded(value = 10_000_000L, index = 0)
        val other = wallet.funded(value = 9_000_000L, index = 0)
        serveOnce(MockResponse().setBody("""{"status":"ok","data":"${other.hex}"}"""))
        val message = composeFail(funded.utxo)
        assertTrue("expected hash mismatch, got: $message", message.contains("hash mismatch"))
    }

    @Test
    fun aFailedFetchFailsClosed() {
        val funded = wallet.funded(value = 10_000_000L, index = 0)
        serveOnce(MockResponse().setResponseCode(500).setBody("boom"))
        val message = composeFail(funded.utxo)
        assertTrue("expected fetch failure, got: $message", message.contains("fetch failed"))
    }

    @Test
    fun aBodyWithoutDataFailsClosed() {
        val funded = wallet.funded(value = 10_000_000L, index = 0)
        serveOnce(MockResponse().setBody("""{"status":"ok"}"""))
        val message = composeFail(funded.utxo)
        assertTrue("expected missing data, got: $message", message.contains("No prev tx data"))
    }

    @Test
    fun anOutOfRangeOutputIndexIsRefused() {
        val funded = wallet.funded(value = 10_000_000L, index = 0)
        val lie = funded.utxo.copy(txOutputN = 3)
        val message = composeFail(lie)
        assertTrue("expected bounds failure, got: $message", message.contains("out of range"))
    }

    private fun composeOk(vararg funded: FundedUtxo): ByteArray {
        val composer = TransactionComposer()
        composer.setPubKey(listOf(wallet.pub))
        return runBlocking {
            withTimeout(20_000) {
                val fee = async { composer.getMinerFee().receive() }
                val ok = composer.compose(
                    inputUtxos = ArrayList(funded.map { it.utxo }),
                    inputAddress = wallet.externalAddress,
                    inputFee = 1000L,
                    inputAmount = 2_000_000.0
                )
                assertTrue(ok)
                fee.await()
                composer.getPSBT()?.toBytes() ?: error("composer returned no PSBT bytes")
            }
        }
    }

    private fun composeFail(vararg utxos: Utxo): String {
        val composer = TransactionComposer()
        composer.setPubKey(listOf(wallet.pub))
        return runBlocking {
            withTimeout(20_000) {
                val fee = async { composer.getMinerFee().receive() }
                // Kotlin quirk: a try-catch-finally used as an expression
                // folds the finally block's type into the result type, so
                // this would type as Any, not String. Capture the caught
                // message and produce the value after the finally runs.
                var caught: String? = null
                try {
                    composer.compose(
                        inputUtxos = ArrayList(utxos.toList()),
                        inputAddress = wallet.externalAddress,
                        inputFee = 1000L,
                        inputAmount = 2_000_000.0
                    )
                    fail("compose must fail closed on an unverified input")
                } catch (e: TransactionComposer.ComposeException) {
                    caught = e.message ?: ""
                } finally {
                    fee.cancel()
                }
                caught ?: error("unreachable: compose neither threw nor returned")
            }
        }
    }

    /** First input-map entry with [keyType], walking the PSBT like a signer. */
    private fun inputEntry(psbt: ByteArray, keyType: Int): ByteArray {
        val cursor = Cursor(psbt)
        cursor.i = 4
        assertEquals(0xff, cursor.u8())
        val global = cursor.readMap()
        val rawTx = global.first { it.keyType == 0 }.value
        val tx = Transaction(SentinelState.getNetworkParam(), rawTx)
        repeat(tx.inputs.size) {
            val hit = cursor.readMap().firstOrNull { entry -> entry.keyType == keyType }
            if (hit != null) return hit.value
        }
        error("no input entry with key type $keyType")
    }

    /** The value field of a serialized TransactionOutput: 8 bytes, LE. */
    private fun serializedOutputValue(bytes: ByteArray): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (bytes[i].toLong() and 0xff)
        return v
    }

    private class Wallet(val params: NetworkParameters) {
        val zpub: String
        val externalAddress: String
        val pub: PubKeyModel

        init {
            val seed = ByteArray(32) { (it + 13).toByte() }
            val master = HDKeyDerivation.createMasterPrivateKey(seed)
            val accountKey = HDKeyDerivation.deriveChildKey(
                HDKeyDerivation.deriveChildKey(
                    HDKeyDerivation.deriveChildKey(master, ChildNumber(84, true)),
                    ChildNumber(0, true)
                ),
                ChildNumber(0, true)
            )
            zpub = XPUB.makeXPUB(
                XPUB.MAGIC_ZPUB,
                accountKey.depth.toByte(),
                accountKey.parentFingerprint,
                accountKey.childNumber.i(),
                accountKey.chainCode,
                accountKey.pubKey
            )
            externalAddress = SegwitAddress(
                ECKey.fromPrivate(ByteArray(32) { 0x13 }).pubKey,
                params
            ).bech32AsString
            pub = PubKeyModel(
                pubKey = zpub,
                label = "Deposit",
                type = AddressTypes.BIP84,
                change_index = 2,
                fingerPrint = "cafed00d"
            )
        }

        fun funded(value: Long, index: Int): FundedUtxo {
            val account = HD_Account(params, zpub, "", 0)
            val key = account.getChain(0).getAddressAt(index).ecKey
            val address = SegwitAddress(key.pubKey, params).bech32AsString
            val script = Bech32UtilGeneric.getInstance().computeScriptPubKey(address, params)
            // The fixture txid must be the real id of a real funding tx
            // paying this exact output, or compose() cannot verify it.
            val funding = Transaction(params)
            // No inputs + one output serializes 0x00 0x01 at bytes 4-5, which a
            // segwit parser reads as BIP144 marker+flag and mis-parses (CI run
            // 42434828377). One dummy OP_TRUE input pins unambiguous legacy shape.
            funding.addInput(TransactionInput(params, funding, byteArrayOf(0x51)))
            funding.addOutput(TransactionOutput(params, funding, Coin.valueOf(value), script))
            val hex = Hex.toHexString(funding.bitcoinSerialize())
            val txid = funding.hash.toString()
            served[txid] = hex
            return FundedUtxo(
                Utxo(
                    idx = txid,
                    addr = address,
                    confirmations = 1,
                    script = Hex.toHexString(script),
                    txHash = txid,
                    txOutputN = 0,
                    value = value,
                    path = "m/0/$index",
                    pubKey = zpub
                ),
                hex
            )
        }
    }

    /** #469: the Utxo plus the funding-tx hex the fake Dojo serves. */
    private class FundedUtxo(val utxo: Utxo, val hex: String)

    private class Kv(val keyType: Int, val keyData: ByteArray, val value: ByteArray)

    private class Cursor(val bytes: ByteArray) {
        var i: Int = 0

        fun u8(): Int {
            if (i >= bytes.size) error("truncated PSBT at $i")
            return bytes[i++].toInt() and 0xff
        }

        fun readCompact(): Int {
            val first = u8()
            return when (first) {
                0xfd -> u8() or (u8() shl 8)
                0xfe -> u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)
                0xff -> error("compact size too large")
                else -> first
            }
        }

        fun readBytes(n: Int): ByteArray {
            if (n < 0 || i + n > bytes.size) error("truncated PSBT reading $n at $i")
            val out = bytes.copyOfRange(i, i + n)
            i += n
            return out
        }

        fun readMap(): List<Kv> {
            val entries = mutableListOf<Kv>()
            while (true) {
                val keyLen = readCompact()
                if (keyLen == 0) break
                val key = readBytes(keyLen)
                val value = readBytes(readCompact())
                entries.add(Kv(key[0].toInt() and 0xff, key.copyOfRange(1, key.size), value))
            }
            return entries
        }
    }

    companion object {
        @Volatile
        private var startedKoin = false

        private lateinit var server: MockWebServer

        private val served = HashMap<String, String>()

        private var oneShot: MockResponse? = null

        private fun serverUrl(): String = server.url("/").toString()

        private fun serveOnce(response: MockResponse) {
            oneShot = response
        }

        private fun installDispatcher() {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    oneShot?.let {
                        oneShot = null
                        return it
                    }
                    val txid = request.path!!.substringAfter("/tx/").removeSuffix("/HEX")
                    val hex = served[txid]
                        ?: return MockResponse().setResponseCode(404)
                            .setBody("""{"status":"error"}""")
                    return MockResponse()
                        .setBody("""{"status":"ok","data":"$hex"}""")
                }
            }
        }

        private fun ensureRuntime(baseUrl: String) {
            // #469 CI (run 42426252881): another Robolectric class can leave a
            // Koin graph running in this fork without an ApiService bean; the old
            // start-only-when-null guard silently skipped registration and every
            // compose test failed closed on NoBeanDefFound. Stop whatever graph is
            // running, then always start ours.
            GlobalContext.getOrNull()?.let { stopKoin() }
            val app = ApplicationProvider.getApplicationContext<Application>()
            val prefs = PrefsUtil(app)
            prefs.testnet = false
            prefs.apiEndPoint = baseUrl
            startKoin {
                androidContext(app)
                modules(
                    module {
                        single { prefs }
                        single { CollectionRepository() }
                        // #469: compose() fetches the prev tx for
                        // every input; this single serves it.
                        single { ApiService() }
                        // #469 CI (run 42429551157): getAPIUrl() opens with
                        // SentinelState.isTorRequired(), whose DojoUtility
                        // lazy resolves on first use; the fixture module
                        // never had one, so every prev-tx fetch died as
                        // NoBeanDefFound. An empty Dojo store keeps
                        // isDojoEnabled() false, so requests stay on the
                        // plain apiEndPoint (the fake Dojo) with Tor off.
                        single { SentinelCollectionStore() }
                        single { DojoUtility() }
                    }
                )
            }
            startedKoin = true
            check(!SentinelState.isTestNet()) { "fixture is a mainnet zpub" }
            shadowOf(Looper.getMainLooper()).idle()
        }

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            server = MockWebServer()
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            installDispatcher()
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            if (startedKoin) {
                stopKoin()
                startedKoin = false
            }
            server.shutdown()
            served.clear()
            oneShot = null
        }
    }
}
