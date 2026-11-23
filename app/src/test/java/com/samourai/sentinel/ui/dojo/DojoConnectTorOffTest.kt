package com.samourai.sentinel.ui.dojo

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.tor.TorState
import com.samourai.sentinel.ui.dojo.DojoBayTestVectors.ThrowawayPayNym
import com.samourai.sentinel.ui.utils.PrefsUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.robolectric.shadows.ShadowDialog
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Collections

/**
 * Tapping a cached Community Dojo row while Tor Routing is off must not
 * start Tor. The connect page shows a Turn on Tor step instead. Tapping
 * Turn on Tor enables Tor Routing and starts Tor once, re-checks routing
 * when Tor becomes ready, and continues the connect normally. With Tor
 * Routing on, the existing connect gate and #260/#261 latches are
 * unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DojoConnectTorOffTest {

    private lateinit var prefs: PrefsUtil
    private lateinit var app: Application
    private val listingOwner = ThrowawayPayNym(62)
    private val directoryBase = DojoBayConstants.BASE_URL
    private val operatorCode = DojoBayConstants.DEFAULT_OPERATOR_CODE
    private var startCount = 0

    @Before
    fun setUp() {
        GlobalContext.getOrNull()?.let { stopKoin() }
        app = ApplicationProvider.getApplicationContext()
        prefs = PrefsUtil(app)
        prefs.clearAll()
        prefs.testnet = true
        prefs.enableTor = false
        prefs.dojoBayUrl = null
        prefs.dojoBayPinnedCode = null
        CommunityDojoCache.forDirectory(app.noBackupFilesDir).clear()
        setTor(EnumTorState.OFF, 0, proxy = null)
        startCount = 0
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
        setTor(EnumTorState.OFF, 0, proxy = null)
        prefs.enableTor = false
        CommunityDojoCache.forDirectory(app.noBackupFilesDir).clear()
        SentinelTorManager::class.java.getDeclaredField("runtime").apply {
            isAccessible = true
            set(SentinelTorManager, null)
        }
        GlobalContext.getOrNull()?.let { stopKoin() }
    }

    @Test
    fun `cached-row tap with Tor Routing off does not start Tor and shows Turn on Tor on connect page`() {
        writeCachedListing()
        val (sheet, list) = openListWithCache()

        clickCachedRow(list)
        val dialog = prePairDialog()
        val page = connectPage(sheet)

        assertEquals(0, startCount)
        assertEquals(false, prefs.enableTor)
        assertEquals(EnumTorState.OFF, SentinelTorManager.getTorState().state)
        assertNull(runtime())
        assertEquals(2, pager(sheet).currentItem)

        assertEquals(View.VISIBLE, torOff(page).visibility)
        assertEquals(View.GONE, steps(page).visibility)
        assertEquals("Tor is not connected", torOffTitle(page).text.toString())
        assertTrue(torOffBody(page).text.toString().contains("only reachable over Tor"))
        val btn = enableTor(page)
        assertEquals(View.VISIBLE, btn.visibility)
        assertEquals("Turn on Tor", btn.text.toString())
        assertNull(btn.transformationMethod)

        dialog.onBackPressed()
        idle()
    }

    @Test
    fun `cached-row tap with STARTING and routing off does not restart Tor and shows Turn on Tor`() {
        setTor(EnumTorState.STARTING, 40, proxy = null)
        writeCachedListing()
        val (sheet, list) = openListWithCache()

        clickCachedRow(list)
        val dialog = prePairDialog()
        val page = connectPage(sheet)

        assertEquals(0, startCount)
        assertEquals(false, prefs.enableTor)
        assertEquals(EnumTorState.STARTING, SentinelTorManager.getTorState().state)
        assertEquals(40, SentinelTorManager.getTorState().progressIndicator)
        assertEquals(View.VISIBLE, torOff(page).visibility)
        assertEquals(View.GONE, steps(page).visibility)

        dialog.onBackPressed()
        idle()
    }

    @Test
    fun `Pair then Turn on Tor starts Tor once and continues connect when Tor is ready`() {
        val server = SocksLoginStub()
        try {
            writeCachedListing()
            val (sheet, list) = openListWithCache()

            clickCachedRow(list)
            val dialog = prePairDialog()
            assertEquals(0, startCount)

            // Confirm Pair while Tor Routing is still off: list closes,
            // connect page stays open on the Turn on Tor step, no network call.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()

            assertTrue(sheet.isAdded && sheet.dialog?.isShowing == true)
            assertFalse(list.dialog?.isShowing == true)
            val page = connectPage(sheet)
            assertEquals(View.VISIBLE, torOff(page).visibility)
            assertEquals(View.GONE, steps(page).visibility)
            assertEquals(0, startCount)
            assertEquals(0, server.loginRequests)

            // Tap Turn on Tor: enables pref and calls start() once.
            enableTor(page).performClick()
            idle()

            assertEquals(true, prefs.enableTor)
            assertEquals(1, startCount)
            assertEquals(EnumTorState.STARTING, SentinelTorManager.getTorState().state)
            assertEquals(View.GONE, torOff(page).visibility)
            assertEquals(View.VISIBLE, steps(page).visibility)
            assertEquals(View.VISIBLE, page.requireView().findViewById<View>(R.id.progressTor).visibility)
            assertEquals(0, server.loginRequests)

            // Tor becomes ready: green Tor tick renders and /auth/login proceeds.
            setTor(EnumTorState.ON, 100, proxy = server.proxy())
            waitUntil("login request sent and receipt shown") {
                server.loginRequests == 1 &&
                    ShadowDialog.getLatestDialog().let { it is AlertDialog && it.isShowing && it !== dialog }
            }

            assertEquals(1, startCount)
            assertEquals(View.VISIBLE, page.requireView().findViewById<View>(R.id.checkImageTor).visibility)
            assertEquals(View.VISIBLE, page.requireView().findViewById<View>(R.id.checkImageDojo).visibility)
            val dojo = GlobalContext.get().get<DojoUtility>()
            assertTrue(dojo.isDojoEnabled())

            val receipt = ShadowDialog.getLatestDialog() as AlertDialog
            receipt.dismiss()
            idle()
        } finally {
            server.close()
        }
    }

    @Test
    fun `turning routing off while waiting after Turn on Tor drops the pending connect`() {
        val server = SocksLoginStub()
        try {
            writeCachedListing()
            val (sheet, list) = openListWithCache()

            clickCachedRow(list)
            val dialog = prePairDialog()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()

            val page = connectPage(sheet)
            enableTor(page).performClick()
            idle()
            assertEquals(1, startCount)
            assertEquals(true, prefs.enableTor)

            // User turns routing off before Tor becomes ready.
            prefs.enableTor = false
            setTor(EnumTorState.ON, 100, proxy = server.proxy())
            waitUntil("reverted to Turn on Tor step") { torOff(page).visibility == View.VISIBLE }

            assertEquals(0, server.loginRequests)
            assertEquals(View.GONE, steps(page).visibility)
            assertEquals(1, startCount)

            // Tapping Turn on Tor again starts Tor once and brings Tor up,
            // but does not auto-submit pairing because pairingConfirmedWaitingForTor was reset.
            enableTor(page).performClick()
            idle()
            assertEquals(2, startCount)
            assertEquals(true, prefs.enableTor)
            waitUntil("Tor checkmark visible without auto-login") {
                page.requireView().findViewById<View>(R.id.checkImageTor).visibility == View.VISIBLE
            }
            assertEquals(0, server.loginRequests)
            assertFalse(GlobalContext.get().get<DojoUtility>().isDojoEnabled())
        } finally {
            server.close()
        }
    }

    @Test
    fun `DON'T PAIR with routing off returns to list without starting Tor`() {
        writeCachedListing()
        val (sheet, list) = openListWithCache()

        clickCachedRow(list)
        val dialog = prePairDialog()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()

        assertEquals(0, startCount)
        assertEquals(false, prefs.enableTor)
        assertTrue(list.isAdded && list.dialog?.isShowing == true)
        assertEquals(0, pager(sheet).currentItem)
    }

    @Test
    fun `with Tor Routing on, cached-row tap keeps the existing connect and latch behaviour`() {
        val server = SocksLoginStub()
        try {
            prefs.enableTor = true
            setTor(EnumTorState.ON, 100, proxy = server.proxy())
            writeCachedListing()
            val (sheet, list) = openListWithCache()

            clickCachedRow(list)
            val dialog = prePairDialog()
            val page = connectPage(sheet)

            assertEquals(0, startCount)
            assertEquals(View.GONE, torOff(page).visibility)
            assertEquals(View.VISIBLE, steps(page).visibility)
            assertEquals(View.VISIBLE, page.requireView().findViewById<View>(R.id.checkImageTor).visibility)

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            waitUntil("login request sent with routing on") {
                server.loginRequests == 1 &&
                    ShadowDialog.getLatestDialog().let { it is AlertDialog && it.isShowing && it !== dialog }
            }
            (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()
            idle()
        } finally {
            server.close()
        }
    }

    @Test
    fun `with Tor Routing on and STARTING, cached-row tap shows Tor spinner and latches checkmark on ON`() {
        prefs.enableTor = true
        setTor(EnumTorState.STARTING, 25, proxy = null)
        writeCachedListing()
        val (sheet, list) = openListWithCache()

        clickCachedRow(list)
        val dialog = prePairDialog()
        val page = connectPage(sheet)

        assertEquals(View.GONE, torOff(page).visibility)
        assertEquals(View.VISIBLE, steps(page).visibility)
        assertEquals(View.VISIBLE, page.requireView().findViewById<View>(R.id.progressTor).visibility)
        assertEquals(View.INVISIBLE, page.requireView().findViewById<View>(R.id.checkImageTor).visibility)

        setTor(EnumTorState.ON, 100, proxy = null)
        idle()
        assertEquals(View.VISIBLE, page.requireView().findViewById<View>(R.id.checkImageTor).visibility)

        dialog.onBackPressed()
        idle()
    }

    private fun writeCachedListing() {
        val node = CommunityDojoNode(
            id = "cached" + listingOwner.code.takeLast(4),
            network = "testnet",
            name = "Cached Test Dojo",
            status = "active",
            paymentCode = listingOwner.code,
            payload = DojoBayTestVectors.testPayload(),
            signed = listingOwner.listing(DojoBayTestVectors.TEST_PAIRING_JSON),
        )
        assertNotNull(DojoBaySignatures.checkNode(node))
        CommunityDojoCache.forDirectory(app.noBackupFilesDir).write(
            source = directoryBase,
            nodes = listOf(node),
            fetchedAtMs = System.currentTimeMillis() - 60_000L,
            operatorCode = operatorCode,
        )
    }

    private fun openListWithCache(): Pair<DojoConfigureBottomSheet, CommunityDojoListBottomSheet> {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppThemeV3)
        val activity = controller.setup().get()
        val sheet = DojoConfigureBottomSheet.newInstance("dojo_connect_tor_off_test")
        sheet.show(activity.supportFragmentManager, "dojo_configure")
        idle()
        CommunityDojoListBottomSheet().show(
            sheet.childFragmentManager, DojoConfigureBottomSheet.COMMUNITY_DOJO_LIST_TAG
        )
        idle()
        val list = sheet.childFragmentManager
            .findFragmentByTag(DojoConfigureBottomSheet.COMMUNITY_DOJO_LIST_TAG) as CommunityDojoListBottomSheet
        waitUntil("cached row rendered") {
            val rv = list.requireView().findViewById<RecyclerView>(R.id.communityDojoRecyclerView)
            rv.visibility == View.VISIBLE && (rv.adapter?.itemCount ?: 0) > 0
        }
        return sheet to list
    }

    private fun clickCachedRow(list: CommunityDojoListBottomSheet) {
        val rv = list.requireView().findViewById<RecyclerView>(R.id.communityDojoRecyclerView)
        rv.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST),
        )
        rv.layout(0, 0, 1080, 1920)
        val vh = rv.findViewHolderForAdapterPosition(0)
        assertNotNull("cached row view holder must exist", vh)
        vh!!.itemView.performClick()
        idle()
    }

    private fun prePairDialog(): AlertDialog {
        val d = ShadowDialog.getLatestDialog()
        assertTrue("the pre-pair dialog is showing", d is AlertDialog && d.isShowing)
        return d as AlertDialog
    }

    private fun pager(sheet: DojoConfigureBottomSheet): ViewPager2 =
        sheet.requireView().findViewById(R.id.pager)

    private fun connectPage(sheet: DojoConfigureBottomSheet): DojoConnectFragment {
        waitUntil("connect page view created") {
            sheet.childFragmentManager.fragments
                .filterIsInstance<DojoConnectFragment>()
                .firstOrNull()
                ?.view != null
        }
        return sheet.childFragmentManager.fragments
            .filterIsInstance<DojoConnectFragment>()
            .first()
    }

    private fun steps(page: DojoConnectFragment): View =
        page.requireView().findViewById(R.id.dojoConnectSteps)

    private fun torOff(page: DojoConnectFragment): View =
        page.requireView().findViewById(R.id.dojoConnectTorOff)

    private fun torOffTitle(page: DojoConnectFragment): TextView =
        page.requireView().findViewById(R.id.dojoConnectTorOffTitle)

    private fun torOffBody(page: DojoConnectFragment): TextView =
        page.requireView().findViewById(R.id.dojoConnectTorOffBody)

    private fun enableTor(page: DojoConnectFragment): MaterialButton =
        page.requireView().findViewById(R.id.dojoConnectEnableTor)

    private fun waitUntil(desc: String, timeoutMs: Long = 8_000, pred: () -> Boolean) {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
        while (!pred()) {
            check(System.nanoTime() < deadlineNs) { "timed out waiting for $desc" }
            Thread.sleep(10)
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

    private fun setTor(state: EnumTorState, progress: Int, proxy: Proxy?) {
        val proxyField = SentinelTorManager::class.java.getDeclaredField("proxy").apply { isAccessible = true }
        proxyField.set(SentinelTorManager, proxy)
        val live = SentinelTorManager::class.java.getDeclaredField("torStateLiveDataPrivate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (live.get(SentinelTorManager) as MutableLiveData<TorState>).value =
            TorState().apply { this.state = state; progressIndicator = progress }
    }

    private class SocksLoginStub {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        @Volatile var loginRequests = 0
        private val worker = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { s ->
                        val rawIn = BufferedInputStream(s.getInputStream())
                        val input = DataInputStream(rawIn)
                        val out = s.getOutputStream()
                        // SOCKS5 greeting
                        input.readByte()
                        val nMethods = input.readUnsignedByte()
                        repeat(nMethods) { input.readByte() }
                        out.write(byteArrayOf(5, 0))
                        out.flush()
                        // SOCKS5 CONNECT
                        input.readByte(); input.readByte(); input.readByte()
                        when (input.readUnsignedByte()) {
                            1 -> { val b = ByteArray(4); input.readFully(b) }
                            3 -> { val len = input.readUnsignedByte(); val b = ByteArray(len); input.readFully(b) }
                            4 -> { val b = ByteArray(16); input.readFully(b) }
                        }
                        input.readUnsignedShort()
                        out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
                        out.flush()
                        // Read HTTP request headers + body
                        val headerBuf = StringBuilder()
                        var contentLength = 0
                        while (true) {
                            val line = readLine(rawIn) ?: break
                            if (line.isEmpty()) break
                            headerBuf.append(line).append('\n')
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                            }
                        }
                        if (contentLength > 0) {
                            val body = ByteArray(contentLength)
                            var read = 0
                            while (read < contentLength) {
                                val r = rawIn.read(body, read, contentLength - read)
                                if (r < 0) break
                                read += r
                            }
                        }
                        if (headerBuf.toString().contains("/auth/login")) {
                            loginRequests++
                        }
                        val json = """{"authorizations":{"access_token":"test-access","refresh_token":"test-refresh"}}"""
                        val bodyBytes = json.toByteArray(StandardCharsets.UTF_8)
                        val resp = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${bodyBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        out.write(resp.toByteArray(StandardCharsets.US_ASCII))
                        out.write(bodyBytes)
                        out.flush()
                    }
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true; start() }

        private fun readLine(input: BufferedInputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) {
                    if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.deleteCharAt(sb.length - 1)
                    return sb.toString()
                }
                sb.append(b.toChar())
            }
        }

        fun proxy(): Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", server.localPort))

        fun close() {
            server.close()
            worker.join(2_000)
        }
    }
}
