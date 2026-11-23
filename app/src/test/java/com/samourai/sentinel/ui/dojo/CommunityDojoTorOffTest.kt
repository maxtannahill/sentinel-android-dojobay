package com.samourai.sentinel.ui.dojo

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.tor.TorState
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
import java.net.InetSocketAddress
import java.time.Duration
import java.net.Proxy

/**
 * Opening Community Dojos with Tor Routing off must not start Tor. The
 * sheet explains that, and TURN ON TOR enables the same pref Settings
 * uses and starts Tor once. Retry while off still does not start Tor.
 * STARTING with routing off is treated as off; STARTING with routing
 * on waits, then fetches. If routing is turned off while Tor stays ON,
 * a pending fetch is dropped. With Tor on, the existing wait-or-fetch
 * path is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunityDojoTorOffTest {

    private lateinit var prefs: PrefsUtil
    private var startCount = 0

    @Before
    fun setUp() {
        GlobalContext.getOrNull()?.let { stopKoin() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        prefs = PrefsUtil(app)
        prefs.clearAll()
        prefs.testnet = true
        prefs.enableTor = false
        setTor(EnumTorState.OFF, 0, proxy = false)
        startCount = 0
        // In production, start() with a live runtime publishes STARTING.
        // These JVM tests leave the runtime null so setUp does not build
        // a kmp-tor daemon; the hook reproduces that STARTING publish so
        // Turn on Tor is one start(), as on a device.
        SentinelTorManager.onManualStart = {
            startCount++
            if (SentinelTorManager.getTorState().state == EnumTorState.OFF) {
                SentinelTorManager.publish(EnumTorState.STARTING, 0)
            }
        }
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                    single { CollectionRepository() }
                    single { ApiService() }
                    single { SentinelCollectionStore() }
                    single { DojoUtility() }
                }
            )
        }
    }

    @After
    fun tearDown() {
        SentinelTorManager.onManualStart = null
        startCount = 0
        setTor(EnumTorState.OFF, 0, proxy = false)
        prefs.enableTor = false
        SentinelTorManager::class.java.getDeclaredField("runtime").apply {
            isAccessible = true
            set(SentinelTorManager, null)
        }
        GlobalContext.getOrNull()?.let { stopKoin() }
    }

    @Test
    fun `opening the list with Tor Routing off does not start Tor and shows the off state`() {
        val list = openList()
        waitUntil("off state") { torOff(list).visibility == View.VISIBLE }

        assertEquals(false, prefs.enableTor)
        assertEquals(EnumTorState.OFF, SentinelTorManager.getTorState().state)
        assertNull(runtime())
        assertFalse(SentinelTorManager.isTorReady())

        assertEquals(View.VISIBLE, torOff(list).visibility)
        assertEquals("Tor is not connected", torOffTitle(list).text.toString())
        assertTrue(
            torOffBody(list).text.toString(),
            torOffBody(list).text.toString().contains("Dojo Bay's directory is only reachable over Tor"),
        )
        val enable = enableTor(list)
        assertEquals(View.VISIBLE, enable.visibility)
        assertEquals("Turn on Tor", enable.text.toString())
        assertEquals(View.GONE, list.requireView().findViewById<View>(R.id.communityDojoProgress).visibility)
        assertEquals(View.GONE, retry(list).visibility)
    }

    @Test
    fun `Retry while Tor Routing is off still does not start Tor`() {
        val list = openList()
        waitUntil("off state") { torOff(list).visibility == View.VISIBLE }
        // Retry and the banner Retry both call connectTorThenFetch, which
        // must not start Tor while the setting is off.
        CommunityDojoListBottomSheet::class.java.getDeclaredMethod("connectTorThenFetch", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(list, true)
        idle()
        waitUntil("still off") { torOff(list).visibility == View.VISIBLE }

        assertEquals(false, prefs.enableTor)
        assertEquals(EnumTorState.OFF, SentinelTorManager.getTorState().state)
        assertNull(runtime())
        assertEquals("Tor is not connected", torOffTitle(list).text.toString())
        assertEquals("Turn on Tor", enableTor(list).text.toString())
    }

    @Test
    fun `TURN ON TOR enables the setting and starts Tor once`() {
        val list = openList()
        waitUntil("off state") { torOff(list).visibility == View.VISIBLE }
        enableTor(list).performClick()
        idle()

        assertEquals(true, prefs.enableTor)
        assertEquals(1, startCount)
        assertEquals(EnumTorState.STARTING, SentinelTorManager.getTorState().state)
        assertNull(runtime())
        assertEquals(View.GONE, torOff(list).visibility)
        waitUntil("waiting for Tor") {
            status(list).contains("Waiting for Tor") ||
                list.requireView().findViewById<View>(R.id.communityDojoProgress).visibility == View.VISIBLE
        }
        list.dismiss()
        idle()
    }

    @Test
    fun `STARTING with routing off does not fetch or restart Tor`() {
        setTor(EnumTorState.STARTING, 40, proxy = false)
        val list = openList()
        waitUntil("off state") { torOff(list).visibility == View.VISIBLE }

        assertEquals(false, prefs.enableTor)
        assertEquals(0, startCount)
        assertEquals(EnumTorState.STARTING, SentinelTorManager.getTorState().state)
        assertEquals(40, SentinelTorManager.getTorState().progressIndicator)
        assertNull(runtime())
        assertEquals("Tor is not connected", torOffTitle(list).text.toString())
        assertEquals("Turn on Tor", enableTor(list).text.toString())
        assertEquals(View.GONE, list.requireView().findViewById<View>(R.id.communityDojoProgress).visibility)
        list.dismiss()
        idle()
    }

    @Test
    fun `STARTING with routing on waits, then fetches when Tor is ready`() {
        prefs.enableTor = true
        setTor(EnumTorState.STARTING, 40, proxy = false)
        val list = openList()
        waitUntil("waiting for Tor") {
            status(list).contains("Waiting for Tor") ||
                list.requireView().findViewById<View>(R.id.communityDojoProgress).visibility == View.VISIBLE
        }
        assertEquals(View.GONE, torOff(list).visibility)
        assertEquals(0, startCount)
        assertEquals(EnumTorState.STARTING, SentinelTorManager.getTorState().state)

        // Set the proxy before publishing ON so awaitTorReady can
        // return on its first check, then let the main dispatcher run.
        setTor(EnumTorState.ON, 100, proxy = true)
        waitUntil("fetch started after Tor became ready") {
            val text = status(list)
            text.contains("Connecting via Tor") ||
                (torOff(list).visibility == View.GONE && !text.contains("Waiting for Tor"))
        }
        assertEquals(View.GONE, torOff(list).visibility)
        assertEquals(0, startCount)
        assertFalse(status(list).contains("Waiting for Tor"))
        list.dismiss()
        idle()
    }

    @Test
    fun `turning routing off while Tor stays ON drops the pending fetch`() {
        prefs.enableTor = true
        setTor(EnumTorState.STARTING, 40, proxy = false)
        val list = openList()
        waitUntil("waiting for Tor") {
            status(list).contains("Waiting for Tor") ||
                list.requireView().findViewById<View>(R.id.communityDojoProgress).visibility == View.VISIBLE
        }
        assertEquals(View.GONE, torOff(list).visibility)

        prefs.enableTor = false
        setTor(EnumTorState.ON, 100, proxy = true)
        waitUntil("off state after toggle") { torOff(list).visibility == View.VISIBLE }

        assertEquals(false, prefs.enableTor)
        assertEquals(0, startCount)
        assertTrue(SentinelTorManager.isTorReady())
        assertEquals("Tor is not connected", torOffTitle(list).text.toString())
        assertFalse(status(list).contains("Connecting via Tor"))
        list.dismiss()
        idle()
    }

    @Test
    fun `with Tor on the list follows the existing fetch path`() {
        prefs.enableTor = true
        setTor(EnumTorState.ON, 100, proxy = true)
        val list = openList()
        waitUntil("connecting") {
            val text = status(list)
            text.contains("Connecting via Tor") || text.contains("Waiting for Tor") ||
                list.requireView().findViewById<View>(R.id.communityDojoProgress).visibility == View.VISIBLE
        }
        assertEquals(View.GONE, torOff(list).visibility)
    }

    private fun openList(): CommunityDojoListBottomSheet {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppThemeV3)
        val activity = controller.setup().get()
        val sheet = DojoConfigureBottomSheet.newInstance("tor_off_test")
        sheet.show(activity.supportFragmentManager, "dojo_configure")
        idle()
        CommunityDojoListBottomSheet().show(
            sheet.childFragmentManager, DojoConfigureBottomSheet.COMMUNITY_DOJO_LIST_TAG
        )
        idle()
        val list = sheet.childFragmentManager
            .findFragmentByTag(DojoConfigureBottomSheet.COMMUNITY_DOJO_LIST_TAG) as CommunityDojoListBottomSheet
        assertTrue(list.dialog?.isShowing == true)
        return list
    }

    private fun status(list: CommunityDojoListBottomSheet): String =
        list.requireView().findViewById<TextView>(R.id.communityDojoStatusText).text.toString()

    private fun retry(list: CommunityDojoListBottomSheet): MaterialButton =
        list.requireView().findViewById(R.id.communityDojoRetryButton)

    private fun torOff(list: CommunityDojoListBottomSheet): View =
        list.requireView().findViewById(R.id.communityDojoTorOff)

    private fun torOffTitle(list: CommunityDojoListBottomSheet): TextView =
        list.requireView().findViewById(R.id.communityDojoTorOffTitle)

    private fun torOffBody(list: CommunityDojoListBottomSheet): TextView =
        list.requireView().findViewById(R.id.communityDojoTorOffBody)

    private fun enableTor(list: CommunityDojoListBottomSheet): MaterialButton =
        list.requireView().findViewById(R.id.communityDojoEnableTor)

    private fun waitUntil(desc: String, timeoutMs: Long = 8_000, pred: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!pred()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $desc" }
            // awaitTorReady uses delay(250) on the main dispatcher, which
            // only advances when the looper's scheduled time moves.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            idle()
        }
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun runtime(): Any? {
        val f = SentinelTorManager::class.java.getDeclaredField("runtime").apply { isAccessible = true }
        return f.get(SentinelTorManager)
    }

    private fun setTor(state: EnumTorState, progress: Int, proxy: Boolean) {
        // Publish the proxy first: LiveData observers run synchronously
        // and awaitTorReady reads both the state and the proxy.
        val proxyField = SentinelTorManager::class.java.getDeclaredField("proxy").apply { isAccessible = true }
        proxyField.set(
            SentinelTorManager,
            if (proxy) Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9)) else null,
        )
        val live = SentinelTorManager::class.java.getDeclaredField("torStateLiveDataPrivate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (live.get(SentinelTorManager) as MutableLiveData<TorState>).value =
            TorState().apply { this.state = state; progressIndicator = progress }
    }
}
