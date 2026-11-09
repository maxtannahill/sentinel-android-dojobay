package com.samourai.sentinel.ui.collectionEdit

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.view.WindowManager
import android.widget.EditText
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
        assertEquals("Cold storage", repository.findById(id)!!.collectionLabel)
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
        val key = PubKeyModel(pubKey = "zpub-fresh", label = "Savings", type = AddressTypes.BIP84)
        val token = PubKeyRequests.put(key)
        val activity = launch(
            Intent().putExtra(CollectionEditActivity.EXTRA_PUBKEY_TOKEN, token)
        )
        assertNull(PubKeyRequests.take(token))
        return activity
    }

    private fun launch(intent: Intent): CollectionEditActivity {
        val activity = Robolectric.buildActivity(CollectionEditActivity::class.java, intent)
            .setup()
            .get()
        ShadowLooper.idleMainLooper()
        assertFalse(activity.isFinishing)
        return activity
    }

    private fun tapSave(activity: CollectionEditActivity): Boolean {
        val item = shadowOf(activity).optionsMenu.findItem(R.id.saveCollectionMenuItem)
        return activity.onOptionsItemSelected(item)
    }
}
