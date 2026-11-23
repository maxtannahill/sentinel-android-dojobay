package com.samourai.sentinel.ui.collectionDetails.send

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.hd.HD_Account
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.PubSyncHealth
import com.samourai.sentinel.data.repository.SyncFailureKind
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.send.FeeUtil
import com.samourai.sentinel.send.SuggestedFee
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import com.samourai.wallet.segwit.SegwitAddress
import com.samourai.wallet.segwit.bech32.Bech32UtilGeneric
import com.samourai.wallet.util.XPUB
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * #612: tapping the available total on Send, against the real
 * [TransactionComposer] and a fake Dojo that serves each funding tx by
 * txid. Fixture and runtime bootstrap follow [ExplicitSelectionComposeTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpendAllComposeTest {

    private lateinit var wallet: Wallet

    @Before
    fun setUp() {
        ensureRuntime(serverUrl())
        val suggested = SuggestedFee()
        suggested.defaultPerKB = BigInteger.valueOf(1000L)
        FeeUtil.getInstance().suggestedFee = suggested
        wallet = Wallet(SentinelState.getNetworkParam()!!)
        mode = Mode.SERVE
        oneShotResponse = null
        hexRequests.set(0)
        authRequests.set(0)
    }

    @Test
    fun theFigureThatComposedBeforeStillComposesAsAMaxSend() {
        // 0.19506493 BTC over two coins: the round trip happens to land on
        // the exact sats for this one, so it composed before as well.
        val coins = listOf(wallet.funded(12_000_000L, 0), wallet.funded(7_506_493L, 1))
        val tx = compose(coins, 19_506_493L)
        assertEquals(2, tx.inputs.size)
        assertEquals(1, tx.outputs.size)
        assertEquals(2, hexRequests.get())
    }

    @Test
    fun totalsTheDoubleRoundTripBrokeComposeAOneOutputMaxSend() {
        // 29,000,000 and 8,000,028 sats came back one sat low through
        // (sats / 1e8) * 1e8, missed the max-send branch and failed with
        // "Change is dust 2". Whole sats reach the composer now.
        val two = listOf(wallet.funded(20_000_000L, 0), wallet.funded(9_000_000L, 1))
        val maxTwo = compose(two, 29_000_000L)
        assertEquals(1, maxTwo.outputs.size)
        assertEquals(29_000_000L - feeFor(maxTwo, two), maxTwo.outputs[0].value.value)

        val eight = (0 until 8).map { wallet.funded(1_000_000L + it, 10 + it) }
        val maxEight = compose(eight, 8_000_028L)
        assertEquals(8, maxEight.inputs.size)
        assertEquals(1, maxEight.outputs.size)
    }

    @Test
    fun typing029BuildsAnOutputOfExactly29MillionSats() {
        val coins = listOf(wallet.funded(50_000_000L, 0))
        val typed = SatsAmount.parseBtc("0.29")!!
        val tx = compose(coins, typed)
        assertEquals(2, tx.outputs.size)
        assertTrue(tx.outputs.any { it.value.value == 29_000_000L })
    }

    @Test
    fun spendAllSpendsACoinTheFigureMissedInsteadOfFailingOnDust() {
        // The figure reads 0.19506493 BTC, but a 700-sat coin is already in
        // the pool. As an amount, that left change under dust and failed.
        val coins = listOf(
            wallet.funded(12_000_000L, 0), wallet.funded(7_506_493L, 1), wallet.funded(700L, 2)
        )
        val failed = composeFailure(coins, 19_506_493L)
        assertTrue(failed is TransactionComposer.ComposeException)
        val composer = composer()
        val tx = compose(coins, 19_506_493L, spendAll = true, composer = composer)
        assertEquals(3, tx.inputs.size)
        assertEquals(1, tx.outputs.size)
        assertEquals(19_507_193L - feeFor(tx, coins), tx.outputs[0].value.value)
        assertEquals(tx.outputs[0].value.value, composer.getComposedAmountSats())
    }

    @Test
    fun spendAllLeavesNoChangeWhenTheFigureLagsTheCoinsByMoreThanDust() {
        val coins = listOf(
            wallet.funded(12_000_000L, 0), wallet.funded(7_506_493L, 1), wallet.funded(50_000L, 2)
        )
        // As an amount, the stale figure composes but leaves a change output.
        assertEquals(2, compose(coins, 19_506_493L).outputs.size)
        val tx = compose(coins, 19_506_493L, spendAll = true)
        assertEquals(3, tx.inputs.size)
        assertEquals(1, tx.outputs.size)
    }

    @Test
    fun spendAllIgnoresAFigureAboveTheCoins() {
        val coins = listOf(wallet.funded(12_000_000L, 0), wallet.funded(7_506_493L, 1))
        val tx = compose(coins, 25_000_000L, spendAll = true)
        assertEquals(2, tx.inputs.size)
        assertEquals(1, tx.outputs.size)
        assertEquals(19_506_493L - feeFor(tx, coins), tx.outputs[0].value.value)
    }

    @Test
    fun spendAllOfPinnedCoinsSpendsExactlyThoseCoins() {
        val pinned = listOf(wallet.funded(3_000_000L, 0), wallet.funded(2_000_000L, 1))
        val tx = compose(pinned, 1L, explicit = true, spendAll = true)
        assertEquals(2, tx.inputs.size)
        assertEquals(1, tx.outputs.size)
        assertEquals(5_000_000L - feeFor(tx, pinned), tx.outputs[0].value.value)
    }

    @Test
    fun spendAllOfCoinsThatCannotPayTheFeeIsRefused() {
        val coins = listOf(wallet.funded(600L, 0))
        val failed = composeFailure(coins, 600L, spendAll = true)
        assertTrue(failed is TransactionComposer.ComposeException)
    }

    @Test
    fun tappingTheTotalComposesAMaxSendThroughTheViewModel() {
        val coins = listOf(
            wallet.funded(12_000_000L, 0), wallet.funded(7_506_493L, 1), wallet.funded(700L, 2)
        )
        val vm = viewModel(coins)
        vm.setSpendAll(19_506_493L)
        vm.settle()
        assertTrue(vm.isSpendAll())
        assertEquals(true, vm.validSpend.value)
        assertEquals(SendViewModel.SpendBlocker.NONE, vm.spendBlocker.value)
        val sent = vm.spendAllSentSats.value!!
        assertTrue(sent in 19_500_000L until 19_507_193L)

        // Typing an amount is no longer a spend-all.
        vm.setAmountSats(10_000_000L)
        vm.settle()
        assertTrue(!vm.isSpendAll())
        assertEquals(null, vm.spendAllSentSats.value)
        assertEquals(true, vm.validSpend.value)
    }

    @Test
    fun droppingASpendAllKeepsTheNetAmountTheFieldShows() {
        val coins = listOf(wallet.funded(12_000_000L, 0), wallet.funded(7_506_493L, 1))
        val composer = composer()
        val vm = viewModel(coins, composer)
        vm.setSpendAll(19_506_493L)
        vm.settle()
        val sent = vm.spendAllSentSats.value!!
        assertTrue(sent < 19_506_493L)

        // An account change drops spend-all, and the field still shows
        // sent. The next account's coins compose that as a typed amount.
        vm.dropSpendAll()
        vm.applySelectableUtxos(listOf(wallet.funded(30_000_000L, 2).utxo))
        vm.settle()
        assertTrue(!vm.isSpendAll())
        assertEquals(true, vm.validSpend.value)
        assertEquals(sent, composer.getComposedAmountSats())
    }

    @Test
    fun theFigureIsTheSumOfTheCoinsComposeSpends() {
        val coins = listOf(wallet.funded(12_000_000L, 0), wallet.funded(7_506_493L, 1))
        val vm = viewModel(coins)
        assertEquals(19_506_493L, vm.selectableSpendable.value)
    }

    @Test
    fun aDoNotSpendCoinLeavesTheFigureAndAMarkOnASpentCoinDoesNot() {
        val kept = wallet.funded(12_000_000L, 0)
        val frozen = wallet.funded(7_506_493L, 1)
        val spent = wallet.funded(3_000_000L, 2)
        val vm = viewModel(listOf(kept, frozen))
        try {
            UtxoMetaUtil.put(frozen.utxo)
            vm.onDoNotSpendChanged()
            vm.settle()
            assertEquals(12_000_000L, vm.selectableSpendable.value)

            // A mark whose coin is no longer in the pool used to keep
            // subtracting its stored amount until the next sync.
            UtxoMetaUtil.put(spent.utxo)
            vm.onDoNotSpendChanged()
            vm.settle()
            assertEquals(12_000_000L, vm.selectableSpendable.value)

            vm.setSpendAll(12_000_000L)
            vm.settle()
            assertEquals(true, vm.validSpend.value)
            assertTrue(vm.spendAllSentSats.value!! < 12_000_000L)
        } finally {
            UtxoMetaUtil.remove(frozen.utxo.txHash!!, 0)
            UtxoMetaUtil.remove(spent.utxo.txHash!!, 0)
        }
        assertEquals(0L, spendableTotal(emptyList()) { true })
    }

    @Test
    fun aCoinArrivingOnSendRedrawsTheFigureAndSpendAllIncludesIt() {
        val first = listOf(wallet.funded(12_000_000L, 0), wallet.funded(7_506_493L, 1))
        val vm = viewModel(first)
        vm.setSpendAll(19_506_493L)
        vm.settle()
        val before = vm.spendAllSentSats.value!!

        val arrived = first + wallet.funded(50_000L, 2)
        vm.applySelectableUtxos(arrived.map { it.utxo })
        vm.settle()
        assertEquals(19_556_493L, vm.selectableSpendable.value)
        assertEquals(true, vm.validSpend.value)
        val after = vm.spendAllSentSats.value!!
        assertTrue("the new coin is spent too", after > before + 49_000L)
    }

    @Test
    fun aCollectionUpdateRedrawsTheFigureWhenAnXpubIsHiddenOrBack() {
        val coins = listOf(wallet.funded(12_000_000L, 0))
        val vm = viewModel(coins)
        assertEquals(12_000_000L, vm.selectableSpendable.value)
        try {
            PubSyncHealth.markFailed(wallet.zpub, SyncFailureKind.TRANSPORT)
            vm.onCollectionUpdated()
            vm.settle()
            assertEquals(0L, vm.selectableSpendable.value)
            assertEquals(SendViewModel.SpendBlocker.NO_FUNDS, vm.spendBlocker.value)
        } finally {
            PubSyncHealth.clear(wallet.zpub)
        }
        vm.onCollectionUpdated()
        vm.settle()
        assertEquals(12_000_000L, vm.selectableSpendable.value)
    }

    @Test
    fun anExpiredTokenOnThePrevTxFetchIsRefreshedOnceAndComposeSucceeds() {
        // Before the expired-token retry this was one 401, no refresh and
        // "Cannot build a transaction from these inputs".
        val coins = listOf(wallet.funded(12_000_000L, 0), wallet.funded(7_506_493L, 1))
        mode = Mode.EXPIRED_UNTIL_REFRESH
        val tx = compose(coins, 19_506_493L, spendAll = true)
        assertEquals(1, tx.outputs.size)
        assertEquals("one refresh for the whole compose", 1, authRequests.get())
        assertEquals("one 401, its resend, then the second input", 3, hexRequests.get())
    }

    @Test
    fun anExpiredTokenTheRefreshCannotRenewIsReportedAsDojoUnreachable() {
        val coins = listOf(wallet.funded(12_000_000L, 0))
        mode = Mode.EXPIRED_REFRESH_REFUSED
        val failed = composeFailure(coins, 5_000_000L)
        assertTrue(failed is TransactionComposer.PrevTxException)
        assertTrue((failed as TransactionComposer.PrevTxException).dojoUnreachable)
        assertEquals("no resend after a failed refresh", 1, hexRequests.get())
        assertEquals(1, authRequests.get())
    }

    @Test
    fun aDojoThatDoesNotAnswerIsToldApartFromAPrevTxThatDoesNotVerify() {
        val coin = wallet.funded(12_000_000L, 0)
        for (unreachable in listOf(Mode.UNAVAILABLE, Mode.DROP)) {
            mode = unreachable
            val failed = composeFailure(listOf(coin), 5_000_000L)
            assertTrue("$unreachable ${failed.javaClass.simpleName} ${failed.cause}", (failed as TransactionComposer.PrevTxException).dojoUnreachable)
        }
        mode = Mode.SERVE
        // The Dojo answers, but about a coin it does not know ...
        val unknown = wallet.funded(3_000_000L, 1)
        forget(unknown)
        val notFound = composeFailure(listOf(unknown), 1_000_000L)
        assertTrue(!(notFound as TransactionComposer.PrevTxException).dojoUnreachable)
        // ... or with a prev tx whose output is not the value the coin claims.
        val lying = wallet.funded(4_000_000L, 2).withValue(4_000_001L)
        val mismatch = composeFailure(listOf(lying), 1_000_000L)
        assertTrue(!(mismatch as TransactionComposer.PrevTxException).dojoUnreachable)
        assertEquals(
            ApplicationProvider.getApplicationContext<Application>()
                .getString(com.samourai.sentinel.R.string.send_error_prev_tx_unverified),
            mismatch.message
        )

        assertTrue(TransactionComposer.isDojoUnavailableStatus(401))
        assertTrue(TransactionComposer.isDojoUnavailableStatus(503))
        assertTrue(TransactionComposer.isDojoUnavailableStatus(429))
        assertTrue(!TransactionComposer.isDojoUnavailableStatus(404))
        assertTrue(!TransactionComposer.isDojoUnavailableStatus(400))
    }

    @Test
    fun verifiedPrevTxsAreFetchedOncePerSendScreenAndStillChecked() {
        val coins = (0 until 8).map { wallet.funded(1_000_000L + it, it) }
        val total = coins.sumOf { it.utxo.value!! }
        val composer = composer()
        compose(coins, total, spendAll = true, composer = composer)
        assertEquals("a max send fetches every coin once", 8, hexRequests.getAndSet(0))
        compose(coins, total, spendAll = true, composer = composer)
        compose(coins, 50_000L, composer = composer)
        assertEquals("a recompose on the same screen fetches nothing", 0, hexRequests.get())

        // The cached prev tx is still held against the coin on every compose.
        val lying = listOf(coins[0].withValue(1_000_007L))
        val failed = composeFailure(lying, 500_000L, composer = composer)
        assertTrue(failed is TransactionComposer.PrevTxException)
        assertEquals(0, hexRequests.get())

        // A new Send screen has a new composer and fetches again.
        compose(coins.take(1), 500_000L)
        assertEquals(1, hexRequests.get())
    }

    @Test
    fun aDojoFailureShowsItsOwnReasonAndRetryComposesAgain() {
        val coins = listOf(wallet.funded(12_000_000L, 0), wallet.funded(7_506_493L, 1))
        val vm = viewModel(coins)
        mode = Mode.UNAVAILABLE
        vm.setSpendAll(19_506_493L)
        vm.settle()
        assertEquals(SendViewModel.SpendBlocker.DOJO_UNREACHABLE, vm.spendBlocker.value)
        assertTrue(vm.spendBlocker.value!!.canRetry)
        assertEquals(false, vm.validSpend.value)

        mode = Mode.SERVE
        vm.retryCompose()
        vm.settle()
        assertEquals(SendViewModel.SpendBlocker.NONE, vm.spendBlocker.value)
        assertEquals(true, vm.validSpend.value)
        assertTrue(!SendViewModel.SpendBlocker.CANNOT_COMPOSE.canRetry)
    }

    @Test
    fun composeSaysItIsWorkingAndShowsTheFeeOnlyOnceItSucceeds() {
        val coins = listOf(wallet.funded(12_000_000L, 0), wallet.funded(7_506_493L, 1))
        val vm = viewModel(coins)
        mode = Mode.SLOW
        vm.setSpendAll(19_506_493L)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SendViewModel.SpendBlocker.COMPOSING, vm.spendBlocker.value)
        assertEquals(
            "Verifying unspents with Dojo…",
            ApplicationProvider.getApplicationContext<Application>().getString(R.string.send_composing),
        )
        assertEquals(false, vm.validSpend.value)
        assertEquals("no fee while the Dojo is still being asked", "", vm.minerFee.value)
        vm.settle()
        assertEquals(SendViewModel.SpendBlocker.NONE, vm.spendBlocker.value)
        assertTrue(vm.minerFee.value!!.endsWith(" BTC"))

        // A compose that then fails on the Dojo leaves no fee behind.
        val fresh = viewModel(coins)
        mode = Mode.UNAVAILABLE
        fresh.setSpendAll(19_506_493L)
        fresh.settle()
        assertEquals(SendViewModel.SpendBlocker.DOJO_UNREACHABLE, fresh.spendBlocker.value)
        assertEquals("", fresh.minerFee.value)
    }

    @Test
    fun theComposerAnnouncesNoFeeForAComposeThatFailsOnThePrevTx() {
        val coins = listOf(wallet.funded(12_000_000L, 0))
        mode = Mode.UNAVAILABLE
        val composer = composer()
        runBlocking {
            withTimeout(20_000) {
                val fee = async { composer.getMinerFee().receive() }
                try {
                    composer.compose(
                        inputUtxos = ArrayList(coins.map { it.utxo }),
                        inputAddress = wallet.externalAddress,
                        inputFee = 1000L,
                        inputAmountSats = 5_000_000L,
                    )
                    fail("expected compose to fail")
                } catch (expected: TransactionComposer.PrevTxException) {
                }
                assertTrue("the fee went out before the Dojo answered", !fee.isCompleted)
                fee.cancel()
            }
        }
        assertEquals(null, composer.getComposedFeeSats())
        assertEquals(null, composer.getComposedAmountSats())
    }

    @Test
    fun useAutomaticSelectionOnlyHasSomethingToDoWhileCoinsArePinned() {
        val coins = listOf(wallet.funded(12_000_000L, 0), wallet.funded(7_506_493L, 1))
        val vm = viewModel(coins)
        assertTrue("already automatic", !vm.canClearPinnedSelection)
        assertTrue(vm.setPinnedSelection(setOf(CoinSelection.keyOf(coins[0].utxo))))
        vm.settle()
        assertTrue(vm.canClearPinnedSelection)
        vm.clearPinnedSelection()
        vm.settle()
        assertTrue(!vm.canClearPinnedSelection)
    }


    @Test
    fun noPairedDojoReportsNoDojoInsteadOfUnreachableWithNoRetry() {
        val coins = listOf(wallet.funded(12_000_000L, 0))
        val savedEndpoint = prefs.apiEndPoint
        val savedTorEndpoint = prefs.apiEndPointTor
        try {
            prefs.apiEndPoint = null
            prefs.apiEndPointTor = null
            val vm = viewModel(coins)
            vm.setAmountSats(5_000_000L)
            vm.settle()
            assertEquals(SendViewModel.SpendBlocker.NO_DOJO, vm.spendBlocker.value)
            assertEquals(false, vm.spendBlocker.value!!.canRetry)
            assertEquals(false, vm.validSpend.value)
            assertEquals(
                "No Dojo paired. Pair a Dojo in Settings",
                ApplicationProvider.getApplicationContext<Application>()
                    .getString(R.string.send_error_no_dojo),
            )
        } finally {
            prefs.apiEndPoint = savedEndpoint
            prefs.apiEndPointTor = savedTorEndpoint
        }
    }

    @Test
    fun prevTxHexIsRejectedAndNotCachedBeforeHashCheckPasses() {
        val coin = wallet.funded(12_000_000L, 0)
        val wrongTx = wallet.funded(12_000_000L, 1)
        val composer = composer()

        // First compose receives valid hex for a DIFFERENT transaction (wrong txid hash).
        oneShotResponse = MockResponse().setBody("""{"status":"ok","data":"${wrongTx.hex}"}""")
        val wrongHashFailure = composeFailure(listOf(coin), 5_000_000L, composer = composer)
        assertTrue(wrongHashFailure is TransactionComposer.PrevTxException)
        assertTrue(!(wrongHashFailure as TransactionComposer.PrevTxException).dojoUnreachable)
        assertEquals(1, hexRequests.getAndSet(0))

        // Second compose with malformed hex also fails and is not cached.
        oneShotResponse = MockResponse().setBody("""{"status":"ok","data":"deadbeef"}""")
        val malformedFailure = composeFailure(listOf(coin), 5_000_000L, composer = composer)
        assertTrue(malformedFailure is TransactionComposer.PrevTxException)
        assertEquals(1, hexRequests.getAndSet(0))

        // Third compose with the honest server MUST re-fetch (neither bad hex was cached) and succeed.
        val tx = compose(listOf(coin), 5_000_000L, composer = composer)
        assertEquals(1, tx.inputs.size)
        assertEquals(1, hexRequests.get())
    }

    @Test
    fun prevTxCacheEvictsEldestEntryAtThe500Cap() {
        assertEquals(500, TransactionComposer.PREV_TX_CACHE_MAX)
        val coins = (0..500).map { wallet.funded(1_000_000L, it) }
        val composer = composer()

        // Insert entry 0 first so it is strictly the eldest entry in the FIFO cache,
        // then fill the remaining 499 slots across batches.
        compose(listOf(coins[0]), 100_000L, composer = composer)
        compose(coins.subList(1, 50), 100_000L, explicit = true, spendAll = true, composer = composer)
        for (start in 50 until 500 step 50) {
            val batch = coins.subList(start, start + 50)
            compose(batch, 100_000L, explicit = true, spendAll = true, composer = composer)
        }
        assertEquals(500, hexRequests.getAndSet(0))

        // Confirm entry 0 is currently cached (and FIFO order is unchanged).
        compose(listOf(coins[0]), 100_000L, composer = composer)
        assertEquals("entry 0 should be cached before overflow", 0, hexRequests.getAndSet(0))

        // Adding the 501st entry evicts entry 0 (FIFO), while a newer entry stays cached.
        compose(listOf(coins[500]), 100_000L, composer = composer)
        assertEquals(1, hexRequests.getAndSet(0))

        compose(listOf(coins[499]), 100_000L, composer = composer)
        assertEquals("newer entry 499 should still be cached", 0, hexRequests.getAndSet(0))

        compose(listOf(coins[0]), 100_000L, composer = composer)
        assertEquals("entry 0 should have been evicted at the 500 cap", 1, hexRequests.get())
    }

    // Fixture

    private fun composer() = TransactionComposer().apply { setPubKey(listOf(wallet.pub)) }

    private fun compose(
        utxos: List<FundedUtxo>,
        amountSats: Long,
        explicit: Boolean = false,
        spendAll: Boolean = false,
        composer: TransactionComposer = composer(),
    ): Transaction {
        val bytes = runBlocking {
            withTimeout(20_000) {
                val fee = async { composer.getMinerFee().receive() }
                val ok = composer.compose(
                    inputUtxos = ArrayList(utxos.map { it.utxo }),
                    inputAddress = wallet.externalAddress,
                    inputFee = 1000L,
                    inputAmountSats = amountSats,
                    explicitSelection = explicit,
                    spendAll = spendAll,
                )
                assertTrue(ok)
                fee.await()
                composer.getPSBT()?.toBytes() ?: error("composer returned no PSBT bytes")
            }
        }
        return unsignedTx(bytes)
    }

    private fun composeFailure(
        utxos: List<FundedUtxo>,
        amountSats: Long,
        spendAll: Boolean = false,
        composer: TransactionComposer = composer(),
    ): Exception = runBlocking {
        withTimeout(20_000) {
            val fee = async { composer.getMinerFee().receive() }
            try {
                composer.compose(
                    inputUtxos = ArrayList(utxos.map { it.utxo }),
                    inputAddress = wallet.externalAddress,
                    inputFee = 1000L,
                    inputAmountSats = amountSats,
                    spendAll = spendAll,
                )
                fail("expected compose to fail")
                throw IllegalStateException()
            } catch (e: Exception) {
                if (e is AssertionError) throw e
                e
            } finally {
                fee.cancel()
            }
        }
    }

    /** A Send ViewModel on [coins], with the composer bound to the test wallet. */
    private fun viewModel(
        coins: List<FundedUtxo>,
        composer: TransactionComposer = composer(),
    ): SendViewModel {
        val vm = SendViewModel()
        SendViewModel::class.java.getDeclaredField("transactionComposer").apply {
            isAccessible = true
            set(vm, composer)
        }
        // The ViewModel drains the fee channel of the composer it built.
        vm.viewModelScope.launch { for (ignored in composer.getMinerFee()) Unit }
        vm.setDestinationAddress(wallet.externalAddress)
        vm.applySelectableUtxos(coins.map { it.utxo })
        vm.settle()
        return vm
    }

    /** Runs the compose job and the main-thread verdict it posts. */
    private fun SendViewModel.settle() {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 20_000
        while (job?.isActive == true) {
            check(System.currentTimeMillis() < deadline) { "compose did not finish" }
            looper.idle()
            Thread.sleep(5)
        }
        repeat(3) { looper.idle() }
    }

    /** The fake Dojo no longer knows [coin]'s funding tx. */
    private fun forget(coin: FundedUtxo) {
        served.remove(coin.utxo.txHash)
    }

    /** The same outpoint, claiming [value] sats. */
    private fun FundedUtxo.withValue(value: Long): FundedUtxo =
        FundedUtxo(utxo.copy(value = value), hex)

    private fun feeFor(tx: Transaction, coins: List<FundedUtxo>): Long =
        coins.sumOf { it.utxo.value!! } - tx.outputs.sumOf { it.value.value }

    /** The PSBT global unsigned tx (key type 0x00, first global record). */
    private fun unsignedTx(psbt: ByteArray): Transaction {
        var i = 5 // magic + 0xff
        fun compact(): Int {
            val first = psbt[i++].toInt() and 0xff
            return when (first) {
                0xfd -> (psbt[i++].toInt() and 0xff) or ((psbt[i++].toInt() and 0xff) shl 8)
                0xfe -> {
                    var v = 0
                    for (s in 0 until 4) v = v or ((psbt[i++].toInt() and 0xff) shl (8 * s))
                    v
                }
                else -> first
            }
        }
        while (true) {
            val keyLen = compact()
            check(keyLen != 0) { "no unsigned tx in PSBT globals" }
            val keyType = psbt[i].toInt() and 0xff
            i += keyLen
            val valueLen = compact()
            val value = psbt.copyOfRange(i, i + valueLen)
            i += valueLen
            if (keyType == 0x00) return Transaction(SentinelState.getNetworkParam(), value)
        }
    }

    /** #469: the Utxo plus the funding-tx hex the fake Dojo serves. */
    private class FundedUtxo(val utxo: Utxo, val hex: String)

    private class Wallet(val params: NetworkParameters) {
        val zpub: String
        val externalAddress: String
        val pub: PubKeyModel

        init {
            val seed = ByteArray(32) { (it + 7).toByte() }
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
                ECKey.fromPrivate(ByteArray(32) { 0x24 }).pubKey,
                params
            ).bech32AsString
            pub = PubKeyModel(
                pubKey = zpub,
                label = "Deposit",
                type = AddressTypes.BIP84,
                change_index = 3,
                fingerPrint = "0badf00d"
            )
        }

        fun funded(value: Long, index: Int): FundedUtxo {
            val account = HD_Account(params, zpub, "", 0)
            val key = account.getChain(0).getAddressAt(index).ecKey
            val address = SegwitAddress(key.pubKey, params).bech32AsString
            val script = Bech32UtilGeneric.getInstance().computeScriptPubKey(address, params)
            val funding = Transaction(params)
            // One dummy OP_TRUE input keeps the serialization unambiguously
            // legacy (no inputs + one output reads as a BIP144 marker).
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

    /** How the fake Dojo answers /tx/{txid}/HEX. */
    internal enum class Mode {
        SERVE,
        /** 401 Invalid JSON Web Token until the login has been refreshed once. */
        EXPIRED_UNTIL_REFRESH,
        /** 401 Invalid JSON Web Token, and the refresh is refused too. */
        EXPIRED_REFRESH_REFUSED,
        /** 503 from the Dojo. */
        UNAVAILABLE,
        /** The connection drops before an answer. */
        DROP,
        /** Serves, but each prev tx takes a while (a slow Tor circuit). */
        SLOW,
    }

    companion object {
        @Volatile
        private var startedKoin = false

        private lateinit var server: MockWebServer
        private lateinit var prefs: PrefsUtil

        private val served = HashMap<String, String>()

        @Volatile
        internal var mode = Mode.SERVE
        @Volatile
        internal var oneShotResponse: MockResponse? = null
        @Volatile
        private var refreshed = false
        val hexRequests = AtomicInteger()
        val authRequests = AtomicInteger()

        private const val DOJO_EXPIRED = """{"status":"error","error":"Invalid JSON Web Token"}"""
        private const val FRESH_AUTH =
            """{"authorizations":{"access_token":"access-new","refresh_token":"refresh-new"}}"""

        private fun serverUrl(): String = server.url("/").toString()

        private fun installDispatcher() {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (path.contains("/auth/")) {
                        authRequests.incrementAndGet()
                        if (mode == Mode.EXPIRED_REFRESH_REFUSED) {
                            return MockResponse().setResponseCode(401).setBody(DOJO_EXPIRED)
                        }
                        refreshed = true
                        return MockResponse().setBody(FRESH_AUTH)
                    }
                    hexRequests.incrementAndGet()
                    oneShotResponse?.let { override ->
                        oneShotResponse = null
                        return override
                    }
                    when (mode) {
                        Mode.SERVE -> Unit
                        Mode.EXPIRED_UNTIL_REFRESH -> if (!refreshed) {
                            return MockResponse().setResponseCode(401).setBody(DOJO_EXPIRED)
                        }
                        Mode.EXPIRED_REFRESH_REFUSED ->
                            return MockResponse().setResponseCode(401).setBody(DOJO_EXPIRED)
                        Mode.UNAVAILABLE ->
                            return MockResponse().setResponseCode(503).setBody("""{"status":"error"}""")
                        Mode.DROP ->
                            return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        Mode.SLOW -> Unit
                    }
                    val txid = path.substringAfter("/tx/").substringBefore("/HEX")
                    val hex = served[txid]
                        ?: return MockResponse().setResponseCode(404)
                            .setBody("""{"status":"error"}""")
                    val served = MockResponse().setBody("""{"status":"ok","data":"$hex"}""")
                    if (mode == Mode.SLOW) {
                        served.setHeadersDelay(400, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                    return served
                }
            }
        }

        private fun ensureRuntime(baseUrl: String) {
            GlobalContext.getOrNull()?.let { stopKoin() }
            val app = ApplicationProvider.getApplicationContext<Application>()
            prefs = PrefsUtil(app)
            prefs.testnet = false
            prefs.apiEndPoint = baseUrl
            // Synthetic values: only the fake Dojo above ever sees them.
            prefs.authorization = "access-old"
            prefs.refreshToken = "refresh-old"
            refreshed = false
            startKoin {
                androidContext(app)
                modules(
                    module {
                        single { prefs }
                        single { CollectionRepository() }
                        single { ApiService() }
                        // getAPIUrl() asks SentinelState.isTorRequired(),
                        // which needs a DojoUtility. An empty Dojo store
                        // keeps requests on the plain apiEndPoint with Tor off.
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
        }
    }
}
