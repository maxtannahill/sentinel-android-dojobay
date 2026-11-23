package com.samourai.sentinel.ui.settings

import android.app.Application
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.tor.TorState
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.webview.ExplorerRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * The Settings Tor button uses the same TorRouting.enable path as
 * Turn on Tor in the Community Dojos list: it sets the pref and
 * starts Tor once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NetworkActivityTorEnableTest {

    private lateinit var prefs: PrefsUtil
    private var controller: ActivityController<NetworkActivity>? = null
    private var savedAdmitted = false
    private var startCount = 0

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val access = AccessFactory.getInstance(app)
        savedAdmitted = access.isSessionAdmitted
        access.admitSession()
        prefs = PrefsUtil(app)
        prefs.pinHash = ""
        prefs.pinKeyMaterial = ""
        prefs.pinEnabled = false
        prefs.enableTor = false
        setTor(EnumTorState.OFF, 0, proxy = false)
        startCount = 0
        SentinelTorManager.onManualStart = {
            startCount++
            if (SentinelTorManager.getTorState().state == EnumTorState.OFF) {
                SentinelTorManager.publish(EnumTorState.STARTING, 0)
            }
        }
        KeyRegistrationBurst.markFinished()
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                    single { access }
                    single { CollectionRepository() }
                    single { SentinelCollectionStore() }
                    single { DojoUtility() }
                    single { ApiService() }
                    single { ExplorerRepository() }
                }
            )
        }
    }

    @After
    fun tearDown() {
        SentinelTorManager.onManualStart = null
        startCount = 0
        controller?.pause()?.stop()?.destroy()
        controller = null
        KeyRegistrationBurst.markFinished()
        setTor(EnumTorState.OFF, 0, proxy = false)
        prefs.enableTor = false
        SentinelTorManager::class.java.getDeclaredField("runtime").apply {
            isAccessible = true
            set(SentinelTorManager, null)
        }
        if (GlobalContext.getOrNull() != null) stopKoin()
        val access = AccessFactory.getInstance(ApplicationProvider.getApplicationContext<Application>())
        if (savedAdmitted) access.admitSession() else {
            val field = AccessFactory::class.java.getDeclaredField("sessionAdmitted")
            field.isAccessible = true
            field.setBoolean(null, false)
        }
    }

    @Test
    fun `tapping the Settings Tor button enables the pref and starts Tor once`() {
        controller = Robolectric.buildActivity(NetworkActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller!!.get()

        assertEquals(false, prefs.enableTor)
        assertEquals(0, startCount)
        assertEquals(EnumTorState.OFF, SentinelTorManager.getTorState().state)

        activity.findViewById<android.view.View>(R.id.networking_tor_btn).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(true, prefs.enableTor)
        assertEquals(1, startCount)
        assertEquals(EnumTorState.STARTING, SentinelTorManager.getTorState().state)
        assertNull(runtime())
    }

    private fun runtime(): Any? {
        val f = SentinelTorManager::class.java.getDeclaredField("runtime").apply { isAccessible = true }
        return f.get(SentinelTorManager)
    }

    private fun setTor(state: EnumTorState, progress: Int, proxy: Boolean) {
        val live = SentinelTorManager::class.java.getDeclaredField("torStateLiveDataPrivate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (live.get(SentinelTorManager) as MutableLiveData<TorState>).value =
            TorState().apply { this.state = state; progressIndicator = progress }
        val proxyField = SentinelTorManager::class.java.getDeclaredField("proxy").apply { isAccessible = true }
        proxyField.set(
            SentinelTorManager,
            if (proxy) Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9)) else null,
        )
    }
}
