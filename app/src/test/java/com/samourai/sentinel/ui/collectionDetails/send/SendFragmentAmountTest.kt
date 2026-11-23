package com.samourai.sentinel.ui.collectionDetails.send

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.text.InputFilter
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.db.dao.UtxoDao
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.data.repository.FeeRepository
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.wallet.segwit.SegwitAddress
import com.samourai.wallet.util.XPUB
import org.bitcoinj.core.ECKey
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.HDKeyDerivation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * UI-level tests on the real [SendFragment] + `fragment_spend.xml` for:
 * - XML `maxLength` and fast rejection of a 1M-digit paste without hanging;
 * - clearing stale amounts after an unparsable or zero edit (4a);
 * - BigDecimal fiat <-> sats conversion without Float precision loss (4b);
 * - showing the no-Dojo-paired state without Retry when [SpendBlocker.NO_DOJO] fires.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SendFragmentAmountTest {

    private lateinit var prefs: PrefsUtil
    private lateinit var destinationAddress: String
    private lateinit var zpub: String
    private var controller: ActivityController<SendHostActivity>? = null

    @Before
    fun setUp() {
        GlobalContext.getOrNull()?.let { stopKoin() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        prefs = PrefsUtil(app)
        prefs.clearAll()
        prefs.testnet = false
        prefs.fiatDisabled = false
        prefs.selectedCurrency = "USD"
        prefs.exchangeRate = 99_900L

        val access = AccessFactory.getInstance(app)
        access.admitSession()
        access.updatePin()

        val seed = ByteArray(32) { (it + 11).toByte() }
        val master = HDKeyDerivation.createMasterPrivateKey(seed)
        val accountKey = HDKeyDerivation.deriveChildKey(
            HDKeyDerivation.deriveChildKey(
                HDKeyDerivation.deriveChildKey(master, ChildNumber(84, true)),
                ChildNumber(0, true),
            ),
            ChildNumber(0, true),
        )
        zpub = XPUB.makeXPUB(
            XPUB.MAGIC_ZPUB,
            accountKey.depth.toByte(),
            accountKey.parentFingerprint,
            accountKey.childNumber.i(),
            accountKey.chainCode,
            accountKey.pubKey,
        )
        val utxoDao = object : UtxoDao {
            override fun getUTXObyIdx(idx: String): LiveData<List<Utxo>> = MutableLiveData(emptyList())
            override fun getUTXObyCollection(collectionId: String): LiveData<List<Utxo>> = MutableLiveData(emptyList())
            override fun getUTXObyCollectionAsList(collectionId: String): List<Utxo> = emptyList()
            override fun getUTXObyCollectionAndPubKey(collectionId: String, pubKey: String): LiveData<List<Utxo>> = MutableLiveData(emptyList())
            override fun getUTXObyCollectionAndPubKeys(collectionId: String, pubKeys: List<String>): LiveData<List<Utxo>> = MutableLiveData(emptyList())
            override fun getUtxoWithPubKey(pubKey: String): LiveData<List<Utxo>> = MutableLiveData(emptyList())
            override fun getUTXObyPubKeys(pubKeys: List<String>): LiveData<List<Utxo>> = MutableLiveData(emptyList())
            override suspend fun insert(utxo: Utxo) {}
            override suspend fun insertAll(utxos: List<Utxo>) {}
            override suspend fun delete() {}
            override suspend fun delete(txHash: String, outputN: Int) {}
            override suspend fun deleteByPubKey(pubKey: String) {}
            override suspend fun deleteByCollection(collectionId: String) {}
            override suspend fun deleteByCollectionNotIn(collectionId: String, idxs: List<String>) {}
        }

        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                    single { access }
                    single { CollectionRepository() }
                    single { ApiService() }
                    single { SentinelCollectionStore() }
                    single { DojoUtility() }
                    single { ExchangeRateRepository() }
                    single { FeeRepository() }
                    single<UtxoDao> { utxoDao }
                }
            )
        }

        destinationAddress = SegwitAddress(
            ECKey.fromPrivate(ByteArray(32) { 0x33 }).pubKey,
            SentinelState.getNetworkParam(),
        ).bech32AsString
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        GlobalContext.getOrNull()?.let { stopKoin() }
    }

    private fun launchFragment(): SendFragment {
        val pub = PubKeyModel(
            pubKey = zpub,
            label = "Deposit",
            type = AddressTypes.BIP84,
            change_index = 0,
            fingerPrint = "0badf00d",
            balance = 50_000_000L,
        )
        val collection = PubKeyCollection(
            collectionLabel = "Test",
            pubs = arrayListOf(pub),
            balance = 50_000_000L,
        )
        val c = Robolectric.buildActivity(SendHostActivity::class.java)
        controller = c
        c.get().setTheme(R.style.AppThemeV3)
        val activity = c.setup().get()
        val fragment = SendFragment().apply { mCollection = collection }
        activity.supportFragmentManager.beginTransaction()
            .replace(SendHostActivity.CONTAINER_ID, fragment, "send")
            .commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        return fragment
    }

    private fun enteredSats(vm: SendViewModel): Long =
        SendViewModel::class.java
            .getDeclaredField("enteredAmountSats")
            .apply { isAccessible = true }
            .getLong(vm)

    @Test
    fun amountFieldsEnforceMaxLengthAndRejectOneMillionDigitPasteWithoutBlocking() {
        val fragment = launchFragment()
        val root = fragment.requireView()
        val btcEdit = root.findViewById<TextInputEditText>(R.id.btcEditText)
        val fiatEdit = root.findViewById<TextInputEditText>(R.id.fiatEditText)

        val btcFilter = btcEdit.filters.filterIsInstance<InputFilter.LengthFilter>().singleOrNull()
        val fiatFilter = fiatEdit.filters.filterIsInstance<InputFilter.LengthFilter>().singleOrNull()
        assertEquals(SatsAmount.MAX_INPUT_LENGTH, btcFilter?.max)
        assertEquals(SatsAmount.MAX_INPUT_LENGTH, fiatFilter?.max)

        btcEdit.setText("0.29")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(29_000_000L, enteredSats(fragment.viewModel))

        val huge = "9".repeat(1_000_000)
        val startNs = System.nanoTime()
        btcEdit.setText(huge)
        shadowOf(Looper.getMainLooper()).idle()
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertTrue("1M-digit paste took ${elapsedMs}ms", elapsedMs < 500L)
        assertEquals("", btcEdit.text.toString())
        assertEquals("", fiatEdit.text.toString())
        assertEquals(0L, enteredSats(fragment.viewModel))
    }

    @Test
    fun unparsableOrZeroBtcEditClearsStaleEnteredAmount() {
        val fragment = launchFragment()
        val root = fragment.requireView()
        val btcEdit = root.findViewById<TextInputEditText>(R.id.btcEditText)
        val fiatEdit = root.findViewById<TextInputEditText>(R.id.fiatEditText)
        fragment.viewModel.setDestinationAddress(destinationAddress)

        btcEdit.setText("0.5")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(50_000_000L, enteredSats(fragment.viewModel))
        assertTrue(fiatEdit.text.toString().isNotEmpty())

        for (bad in listOf(".", "1.2.3", "abc", "0", "22000000")) {
            btcEdit.setText("0.5")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(50_000_000L, enteredSats(fragment.viewModel))

            btcEdit.setText(bad)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("stale amount kept for '$bad'", 0L, enteredSats(fragment.viewModel))
            assertEquals("stale fiat kept for '$bad'", "", fiatEdit.text.toString())
        }
    }

    @Test
    fun fiatInputUsesBigDecimalWithoutFloatPrecisionLossAndClearsStaleBtcOnInvalidEdit() {
        val fragment = launchFragment()
        val root = fragment.requireView()
        val btcEdit = root.findViewById<TextInputEditText>(R.id.btcEditText)
        val fiatEdit = root.findViewById<TextInputEditText>(R.id.fiatEditText)

        // Rate is 99,900 USD/BTC: $999 is exact 0.01 BTC = 1,000,000 sats (Float gave 999,999).
        fiatEdit.setText("999")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1_000_000L, enteredSats(fragment.viewModel))
        assertEquals("0.01", btcEdit.text.toString())

        // Unparsable fiat edit clears stale BTC amount without erasing "." as the user types.
        fiatEdit.setText(".")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(".", fiatEdit.text.toString())
        assertEquals("", btcEdit.text.toString())
        assertEquals(0L, enteredSats(fragment.viewModel))
    }

    @Test
    fun noDojoStatusRowShowsMessageWithoutRetry() {
        val fragment = launchFragment()
        val root = fragment.requireView()
        val statusRow = root.findViewById<View>(R.id.composeStatusRow)
        val statusText = root.findViewById<TextView>(R.id.composeStatusText)
        val retryBtn = root.findViewById<MaterialButton>(R.id.composeRetryBtn)

        val showComposeStatus = SendFragment::class.java
            .getDeclaredMethod("showComposeStatus", SendViewModel.SpendBlocker::class.java)
            .apply { isAccessible = true }

        showComposeStatus.invoke(fragment, SendViewModel.SpendBlocker.NO_DOJO)
        assertEquals(View.VISIBLE, statusRow.visibility)
        assertEquals("No Dojo paired. Pair a Dojo in Settings", statusText.text.toString())
        assertEquals(View.GONE, retryBtn.visibility)

        showComposeStatus.invoke(fragment, SendViewModel.SpendBlocker.DOJO_UNREACHABLE)
        assertEquals(View.VISIBLE, statusRow.visibility)
        assertEquals("Could not reach Dojo", statusText.text.toString())
        assertEquals(View.VISIBLE, retryBtn.visibility)
    }
}

class SendHostActivity : SentinelActivity() {
    companion object {
        const val CONTAINER_ID = 0x5EED0001
    }

    override fun onCreateAdmitted(savedInstanceState: Bundle?) {
        setContentView(FrameLayout(this).apply { id = CONTAINER_ID })
    }
}
