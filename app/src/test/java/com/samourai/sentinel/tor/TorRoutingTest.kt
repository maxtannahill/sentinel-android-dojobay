package com.samourai.sentinel.tor

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.ui.utils.PrefsUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TorRoutingTest {

    private lateinit var prefs: PrefsUtil

    @Before
    fun setUp() {
        prefs = PrefsUtil(ApplicationProvider.getApplicationContext())
        prefs.enableTor = false
        setTor(EnumTorState.OFF, 0, proxy = false)
    }

    @After
    fun tearDown() {
        prefs.enableTor = false
        setTor(EnumTorState.OFF, 0, proxy = false)
    }

    @Test
    fun `enable sets the same pref Settings uses`() {
        assertEquals(false, prefs.enableTor)
        TorRouting.enable(prefs)
        assertEquals(true, prefs.enableTor)
    }

    @Test
    fun `the list may use Tor when the setting is on, or when Tor is already on`() {
        assertFalse(TorRouting.directoryMayUseTor(prefs))

        prefs.enableTor = true
        assertTrue(TorRouting.directoryMayUseTor(prefs))

        prefs.enableTor = false
        setTor(EnumTorState.STARTING, 5, proxy = false)
        assertFalse(TorRouting.directoryMayUseTor(prefs))

        prefs.enableTor = true
        setTor(EnumTorState.STARTING, 5, proxy = false)
        assertTrue(TorRouting.directoryMayUseTor(prefs))

        prefs.enableTor = false
        setTor(EnumTorState.ON, 50, proxy = false)
        assertTrue(TorRouting.directoryMayUseTor(prefs))

        setTor(EnumTorState.ON, 100, proxy = true)
        assertTrue(TorRouting.directoryMayUseTor(prefs))

        setTor(EnumTorState.OFF, 0, proxy = false)
        assertFalse(TorRouting.directoryMayUseTor(prefs))

        setTor(EnumTorState.STOPPING, 0, proxy = false)
        assertFalse(TorRouting.directoryMayUseTor(prefs))
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
