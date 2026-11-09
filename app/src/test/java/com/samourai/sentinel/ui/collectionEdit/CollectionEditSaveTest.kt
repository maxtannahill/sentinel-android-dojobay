package com.samourai.sentinel.ui.collectionEdit

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
import org.robolectric.shadows.ShadowLooper

/**
 * Collection edit Save. The name field used to write the live collection
 * on every keystroke, and Save both persisted and re-emitted that object,
 * which rebound the screen. A tap with the field focused also dismissed
 * the keyboard; the default resize cancelled the click, so Save had to
 * be pressed again. Robolectric does not resize the window for the IME,
 * so the cancelled gesture itself is a device check. What is pinned here
 * is the contract that gesture has to land on: the draft stays local
 * until one Save, that Save persists once and finishes, Back drops it,
 * and keys are not duplicated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CollectionEditSaveTest {

    private lateinit var repository: CollectionRepository
    private var savedAdmitted = false

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() != null) {
            stopKoin()
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        savedAdmitted = AccessFactory.getInstance(app).isSessionAdmitted
        val access = AccessFactory.getInstance(app)
        access.admitSession()
        val prefs = PrefsUtil(app)
        prefs.pinHash = ""
        prefs.pinKeyMaterial = ""
        prefs.pinEnabled = false
        repository = CollectionRepository()
        PubKeyRequests.clear()
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                    single { access }
                    single { repository }
                    single { SentinelCollectionStore() }
                    single { DojoUtility() }
                    single { ApiService() }
                }
            )
        }
    }

    @After
    fun tearDown() {
        if (GlobalContext.getOrNull() != null) {
            stopKoin()
        }
        PubKeyRequests.clear()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val access = AccessFactory.getInstance(app)
        if (savedAdmitted) access.admitSession() else {
            val field = AccessFactory::class.java.getDeclaredField("sessionAdmitted")
            field.isAccessible = true
            field.setBoolean(null, false)
        }
    }

    @Test
    fun `soft input does not resize the edit window`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.packageManager.getActivityInfo(
            ComponentName(app, CollectionEditActivity::class.java),
            PackageManager.GET_META_DATA
        )
        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            info.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        )
    }

    @Test
    fun `typing a name does not publish it`() {
        val id = storedCollection()
        val activity = launch(id)
        val edit = activity.findViewById<EditText>(R.id.collectionEdiText)
        assertEquals("Cold storage", edit.text.toString())
        edit.requestFocus()
        edit.setText("Cold storage renamed")
        ShadowLooper.idleMainLooper()
        assertEquals("Cold storage", repository.findById(id)!!.collectionLabel)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `one save persists the name and closes`() {
        val id = storedCollection()
        val activity = launch(id)
        val edit = activity.findViewById<EditText>(R.id.collectionEdiText)
        edit.requestFocus()
        edit.setText("Cold storage renamed")
        assertTrue(tapSave(activity))
        ShadowLooper.idleMainLooper()
        assertTrue(activity.isFinishing)
        val stored = repository.findById(id)!!
        assertEquals("Cold storage renamed", stored.collectionLabel)
        assertEquals(listOf("zpub-1", "zpub-2"), stored.pubs.map { it.pubKey })
        assertEquals(1, repository.collectionsSnapshot().size)
    }

    @Test
    fun `back drops the draft name`() {
        val id = storedCollection()
        val activity = launch(id)
        activity.findViewById<EditText>(R.id.collectionEdiText).setText("not saved")
        activity.onBackPressed()
        ShadowLooper.idleMainLooper()
        assertTrue(activity.isFinishing)
        assertEquals("Cold storage", repository.findById(id)!!.collectionLabel)
        assertEquals(listOf("zpub-1", "zpub-2"), repository.findById(id)!!.pubs.map { it.pubKey })
    }

    @Test
    fun `double save of a new collection persists once`() {
        val activity = launchNew()
        activity.findViewById<EditText>(R.id.collectionEdiText).setText("Fresh wallet")
        assertTrue(tapSave(activity))
        assertTrue(tapSave(activity))
        ShadowLooper.idleMainLooper()
        assertTrue(activity.isFinishing)
        val stored = repository.collectionsSnapshot()
        assertEquals(1, stored.size)
        assertEquals("Fresh wallet", stored[0].collectionLabel)
        assertEquals(listOf("zpub-fresh"), stored[0].pubs.map { it.pubKey })
    }

    @Test
    fun `blank name can be corrected and then saved`() {
        val activity = launchNew()
        val edit = activity.findViewById<EditText>(R.id.collectionEdiText)
        edit.setText("   ")
        assertTrue(tapSave(activity))
        ShadowLooper.idleMainLooper()
        assertFalse(activity.isFinishing)
        assertEquals(0, repository.collectionsSnapshot().size)
        edit.setText("Named after")
        assertTrue(tapSave(activity))
        ShadowLooper.idleMainLooper()
        assertTrue(activity.isFinishing)
        val stored = repository.collectionsSnapshot()
        assertEquals(1, stored.size)
        assertEquals("Named after", stored[0].collectionLabel)
        assertEquals(listOf("zpub-fresh"), stored[0].pubs.map { it.pubKey })
    }

    @Test
    fun `blank save stays on the screen and keeps the stored name`() {
        val id = storedCollection()
        val activity = launch(id)
        activity.findViewById<EditText>(R.id.collectionEdiText).setText("   ")
        assertTrue(tapSave(activity))
        ShadowLooper.idleMainLooper()
        assertFalse(activity.isFinishing)
        assertNotEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals("Cold storage", repository.findById(id)!!.collectionLabel)
    }

    @Test
    fun `recreation before save keeps a new collection draft and imports once`() {
        val key = freshKey()
        val token = PubKeyRequests.put(key)
        val controller = controllerFor(Intent().putExtra(CollectionEditActivity.EXTRA_PUBKEY_TOKEN, token))
        ShadowLooper.idleMainLooper()
        val activity = controller.get()
        assertNull(PubKeyRequests.take(token))
        assertEquals(1, editor(activity).importsRequested)
        dismissSheets(activity)
        activity.findViewById<EditText>(R.id.collectionEdiText).setText("Fresh wallet")
        PubKeyRequests.putAt(token, key)

        val recreated = controller.recreate()
        ShadowLooper.idleMainLooper()
        val again = recreated.get()
        assertEquals("Fresh wallet", again.findViewById<EditText>(R.id.collectionEdiText).text.toString())
        assertEquals(listOf("zpub-fresh"), editor(again).getPubKeys().value!!.map { it.pubKey })
        assertTrue(PubKeyRequests.contains(token))
        assertEquals(1, editor(again).importsRequested)
        assertEquals(0, repository.collectionsSnapshot().size)

        assertTrue(tapSave(again))
        ShadowLooper.idleMainLooper()
        assertTrue(again.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(again).resultCode)
        val stored = repository.collectionsSnapshot()
        assertEquals(1, stored.size)
        assertEquals("Fresh wallet", stored[0].collectionLabel)
        assertEquals(listOf("zpub-fresh"), stored[0].pubs.map { it.pubKey })
    }

    @Test
    fun `recreation before save keeps an existing collection draft and its unsaved key`() {
        val id = storedCollection()
        val key = PubKeyModel(pubKey = "zpub-added", label = "New", type = AddressTypes.BIP84)
        val token = PubKeyRequests.put(key)
        val intent = Intent()
            .putExtra("collection", id)
            .putExtra(CollectionEditActivity.EXTRA_PUBKEY_TOKEN, token)
        val controller = controllerFor(intent)
        ShadowLooper.idleMainLooper()
        val activity = controller.get()
        assertNull(PubKeyRequests.take(token))
        assertEquals(1, editor(activity).importsRequested)
        activity.findViewById<EditText>(R.id.collectionEdiText).setText("Cold storage renamed")
        PubKeyRequests.putAt(token, key)

        val recreated = controller.recreate()
        ShadowLooper.idleMainLooper()
        val again = recreated.get()
        assertEquals(
            "Cold storage renamed",
            again.findViewById<EditText>(R.id.collectionEdiText).text.toString()
        )
        assertEquals(
            listOf("zpub-1", "zpub-2", "zpub-added"),
            editor(again).getPubKeys().value!!.map { it.pubKey }
        )
        assertTrue(PubKeyRequests.contains(token))
        assertEquals(1, editor(again).importsRequested)
        assertEquals("Cold storage", repository.findById(id)!!.collectionLabel)
        assertEquals(listOf("zpub-1", "zpub-2"), repository.findById(id)!!.pubs.map { it.pubKey })

        assertTrue(tapSave(again))
        ShadowLooper.idleMainLooper()
        assertTrue(again.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(again).resultCode)
        assertEquals(1, repository.collectionsSnapshot().size)
        val stored = repository.findById(id)!!
        assertEquals("Cold storage renamed", stored.collectionLabel)
        assertEquals(listOf("zpub-1", "zpub-2", "zpub-added"), stored.pubs.map { it.pubKey })
    }

    @Test
    fun `process death reopens an existing collection without the unsent key`() {
        val id = storedCollection()
        val key = PubKeyModel(pubKey = "zpub-added", label = "New", type = AddressTypes.BIP84)
        val token = PubKeyRequests.put(key)
        val intent = Intent()
            .putExtra("collection", id)
            .putExtra(CollectionEditActivity.EXTRA_PUBKEY_TOKEN, token)
        val controller = controllerFor(intent)
        ShadowLooper.idleMainLooper()
        val activity = controller.get()
        assertEquals(
            listOf("zpub-1", "zpub-2", "zpub-added"),
            editor(activity).getPubKeys().value!!.map { it.pubKey }
        )
        assertEquals(1, editor(activity).importsRequested)
        activity.findViewById<EditText>(R.id.collectionEdiText).setText("Cold storage renamed")
        val saved = Bundle()
        controller.saveInstanceState(saved).pause().stop().destroy()

        val restarted = Robolectric.buildActivity(CollectionEditActivity::class.java, intent)
            .setup(saved)
            .get()
        ShadowLooper.idleMainLooper()
        assertFalse(restarted.isFinishing)
        assertEquals(
            listOf("zpub-1", "zpub-2"),
            editor(restarted).getPubKeys().value!!.map { it.pubKey }
        )
        assertEquals(0, editor(restarted).importsRequested)
        assertNull(PubKeyRequests.take(token))
        assertEquals("Cold storage", repository.findById(id)!!.collectionLabel)
        assertEquals(listOf("zpub-1", "zpub-2"), repository.findById(id)!!.pubs.map { it.pubKey })
    }

    @Test
    fun `save before the posted livedata values are delivered persists nothing`() {
        val viewModel = CollectionEditViewModel()
        val collection = PubKeyCollection()
        viewModel.setCollection(collection)
        viewModel.setPubKeys(arrayListOf(freshKey()))
        assertFalse(viewModel.save("Fresh wallet"))
        assertEquals(0, repository.collectionsSnapshot().size)
        assertEquals("", collection.collectionLabel)

        ShadowLooper.idleMainLooper()
        assertTrue(viewModel.save("Fresh wallet"))
        val stored = repository.collectionsSnapshot()
        assertEquals(1, stored.size)
        assertEquals("Fresh wallet", stored[0].collectionLabel)
        assertEquals(listOf("zpub-fresh"), stored[0].pubs.map { it.pubKey })
    }

    @Test
    fun `save with no keys stays open and a later save persists once`() {
        val activity = launch(Intent().putExtra("unused", "x"))
        assertNull(editor(activity).getPubKeys().value)
        val edit = activity.findViewById<EditText>(R.id.collectionEdiText)
        edit.setText("Fresh wallet")
        assertTrue(tapSave(activity))
        ShadowLooper.idleMainLooper()
        assertFalse(activity.isFinishing)
        assertNotEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals(0, repository.collectionsSnapshot().size)
        assertEquals("Fresh wallet", edit.text.toString())

        editor(activity).setPubKeys(arrayListOf(freshKey()))
        ShadowLooper.idleMainLooper()
        assertTrue(tapSave(activity))
        ShadowLooper.idleMainLooper()
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val stored = repository.collectionsSnapshot()
        assertEquals(1, stored.size)
        assertEquals("Fresh wallet", stored[0].collectionLabel)
        assertEquals(listOf("zpub-fresh"), stored[0].pubs.map { it.pubKey })
    }

    private fun storedCollection(): String {
        val collection = PubKeyCollection(
            collectionLabel = "Cold storage",
            pubs = arrayListOf(
                PubKeyModel(pubKey = "zpub-1", label = "Savings", type = AddressTypes.BIP84),
                PubKeyModel(pubKey = "zpub-2", label = "Travel", type = AddressTypes.BIP84)
            )
        )
        repository.addNew(collection)
        return collection.id
    }

    private fun launch(id: String): CollectionEditActivity {
        return launch(Intent().putExtra("collection", id))
    }

    // #484: the Intent carries only the single-use token. The old "pubKey"
    // Parcelable extra is not read, so handing the model that way leaves
    // the key list empty and save() stores nothing.
    private fun launchNew(): CollectionEditActivity {
        val key = freshKey()
        val token = PubKeyRequests.put(key)
        val activity = launch(
            Intent().putExtra(CollectionEditActivity.EXTRA_PUBKEY_TOKEN, token)
        )
        assertNull(PubKeyRequests.take(token))
        return activity
    }

    private fun freshKey(): PubKeyModel {
        return PubKeyModel(pubKey = "zpub-fresh", label = "Savings", type = AddressTypes.BIP84)
    }

    private fun controllerFor(intent: Intent): ActivityController<CollectionEditActivity> {
        return Robolectric.buildActivity(CollectionEditActivity::class.java, intent).setup()
    }

    private fun launch(intent: Intent): CollectionEditActivity {
        val activity = controllerFor(intent).get()
        ShadowLooper.idleMainLooper()
        assertFalse(activity.isFinishing)
        return activity
    }

    private fun editor(activity: CollectionEditActivity): CollectionEditViewModel {
        return ViewModelProvider(activity)[CollectionEditViewModel::class.java]
    }

    private fun dismissSheets(activity: CollectionEditActivity) {
        val manager = activity.supportFragmentManager
        manager.executePendingTransactions()
        manager.fragments.toList().forEach { fragment ->
            if (fragment is DialogFragment) {
                fragment.dismissAllowingStateLoss()
            }
        }
        ShadowLooper.idleMainLooper()
    }

    private fun tapSave(activity: CollectionEditActivity): Boolean {
        val item = shadowOf(activity).optionsMenu.findItem(R.id.saveCollectionMenuItem)
        return activity.onOptionsItemSelected(item)
    }
}
