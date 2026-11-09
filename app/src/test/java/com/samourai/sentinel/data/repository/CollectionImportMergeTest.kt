package com.samourai.sentinel.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.db.PayloadReadException
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.db.dao.TxDao
import com.samourai.sentinel.data.db.dao.UtxoDao
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.CollectionImportDecision
import com.samourai.sentinel.util.CollectionImportNeedsDecision
import com.samourai.sentinel.util.ExportImportUtil
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent.inject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A non-clearing import merges with what the device already holds.
 * "Clear existing" still replaces the wallet.
 *
 * A collection is the same one when its name is exactly the same and
 * its set of public keys is exactly the same, or when one of those
 * sets contains the other and no other collection under that name
 * shares a key. Key order, labels, and balances are not part of that.
 * Keys already present stay, with their labels and settings, and only
 * the keys the device does not have are appended.
 *
 * One shared key between otherwise different sets is not that
 * collection, and neither is more than one partial overlap. The import
 * keeps those as their own collections. Choosing a collection is what
 * appends keys, and a missing choice writes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectionImportMergeTest {

    private lateinit var db: SentinelRoomDb
    private var startedKoin = false

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SentinelRoomDb::class.java
        ).allowMainThreadQueries().build()
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidContext(ApplicationProvider.getApplicationContext())
                modules(
                    module {
                        single { PrefsUtil(ApplicationProvider.getApplicationContext()) }
                        single { SentinelCollectionStore() }
                        single<TxDao> { db.txDao() }
                        single<UtxoDao> { db.utxoDao() }
                        single { CollectionRepository() }
                    }
                )
            }
            startedKoin = true
        }
    }

    @After
    fun tearDown() {
        db.close()
        if (startedKoin) {
            stopKoin()
            startedKoin = false
        }
    }

    @Test
    fun reimportOfTheSameBackupDoesNotDuplicateCollectionsOrKeys() = runBlocking {
        val prefs = prefs()
        prefs.whirlpoolAutoLabels = true
        prefs.selectedExplorer = "Mempool"
        prefs.offlineMode = true
        prefs.currentFiat = "EUR"
        prefs.fiatDisabled = true

        val savings = key(
            "zpub-savings", "Savings",
            balance = 1000, changeIndex = 4, accountIndex = 3, fingerprint = "fp-savings"
        )
        val checking = key(
            "zpub-checking", "Checking",
            balance = 200, changeIndex = 5, accountIndex = 3, fingerprint = "fp-checking"
        )
        val vault = key(
            "zpub-vault", "Vault",
            balance = 50, changeIndex = 1, accountIndex = 0, fingerprint = "fp-vault",
            type = AddressTypes.BIP49
        )
        val before = install(
            collection(
                "My Wallet", savings, checking,
                balance = 9999, lastRefreshed = 42, fromWallet = true
            ),
            collection(
                "Cold Storage", vault,
                balance = 50, lastRefreshed = 7, fromWallet = false
            )
        )
        val myId = before.single { it.collectionLabel == "My Wallet" }.id
        val coldId = before.single { it.collectionLabel == "Cold Storage" }.id

        // Same keys, different order, and backup copies of every label
        // and setting. None of those may replace what is on the device.
        val backup = arrayListOf(
            collection(
                "My Wallet",
                checking.copy(
                    label = "checking-from-backup", balance = 1,
                    change_index = 99, account_index = 99, fingerPrint = "nope"
                ),
                savings.copy(
                    label = "savings-from-backup", balance = 1,
                    change_index = 99, account_index = 99, fingerPrint = "nope"
                ),
                balance = 1, lastRefreshed = 999, fromWallet = false
            ),
            collection(
                "Cold Storage",
                vault.copy(
                    label = "vault-from-backup", balance = 9,
                    change_index = 99, fingerPrint = "nope"
                ),
                balance = 9, lastRefreshed = 999, fromWallet = true
            )
        )

        import(backup, replace = false)
        val after = import(backup, replace = false)

        assertEquals(listOf("My Wallet", "Cold Storage"), after.map { it.collectionLabel })
        val my = after.single { it.id == myId }
        val cold = after.single { it.id == coldId }

        assertEquals(42L, my.lastRefreshed)
        assertTrue(my.isImportFromWallet)
        assertEquals(9999L, my.balance)
        assertEquals(listOf("zpub-savings", "zpub-checking"), my.pubs.map { it.pubKey })
        assertEquals(listOf("Savings", "Checking"), my.pubs.map { it.label })
        assertEquals(4, my.pubs[0].change_index)
        assertEquals(3, my.pubs[0].account_index)
        assertEquals("fp-savings", my.pubs[0].fingerPrint)
        assertEquals(1000L, my.pubs[0].balance)
        assertEquals(AddressTypes.BIP84, my.pubs[0].type)

        assertEquals(7L, cold.lastRefreshed)
        assertFalse(cold.isImportFromWallet)
        assertEquals(50L, cold.balance)
        assertEquals("Vault", cold.pubs.single().label)
        assertEquals("fp-vault", cold.pubs.single().fingerPrint)
        assertEquals(AddressTypes.BIP49, cold.pubs.single().type)

        assertEquals(true, prefs.whirlpoolAutoLabels)
        assertEquals("Mempool", prefs.selectedExplorer)
        assertEquals(true, prefs.offlineMode)
        assertEquals("EUR", prefs.currentFiat)
        assertEquals(true, prefs.fiatDisabled)

        // A restart reads the file the import just wrote. The duplicate
        // rows must not come back, and the kept labels must.
        val fresh = CollectionRepository()
        fresh.read()
        val restored = fresh.collectionsSnapshot()
        assertEquals(listOf("My Wallet", "Cold Storage"), restored.map { it.collectionLabel })
        assertEquals(
            listOf("Savings", "Checking"),
            restored.single { it.id == myId }.pubs.map { it.label }
        )
        assertEquals("Vault", restored.single { it.id == coldId }.pubs.single().label)
    }

    @Test
    fun partialOverlapAddsNewCollectionsAndKeysWithoutDuplicating() = runBlocking {
        val savings = key(
            "zpub-savings", "Savings",
            balance = 1000, changeIndex = 4, accountIndex = 3, fingerprint = "fp-savings"
        )
        val checking = key(
            "zpub-checking", "Checking",
            balance = 200, changeIndex = 5, accountIndex = 3, fingerprint = "fp-checking"
        )
        val vault = key("zpub-vault", "Vault", balance = 50, fingerprint = "fp-vault")
        val donate = key("zpub-donate", "Donate", balance = 8, fingerprint = "fp-donate")
        val before = install(
            collection(
                "My Wallet", savings, checking,
                balance = 1200, lastRefreshed = 42, fromWallet = true
            ),
            collection("Cold Storage", vault, balance = 50, lastRefreshed = 7),
            collection("Watch only", donate, balance = 8, lastRefreshed = 11)
        )
        val ids = before.associate { it.collectionLabel to it.id }

        val hardware = key(
            "zpub-hardware", "Hardware account",
            balance = 25, changeIndex = 2, accountIndex = 1, fingerprint = "fp-hardware"
        )
        val paper = key("zpub-paper", "Paper", balance = 3, fingerprint = "fp-paper")
        val after = import(
            arrayListOf(
                // Exact key set, backup label must not win.
                collection(
                    "Cold Storage",
                    vault.copy(label = "vault-from-backup", balance = 9),
                    balance = 9, lastRefreshed = 999
                ),
                // The keys already stored, plus one new key. The stored
                // key is repeated in the backup and must be added once.
                collection(
                    "My Wallet",
                    checking.copy(label = "checking-from-backup", balance = 1, change_index = 99),
                    checking.copy(label = "checking-again", balance = 1),
                    savings.copy(label = "savings-from-backup", balance = 1, change_index = 99),
                    hardware,
                    balance = 1, lastRefreshed = 999, fromWallet = false
                ),
                collection(
                    "Paper", paper,
                    balance = 3, lastRefreshed = 15, fromWallet = true
                )
            ),
            replace = false
        )

        assertEquals(
            listOf("My Wallet", "Cold Storage", "Watch only", "Paper"),
            after.map { it.collectionLabel }
        )
        assertEquals(ids["My Wallet"], after[0].id)
        assertEquals(ids["Cold Storage"], after[1].id)
        assertEquals(ids["Watch only"], after[2].id)
        assertFalse(after[3].id in ids.values)

        val my = after[0]
        assertEquals(
            listOf("zpub-savings", "zpub-checking", "zpub-hardware"),
            my.pubs.map { it.pubKey }
        )
        assertEquals(listOf("Savings", "Checking", "Hardware account"), my.pubs.map { it.label })
        assertEquals(4, my.pubs[0].change_index)
        assertEquals("fp-savings", my.pubs[0].fingerPrint)
        assertEquals(1000L, my.pubs[0].balance)
        assertEquals(2, my.pubs[2].change_index)
        assertEquals(1, my.pubs[2].account_index)
        assertEquals("fp-hardware", my.pubs[2].fingerPrint)
        assertEquals(25L, my.pubs[2].balance)
        // Existing key balances plus the appended key. The stale backup
        // total is not copied onto the collection.
        assertEquals(1225L, my.balance)
        assertEquals(42L, my.lastRefreshed)
        assertTrue(my.isImportFromWallet)

        val cold = after[1]
        assertEquals("Vault", cold.pubs.single().label)
        assertEquals(50L, cold.balance)
        assertEquals(7L, cold.lastRefreshed)

        val watch = after[2]
        assertEquals(listOf("zpub-donate"), watch.pubs.map { it.pubKey })
        assertEquals("Donate", watch.pubs.single().label)
        assertEquals(8L, watch.balance)
        assertEquals(11L, watch.lastRefreshed)

        val added = after[3]
        assertEquals("Paper", added.collectionLabel)
        assertEquals(listOf("zpub-paper"), added.pubs.map { it.pubKey })
        assertEquals("Paper", added.pubs.single().label)
        assertEquals(15L, added.lastRefreshed)
        assertTrue(added.isImportFromWallet)
    }

    @Test
    fun nameClashWithDifferentKeysKeepsBothCollections() = runBlocking {
        val savings = key("zpub-savings", "Savings", balance = 1000, fingerprint = "fp-savings")
        val before = install(
            collection(
                "My Wallet", savings,
                balance = 1000, lastRefreshed = 99, fromWallet = true
            )
        )
        val existingId = before.single().id

        val alpha = key("zpub-other-a", "Alpha", balance = 4, fingerprint = "fp-a")
        val beta = key("zpub-other-b", "Beta", balance = 6, fingerprint = "fp-b")
        val after = import(
            arrayListOf(
                collection(
                    "My Wallet", alpha, beta,
                    balance = 10, lastRefreshed = 1000, fromWallet = false
                )
            ),
            replace = false
        )

        assertEquals(2, after.size)
        assertEquals(listOf("My Wallet", "My Wallet"), after.map { it.collectionLabel })

        val kept = after.single { it.id == existingId }
        assertEquals(listOf("zpub-savings"), kept.pubs.map { it.pubKey })
        assertEquals("Savings", kept.pubs.single().label)
        assertEquals("fp-savings", kept.pubs.single().fingerPrint)
        assertEquals(1000L, kept.balance)
        assertEquals(99L, kept.lastRefreshed)
        assertTrue(kept.isImportFromWallet)

        val added = after.single { it.id != existingId }
        assertNotEquals(existingId, added.id)
        assertEquals(listOf("zpub-other-a", "zpub-other-b"), added.pubs.map { it.pubKey })
        assertEquals(listOf("Alpha", "Beta"), added.pubs.map { it.label })
        assertEquals(1000L, added.lastRefreshed)
        assertFalse(added.isImportFromWallet)
        assertEquals(10L, added.balance)
    }

    @Test
    fun clearExistingStillReplacesTheWallet() = runBlocking {
        val savings = key("zpub-savings", "Savings", balance = 1000, fingerprint = "fp-savings")
        val donate = key("zpub-donate", "Donate")
        val before = install(
            collection("My Wallet", savings, balance = 9999, lastRefreshed = 42, fromWallet = true),
            collection("Watch only", donate, lastRefreshed = 11)
        )
        val oldIds = before.map { it.id }.toSet()

        val after = import(
            arrayListOf(
                collection(
                    "My Wallet",
                    savings.copy(label = "From backup", balance = 5, fingerPrint = "fp-backup"),
                    balance = 5, lastRefreshed = 80, fromWallet = false
                ),
                collection(
                    "Only backup",
                    key("zpub-only", "Only"),
                    balance = 1, lastRefreshed = 3
                )
            ),
            replace = true
        )

        assertEquals(listOf("My Wallet", "Only backup"), after.map { it.collectionLabel })
        assertTrue(after.map { it.id }.none { it in oldIds })
        val my = after[0]
        assertEquals("From backup", my.pubs.single().label)
        assertEquals("fp-backup", my.pubs.single().fingerPrint)
        assertEquals(5L, my.balance)
        assertEquals(80L, my.lastRefreshed)
        assertFalse(my.isImportFromWallet)
        assertEquals(listOf("zpub-only"), after[1].pubs.map { it.pubKey })
        assertTrue(after.none { it.collectionLabel == "Watch only" })
    }

    @Test
    fun oneKeyOverlapStaysSeparateOnImport() = runBlocking {
        val alpha = key("zpub-a", "Alpha", balance = 1000, fingerprint = "fp-a")
        val beta = key("zpub-b", "Beta", balance = 200, changeIndex = 4, fingerprint = "fp-b")
        val before = install(
            collection(
                "My Wallet", alpha, beta,
                balance = 1200, lastRefreshed = 42, fromWallet = true
            )
        )
        val existingId = before.single().id
        val gamma = key("zpub-c", "Gamma", balance = 5, fingerprint = "fp-c")

        val after = import(
            arrayListOf(
                collection(
                    "My Wallet",
                    beta.copy(label = "beta-from-backup", balance = 1, fingerPrint = "nope", change_index = 99),
                    gamma,
                    balance = 9, lastRefreshed = 1000, fromWallet = false
                )
            ),
            replace = false,
        )

        assertEquals(listOf("My Wallet", "My Wallet"), after.map { it.collectionLabel })
        val kept = after.single { it.id == existingId }
        assertEquals(listOf("zpub-a", "zpub-b"), kept.pubs.map { it.pubKey })
        assertEquals(listOf("Alpha", "Beta"), kept.pubs.map { it.label })
        assertEquals("fp-b", kept.pubs[1].fingerPrint)
        assertEquals(4, kept.pubs[1].change_index)
        assertEquals(1200L, kept.balance)
        assertEquals(42L, kept.lastRefreshed)
        assertTrue(kept.isImportFromWallet)
        assertFalse(kept.pubs.any { it.pubKey == "zpub-c" })

        val added = after.single { it.id != existingId }
        assertEquals(listOf("zpub-b", "zpub-c"), added.pubs.map { it.pubKey })
        assertEquals(listOf("beta-from-backup", "Gamma"), added.pubs.map { it.label })
        assertEquals(1000L, added.lastRefreshed)
        assertFalse(added.isImportFromWallet)

        val fresh = CollectionRepository()
        fresh.read()
        val restored = fresh.collectionsSnapshot()
        assertEquals(listOf("zpub-a", "zpub-b"), restored.single { it.id == existingId }.pubs.map { it.pubKey })
        assertEquals(listOf("zpub-b", "zpub-c"), restored.single { it.id != existingId }.pubs.map { it.pubKey })
    }

    @Test
    fun multiplePartialOverlapsStaySeparateOnImport() = runBlocking {
        val alpha = key("zpub-a", "Alpha", balance = 10, fingerprint = "fp-a")
        val beta = key("zpub-b", "Beta", balance = 20, fingerprint = "fp-b")
        val cold = key("zpub-c", "Cold", balance = 30, fingerprint = "fp-c")
        val desk = key("zpub-d", "Desk", balance = 40, fingerprint = "fp-d")
        val before = install(
            collection("Savings", alpha, beta, balance = 30, lastRefreshed = 4, fromWallet = true),
            collection("Savings", cold, desk, balance = 70, lastRefreshed = 5, fromWallet = false),
        )
        val firstId = before[0].id
        val secondId = before[1].id
        val extra = key("zpub-e", "Extra", balance = 7, fingerprint = "fp-e")

        val after = import(
            arrayListOf(
                collection(
                    "Savings",
                    beta.copy(label = "beta-from-backup"),
                    desk.copy(label = "desk-from-backup"),
                    extra,
                    balance = 1, lastRefreshed = 90, fromWallet = false
                )
            ),
            replace = false,
        )

        assertEquals(3, after.size)
        val first = after.single { it.id == firstId }
        val second = after.single { it.id == secondId }
        val added = after.single { it.id != firstId && it.id != secondId }
        assertEquals(listOf("zpub-a", "zpub-b"), first.pubs.map { it.pubKey })
        assertEquals(listOf("Alpha", "Beta"), first.pubs.map { it.label })
        assertEquals(30L, first.balance)
        assertEquals(4L, first.lastRefreshed)
        assertTrue(first.isImportFromWallet)
        assertEquals(listOf("zpub-c", "zpub-d"), second.pubs.map { it.pubKey })
        assertEquals(listOf("Cold", "Desk"), second.pubs.map { it.label })
        assertEquals(70L, second.balance)
        assertEquals(5L, second.lastRefreshed)
        assertEquals(listOf("zpub-b", "zpub-d", "zpub-e"), added.pubs.map { it.pubKey })
        assertEquals(listOf("beta-from-backup", "desk-from-backup", "Extra"), added.pubs.map { it.label })
        assertTrue(first.pubs.none { it.pubKey == "zpub-e" || it.pubKey == "zpub-d" })
        assertTrue(second.pubs.none { it.pubKey == "zpub-e" || it.pubKey == "zpub-b" })
    }

    @Test
    fun applyWithoutAnExplicitDecisionWritesNothing() = runBlocking {
        val alpha = key("zpub-a", "Alpha", balance = 10, fingerprint = "fp-a")
        val beta = key("zpub-b", "Beta", balance = 20, fingerprint = "fp-b")
        val before = install(
            collection("My Wallet", alpha, beta, balance = 30, lastRefreshed = 8, fromWallet = true)
        )
        val existingId = before.single().id
        val repo = repository()

        try {
            repo.applyCollectionImport(
                listOf(
                    collection("Paper", key("zpub-z", "Zed"), balance = 1, lastRefreshed = 2),
                    collection(
                        "My Wallet",
                        beta.copy(label = "from-backup"),
                        key("zpub-c", "Gamma"),
                        balance = 4, lastRefreshed = 5
                    ),
                )
            )
            fail("a partial overlap without a decision should not be written")
        } catch (expected: CollectionImportNeedsDecision) {
            assertEquals(1, expected.unresolved.size)
            assertEquals(listOf(existingId), expected.unresolved.single().candidateIds)
        }

        val memory = repo.collectionsSnapshot()
        assertEquals(listOf(existingId), memory.map { it.id })
        assertEquals(listOf("zpub-a", "zpub-b"), memory.single().pubs.map { it.pubKey })
        assertEquals(listOf("Alpha", "Beta"), memory.single().pubs.map { it.label })
        assertEquals(30L, memory.single().balance)

        val fresh = CollectionRepository()
        fresh.read()
        val restored = fresh.collectionsSnapshot()
        assertEquals(listOf(existingId), restored.map { it.id })
        assertEquals(listOf("zpub-a", "zpub-b"), restored.single().pubs.map { it.pubKey })
        assertEquals("Alpha", restored.single().pubs[0].label)
        assertTrue(restored.none { it.collectionLabel == "Paper" })
    }

    @Test
    fun explicitMergePersistsOnlyTheChosenCollection() = runBlocking {
        val alpha = key("zpub-a", "Alpha", balance = 10, fingerprint = "fp-a")
        val beta = key("zpub-b", "Beta", balance = 20, fingerprint = "fp-b")
        val cold = key("zpub-c", "Cold", balance = 30, fingerprint = "fp-c")
        val desk = key("zpub-d", "Desk", balance = 40, fingerprint = "fp-d")
        val before = install(
            collection("Savings", alpha, beta, balance = 30, lastRefreshed = 4, fromWallet = true),
            collection("Savings", cold, desk, balance = 70, lastRefreshed = 5),
        )
        val firstId = before[0].id
        val secondId = before[1].id
        val extra = key("zpub-e", "Extra", balance = 7, fingerprint = "fp-e")

        repository().applyCollectionImport(
            listOf(
                collection(
                    "Savings",
                    beta.copy(label = "beta-from-backup", balance = 1),
                    desk.copy(label = "desk-from-backup", balance = 1),
                    extra,
                )
            ),
            mapOf(0 to CollectionImportDecision.MergeInto(firstId)),
        )

        val fresh = CollectionRepository()
        fresh.read()
        val restored = fresh.collectionsSnapshot()
        assertEquals(2, restored.size)
        val first = restored.single { it.id == firstId }
        val second = restored.single { it.id == secondId }
        assertEquals(listOf("zpub-a", "zpub-b", "zpub-d", "zpub-e"), first.pubs.map { it.pubKey })
        assertEquals(listOf("Alpha", "Beta", "desk-from-backup", "Extra"), first.pubs.map { it.label })
        assertEquals("fp-a", first.pubs[0].fingerPrint)
        assertEquals(10L + 20L + 1L + 7L, first.balance)
        assertEquals(4L, first.lastRefreshed)
        assertTrue(first.isImportFromWallet)
        assertEquals(listOf("zpub-c", "zpub-d"), second.pubs.map { it.pubKey })
        assertEquals(listOf("Cold", "Desk"), second.pubs.map { it.label })
        assertEquals(70L, second.balance)
        assertTrue(second.pubs.none { it.pubKey == "zpub-e" || it.pubKey == "zpub-b" })
        assertTrue(first.pubs.none { it.pubKey == "zpub-c" })
    }

    @Test
    fun failedApplyDoesNotLeaveAPartialMerge() = runBlocking {
        val savings = key("zpub-savings", "Savings", balance = 1000, fingerprint = "fp-savings")
        val checking = key("zpub-checking", "Checking", balance = 200, fingerprint = "fp-checking")
        val before = install(
            collection(
                "My Wallet", savings, checking,
                balance = 1200, lastRefreshed = 42, fromWallet = true
            )
        )
        val existingId = before.single().id
        val dir = java.io.File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "app_wallet"
        )
        assertTrue(dir.isDirectory)
        assertTrue(dir.setWritable(false))
        try {
            try {
                repository().applyCollectionImport(
                    listOf(
                        collection(
                            "My Wallet",
                            savings.copy(),
                            checking.copy(),
                            key("zpub-hardware", "Hardware", balance = 25),
                        ),
                        collection("Paper", key("zpub-paper", "Paper"), balance = 3, lastRefreshed = 15),
                    )
                )
                fail("the import write should have failed")
            } catch (expected: PayloadReadException) {
            }
            val memory = repository().collectionsSnapshot()
            assertEquals(listOf(existingId), memory.map { it.id })
            assertEquals(listOf("zpub-savings", "zpub-checking"), memory.single().pubs.map { it.pubKey })
            assertEquals(listOf("Savings", "Checking"), memory.single().pubs.map { it.label })
            assertEquals(1200L, memory.single().balance)
            assertEquals(42L, memory.single().lastRefreshed)
        } finally {
            assertTrue(dir.setWritable(true))
        }

        val fresh = CollectionRepository()
        fresh.read()
        val restored = fresh.collectionsSnapshot()
        assertEquals(listOf(existingId), restored.map { it.id })
        assertEquals(listOf("zpub-savings", "zpub-checking"), restored.single().pubs.map { it.pubKey })
        assertTrue(restored.none { it.collectionLabel == "Paper" })
    }

    @Test
    fun applyRefusesWhenThePayloadIsNotLoaded() = runBlocking {
        val savings = key("zpub-savings", "Savings")
        val before = install(
            collection("My Wallet", savings, balance = 5, lastRefreshed = 3, fromWallet = true)
        )
        val existingId = before.single().id
        val unloaded = CollectionRepository()
        try {
            unloaded.applyCollectionImport(
                listOf(collection("Paper", key("zpub-paper", "Paper")))
            )
            fail("an import before the payload is loaded should be refused")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message?.contains("not in a known-good state") == true)
        }
        assertTrue(unloaded.collectionsSnapshot().isEmpty())
        val fresh = CollectionRepository()
        fresh.read()
        val restored = fresh.collectionsSnapshot()
        assertEquals(listOf(existingId), restored.map { it.id })
        assertEquals(listOf("My Wallet"), restored.map { it.collectionLabel })
        assertEquals(listOf("zpub-savings"), restored.single().pubs.map { it.pubKey })
    }

    private fun prefs(): PrefsUtil = inject(PrefsUtil::class.java).value as PrefsUtil

    private fun repository(): CollectionRepository =
        inject(CollectionRepository::class.java).value as CollectionRepository

    private suspend fun install(vararg collections: PubKeyCollection): ArrayList<PubKeyCollection> {
        val repo = repository()
        repo.reset()
        collections.forEach { repo.addNew(it) }
        repo.syncNow()
        return repo.collectionsSnapshot()
    }

    private suspend fun import(
        collections: ArrayList<PubKeyCollection>,
        replace: Boolean,
    ): ArrayList<PubKeyCollection> {
        ExportImportUtil().startImportCollections(collections, replace)
        return repository().collectionsSnapshot()
    }

    private fun collection(
        label: String,
        vararg keys: PubKeyModel,
        balance: Long = 0L,
        lastRefreshed: Long = 0L,
        fromWallet: Boolean = false,
    ): PubKeyCollection = PubKeyCollection(
        collectionLabel = label,
        balance = balance,
        lastRefreshed = lastRefreshed,
        isImportFromWallet = fromWallet,
        pubs = arrayListOf(*keys),
    )

    private fun key(
        pub: String,
        label: String,
        balance: Long = 0L,
        changeIndex: Int = 0,
        accountIndex: Int = 0,
        fingerprint: String = "",
        type: AddressTypes = AddressTypes.BIP84,
    ): PubKeyModel = PubKeyModel(
        pubKey = pub,
        label = label,
        type = type,
        balance = balance,
        change_index = changeIndex,
        account_index = accountIndex,
        fingerPrint = fingerprint,
    )
}
