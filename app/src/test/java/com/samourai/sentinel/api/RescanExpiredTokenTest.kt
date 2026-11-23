package com.samourai.sentinel.api

import android.app.Application
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.tor.TorState
import com.samourai.sentinel.ui.dojo.DojoPairing
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.dojo.PubKeyRescanner
import com.samourai.sentinel.ui.utils.PrefsUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.koin.java.KoinJavaComponent
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import timber.log.Timber
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Rescan Xpub (and the other Dojo calls that opt out of TokenAuthenticator)
 * used to send the current access token and then give up when Dojo rejected
 * it as expired. Dojo returns HTTP 401 with
 * `{"status":"error","error":"Invalid JSON Web Token"}` for an expired JWT
 * (authorizations-manager isAuthenticated / _verifyAccessToken, HTTP server
 * onError). After this fix the login is refreshed once and the call is
 * resent, and Rescan Xpub refreshes once before a multi-key loop.
 *
 * The Dojo is [MockWebServer] on loopback. A pairing payload is installed
 * so the access token is actually attached, and the Tor gate is pointed at
 * [Proxy.NO_PROXY] so the test stays on loopback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RescanExpiredTokenTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: PrefsUtil
    private lateinit var api: ApiService
    private val seen = CopyOnWriteArrayList<Hit>()
    private val logs = CopyOnWriteArrayList<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += message
            t?.message?.let { logs += it }
        }
    }

    private data class Hit(val method: String, val path: String, val body: String)

    @Before
    fun setUp() {
        Timber.plant(tree)
        logs.clear()
        seen.clear()
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        if (GlobalContext.getOrNull() == null) {
            val app = ApplicationProvider.getApplicationContext<Application>()
            prefs = PrefsUtil(app)
            prefs.clearAll()
            prefs.enableTor = false
            prefs.testnet = false
            startKoin {
                androidContext(app)
                modules(
                    module {
                        single { prefs }
                        single { SentinelCollectionStore() }
                        single { CollectionRepository() }
                        single { DojoUtility() }
                        single { ApiService() }
                    },
                )
            }
            startedKoin = true
        }
        // A new test instance is created for every method. Re-resolve the
        // process-wide Koin singletons so PubKeyRescanner and this test
        // share the same ApiService and prefs.
        prefs = KoinJavaComponent.get(PrefsUtil::class.java)
        api = KoinJavaComponent.get(ApiService::class.java)
        shadowOf(Looper.getMainLooper()).idle()
        prefs.authorization = ""
        prefs.refreshToken = ""
        prefs.enableTor = false
        prefs.testnet = false
        if (this::api.isInitialized) {
            api.beforeNewCall = null
            api.afterCall = null
            api.onResponseClosed = null
            runCatching { api.client.connectionPool.evictAll() }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        if (this::api.isInitialized) {
            api.beforeNewCall = null
            api.afterCall = null
            api.onResponseClosed = null
            runCatching { api.client.connectionPool.evictAll() }
        }
        restorePairingGate()
        Timber.uproot(tree)
        assertNoSecretsInLogs()
        server.shutdown()
    }

    @Test
    fun `isExpiredDojoJwt matches Dojo's 401 envelope and nothing else`() {
        assertTrue(api.isExpiredDojoJwt(http(401, DOJO_EXPIRED)))
        assertTrue(api.isExpiredDojoJwt(http(401, """{ "status" : "error", "error" : "Invalid JSON Web Token" }""")))
        assertFalse(api.isExpiredDojoJwt(http(401, """{"status":"error","error":"Missing JSON Web Token"}""")))
        assertFalse(api.isExpiredDojoJwt(http(403, DOJO_EXPIRED)))
        assertFalse(api.isExpiredDojoJwt(http(200, """{"status":"ok"}""")))
        assertFalse(api.isExpiredDojoJwt(http(500, """{"status":"error","error":{"status":"Server error"}}""")))
        assertFalse(api.isExpiredDojoJwt(http(401, """{"status":"ok","error":"Invalid JSON Web Token"}""")))
        assertFalse(api.isExpiredDojoJwt(http(401, "<html>Invalid JSON Web Token</html>")))
        assertFalse(api.isExpiredDojoJwt(http(401, """{"status":"error","error":"Invalid JSON Web Token","extra":1}""")))
        try {
            assertFalse(api.isExpiredDojoJwt(http(401, "{")))
            assertFalse(api.isExpiredDojoJwt(http(401, "not-json Invalid JSON Web Token")))
        } catch (t: Throwable) {
            fail("a malformed 401 body must not throw, got ${t.javaClass.simpleName}")
        }
    }

    @Test
    fun `an expired token is refreshed once and rescanPubKey succeeds`() = withPairedDojo {
        seedExpiredToken()
        serveAuthThen { req ->
            if (isXpub(req) && hasFresh(req)) ok()
            else if (isXpub(req)) expired()
            else error("unexpected ${req.path}")
        }
        val response = runBlocking { api.rescanPubKey(XPUB, AddressTypes.BIP84) }
        try {
            assertEquals(200, response.code)
            assertEquals("ok", com.samourai.sentinel.helpers.SafeJson.parseObject(response.body!!.string()).optString("status"))
        } finally {
            response.close()
        }
        assertEquals(1, authCount())
        assertEquals(2, xpubCount())
        assertTrue(xpubHits().first().path.contains("at=$STALE"))
        assertTrue(xpubHits().last().path.contains("at=$FRESH"))
    }

    @Test
    fun `an expired token is refreshed once and checkImportStatus succeeds`() = withPairedDojo {
        seedExpiredToken()
        serveAuthThen { req ->
            if (isImportStatus(req) && hasFresh(req)) ok()
            else if (isImportStatus(req)) expired()
            else error("unexpected ${req.path}")
        }
        assertTrue(runBlocking { api.checkImportStatus(XPUB) })
        assertEquals(1, authCount())
        assertEquals(2, seen.count { isImportStatus(it) })
    }

    @Test
    fun `an expired token is refreshed once and importXpub succeeds`() = withPairedDojo {
        seedExpiredToken()
        serveAuthThen { req ->
            if (isXpub(req) && hasFresh(req)) ok()
            else if (isXpub(req)) expired()
            else error("unexpected ${req.path}")
        }
        runBlocking { api.importXpub(XPUB, "bip84") }.use { assertEquals(200, it.code) }
        assertEquals(1, authCount())
        assertEquals(2, xpubCount())
    }

    @Test
    fun `an expired token is refreshed once and importAddress succeeds`() = withPairedDojo {
        seedExpiredToken()
        serveAuthThen { req ->
            if (isXpub(req) && hasFresh(req)) ok()
            else if (isXpub(req)) expired()
            else error("unexpected ${req.path}")
        }
        runBlocking { api.importAddress("tb1q-test-address") }.use { assertEquals(200, it.code) }
        assertEquals(1, authCount())
        assertEquals(2, xpubCount())
    }

    @Test
    fun `an expired token is refreshed once and getTxHex succeeds`() = withPairedDojo {
        seedExpiredToken()
        serveAuthThen { req ->
            if (isTxHex(req) && hasFresh(req)) MockResponse().setResponseCode(200).setBody("""{"data":"00"}""")
            else if (isTxHex(req)) expired()
            else error("unexpected ${req.path}")
        }
        runBlocking { api.getTxHex("ab".repeat(32)) }.use { assertEquals(200, it.code) }
        assertEquals(1, authCount())
        assertEquals(2, seen.count { isTxHex(it) })
    }

    @Test
    fun `a six-key rescan refreshes the login once, not once per key`() = withPairedDojo {
        seedExpiredToken()
        serveAuthThen { req ->
            if (isXpub(req) && hasFresh(req)) ok()
            else if (isXpub(req)) expired()
            else error("unexpected ${req.path}")
        }
        val outcome = runBlocking { PubKeyRescanner.rescanKeys(SIX_KEYS) }
        assertEquals(6, outcome.succeeded)
        assertEquals(0, outcome.stillRunning)
        assertTrue(outcome.failures.isEmpty())
        assertEquals(1, authCount())
        // Pre-refresh used the login; the six POST /xpub calls all carried
        // the new token and did not each trigger another 401.
        assertEquals(6, xpubCount())
        assertTrue(xpubHits().all { it.path.contains("at=$FRESH") })
    }

    @Test
    fun `a second rejection after refresh is returned and does not loop`() = withPairedDojo {
        seedExpiredToken()
        serveAuthThen { expired() }
        val response = runBlocking { api.rescanPubKey(XPUB, AddressTypes.BIP44) }
        try {
            assertEquals(401, response.code)
            assertTrue(api.isExpiredDojoJwt(response))
        } finally {
            response.close()
        }
        assertEquals(1, authCount())
        assertEquals(2, xpubCount())
    }

    @Test
    fun `a six-key rescan that is still rejected after refresh fails each key once`() = withPairedDojo {
        seedExpiredToken()
        serveAuthThen { expired() }
        val outcome = runBlocking { PubKeyRescanner.rescanKeys(SIX_KEYS) }
        assertEquals(0, outcome.succeeded)
        assertEquals(6, outcome.failures.size)
        assertEquals(
            SIX_KEYS.map { "${it.label}: ${ApiService.DOJO_INVALID_JWT}" },
            outcome.failures,
        )
        // One pre-refresh, then each key retries once (another refresh +
        // resend) and stops. No third POST per key.
        assertEquals(7, authCount())
        assertEquals(12, xpubCount())
    }

    @Test
    fun `a failed refresh surfaces the original error and does not resend`() = withPairedDojo {
        seedExpiredToken()
        server.dispatcher = recording { req ->
            when {
                req.path.orEmpty().startsWith("/auth/") ->
                    MockResponse().setResponseCode(401).setBody(DOJO_EXPIRED)
                isXpub(req) -> expired()
                else -> error("unexpected ${req.path}")
            }
        }
        val response = runBlocking { api.rescanPubKey(XPUB, AddressTypes.BIP49) }
        try {
            assertEquals(401, response.code)
            assertTrue(api.isExpiredDojoJwt(response))
        } finally {
            response.close()
        }
        assertEquals(1, xpubCount())
    }

    @Test
    fun `a transport failure is not retried`() = withPairedDojo {
        seedExpiredToken()
        server.dispatcher = recording { req ->
            when {
                req.path.orEmpty().startsWith("/auth/") ->
                    error("refresh must not run on a transport failure")
                isXpub(req) -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                else -> error("unexpected ${req.path}")
            }
        }
        try {
            runBlocking { api.rescanPubKey(XPUB, AddressTypes.BIP84) }.close()
            org.junit.Assert.fail("expected a transport failure")
        } catch (_: java.io.IOException) {
        }
        assertEquals(1, xpubCount())
        assertEquals(0, authCount())
    }


    @Test
    fun `a cancelled refresh is rethrown and does not return the stale 401`() = withPairedDojo {
        seedExpiredToken()
        serveAuthThen { req ->
            if (isXpub(req)) expired()
            else error("unexpected ${req.path}")
        }
        api.beforeNewCall = { _, request ->
            if (request.url.encodedPath.contains("/auth/")) {
                throw CancellationException("test cancellation")
            }
        }
        try {
            runBlocking { api.rescanPubKey(XPUB, AddressTypes.BIP84) }.close()
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertEquals(1, xpubCount())
        assertEquals(0, authCount())
    }

    @Test
    fun `a cancelled refresh before the key loop is rethrown and later keys are not scanned`() = withPairedDojo {
        seedExpiredToken()
        serveAuthThen { req ->
            if (isXpub(req)) ok()
            else error("unexpected ${req.path}")
        }
        api.beforeNewCall = { _, request ->
            if (request.url.encodedPath.contains("/auth/")) {
                throw CancellationException("test cancellation")
            }
        }
        try {
            runBlocking { PubKeyRescanner.rescanKeys(SIX_KEYS) }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertEquals(0, xpubCount())
    }

    @Test
    fun `a cancelled key is rethrown and later keys are not scanned`() = withPairedDojo {
        seedExpiredToken()
        serveAuthThen { req ->
            if (isXpub(req)) ok()
            else error("unexpected ${req.path}")
        }
        var xpubCalls = 0
        api.beforeNewCall = { _, request ->
            val path = request.url.encodedPath
            if (path == "/xpub" || path.endsWith("/xpub")) {
                xpubCalls++
                if (xpubCalls == 2) throw CancellationException("test cancellation")
            }
        }
        try {
            runBlocking { PubKeyRescanner.rescanKeys(SIX_KEYS) }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertEquals(1, xpubCount())
    }

    @Test
    fun `the progress dialog is dismissed when a CancellationException is thrown`() = withPairedDojo {
        seedExpiredToken()
        serveAuthThen { error("the call must be cancelled before it is sent") }
        api.beforeNewCall = { _, _ -> throw CancellationException("test cancellation") }
        val activity = rescanActivity()
        PubKeyRescanner.rescan(activity, SIX_KEYS.take(1), emptyList(), 0, 0L)
        waitUntil { !dialogShowing() }
        assertFalse(dialogShowing())
    }

    @Test
    fun `the progress dialog is dismissed when ApiNotConfigured is thrown`() = withPairedDojo {
        seedExpiredToken()
        serveAuthThen { error("ApiNotConfigured must be thrown before the call is sent") }
        api.beforeNewCall = { _, _ -> throw ApiService.ApiNotConfigured() }
        val activity = rescanActivity()
        PubKeyRescanner.rescan(activity, SIX_KEYS.take(1), emptyList(), 0, 0L)
        waitUntil { !dialogShowing() }
        assertFalse(dialogShowing())
    }

    @Test
    fun `an expired-looking 401 after an off-origin redirect does not refresh or resend`() = withPairedDojo {
        seedExpiredToken()
        val offHits = CopyOnWriteArrayList<String>()
        val off = MockWebServer()
        off.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            off.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    offHits += request.path ?: ""
                    return expired()
                }
            }
            server.dispatcher = recording { req ->
                when {
                    req.path.orEmpty().startsWith("/auth/") ->
                        // 500 rather than error(): on the unfixed path a
                        // thrown dispatcher leaves the client waiting until
                        // the Tor read timeout.
                        MockResponse().setResponseCode(500).setBody("refresh-must-not-run")
                    isXpub(req) ->
                        MockResponse()
                            .setResponseCode(302)
                            .setHeader("Location", off.url("/xpub").toString())
                    else -> error("unexpected ${req.path}")
                }
            }
            val response = runBlocking { api.rescanPubKey(XPUB, AddressTypes.BIP84) }
            try {
                assertEquals(401, response.code)
            } finally {
                response.close()
            }
            assertEquals(0, authCount())
            assertEquals(1, xpubCount())
            assertEquals(1, offHits.size)
            val secrets = listOf(STALE, FRESH, REFRESH, API_KEY, "at=")
            assertTrue(offHits.none { line -> secrets.any { it in line } })
        } finally {
            off.shutdown()
        }
    }

    @Test
    fun `the first response is closed when the call is resent`() = withPairedDojo {
        seedExpiredToken()
        serveAuthThen { req ->
            if (isXpub(req) && hasFresh(req)) ok()
            else if (isXpub(req)) expired()
            else error("unexpected ${req.path}")
        }
        val observed = mutableListOf<Int>()
        val closed = mutableListOf<Int>()
        api.afterCall = { observed += it.code }
        api.onResponseClosed = { closed += it.code }
        runBlocking { api.rescanPubKey(XPUB, AddressTypes.BIP84) }.close()
        assertTrue("the first 401 must have been observed", 401 in observed)
        assertTrue("first.close() must have closed the first 401 body", 401 in closed)
        assertEquals(1, authCount())
        assertEquals(2, xpubCount())
    }

    //
    // Harness
    //

    private fun seedExpiredToken() {
        prefs.authorization = STALE
        prefs.refreshToken = REFRESH
        setApiKey(API_KEY)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Refresh (or login) once, then handle the caller's Dojo route. */
    private fun serveAuthThen(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = recording { req ->
            val path = req.path.orEmpty()
            when {
                path.startsWith("/auth/refresh") ->
                    MockResponse().setResponseCode(200).setBody(FRESH_AUTH)
                path.startsWith("/auth/login") ->
                    MockResponse().setResponseCode(200).setBody(FRESH_AUTH)
                else -> handler(req)
            }
        }
    }

    private fun recording(handler: (RecordedRequest) -> MockResponse): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.add(Hit(request.method ?: "", request.path ?: "", request.body.readUtf8()))
                return handler(request)
            }
        }

    private fun expired() = MockResponse().setResponseCode(401).setBody(DOJO_EXPIRED)
    private fun ok() = MockResponse().setResponseCode(200).setBody("""{"status":"ok"}""")

    private fun isXpub(req: RecordedRequest) = req.path.orEmpty().substringBefore("?").let {
        it == "/xpub" || it.endsWith("/xpub")
    }
    private fun isXpub(hit: Hit) = hit.path.substringBefore("?").let { it == "/xpub" || it.endsWith("/xpub") }
    private fun isImportStatus(req: RecordedRequest) = req.path.orEmpty().contains("/import/status")
    private fun isImportStatus(hit: Hit) = hit.path.contains("/import/status")
    private fun isTxHex(req: RecordedRequest) = req.path.orEmpty().contains("/tx/") && req.path.orEmpty().contains("/HEX")
    private fun isTxHex(hit: Hit) = hit.path.contains("/tx/") && hit.path.contains("/HEX")
    private fun hasFresh(req: RecordedRequest) = req.path.orEmpty().contains("at=$FRESH")
    private fun xpubHits() = seen.filter { isXpub(it) && it.method == "POST" }
    private fun xpubCount() = xpubHits().size
    private fun authCount() = seen.count {
        it.path.substringBefore("?").endsWith("/auth/refresh") ||
            it.path.substringBefore("?").endsWith("/auth/login")
    }

    private fun rescanActivity(): AppCompatActivity {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppThemeV3)
        return controller.setup().get()
    }

    private fun dialogShowing(): Boolean =
        ShadowDialog.getShownDialogs().any { it.isShowing }

    private fun waitUntil(timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        fail("timed out")
    }

    private fun http(code: Int, body: String): Response = Response.Builder()
        .request(Request.Builder().url("http://127.0.0.1/xpub").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("unused")
        .headers(Headers.headersOf())
        .body(body.toResponseBody())
        .build()

    private fun <T> withPairedDojo(block: () -> T): T {
        val endpoint = server.url("/").toString().trimEnd('/')
        prefs.apiEndPoint = endpoint
        prefs.apiEndPointTor = endpoint
        prefs.enableTor = false
        setDojoPayload(DojoPairing())
        setTor(on = true)
        shadowOf(Looper.getMainLooper()).idle()
        return try {
            block()
        } finally {
            restorePairingGate()
        }
    }

    private fun restorePairingGate() {
        if (this::prefs.isInitialized) prefs.enableTor = false
        if (GlobalContext.getOrNull() != null) setDojoPayload(null)
        setTor(on = false)
    }

    private fun setDojoPayload(payload: DojoPairing?) {
        val targets = linkedSetOf<DojoUtility>()
        if (GlobalContext.getOrNull() != null) {
            targets.add(KoinJavaComponent.get(DojoUtility::class.java))
        }
        stateDojo()?.let { targets.add(it) }
        val field = DojoUtility::class.java.getDeclaredField("dojoPayload")
        field.isAccessible = true
        targets.forEach { field.set(it, payload) }
    }

    private fun stateDojo(): DojoUtility? {
        val field = SentinelState::class.java.getDeclaredField("dojoUtility\$delegate")
        field.isAccessible = true
        val lazy = field.get(null) as kotlin.Lazy<*>
        if (!lazy.isInitialized()) return null
        return lazy.value as DojoUtility
    }

    private fun setTor(on: Boolean) {
        SentinelTorManager::class.java.getDeclaredField("proxy").apply { isAccessible = true }
            .set(SentinelTorManager, if (on) Proxy.NO_PROXY else null)
        val liveField = SentinelTorManager::class.java.getDeclaredField("torStateLiveDataPrivate")
        liveField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val live = liveField.get(SentinelTorManager) as MutableLiveData<TorState>
        live.value = TorState().apply {
            state = if (on) EnumTorState.ON else EnumTorState.OFF
            progressIndicator = if (on) 100 else 0
        }
    }

    private fun setApiKey(apiKey: String) {
        val field = DojoUtility::class.java.getDeclaredField("apiKey").apply { isAccessible = true }
        if (GlobalContext.getOrNull() != null) {
            field.set(KoinJavaComponent.get(DojoUtility::class.java), apiKey)
        }
        stateDojo()?.let { field.set(it, apiKey) }
    }

    private fun assertNoSecretsInLogs() {
        val secrets = listOf(STALE, FRESH, REFRESH, API_KEY)
        val leaked = logs.filter { line -> secrets.any { it in line } }
        assertTrue("logs must not contain tokens or the API key", leaked.isEmpty())
    }

    companion object {
        private var startedKoin = false

        @JvmStatic
        @org.junit.AfterClass
        fun tearDownClass() {
            if (startedKoin && GlobalContext.getOrNull() != null) {
                stopKoin()
                startedKoin = false
            }
        }

        private const val STALE = "stale-access"
        private const val FRESH = "access-new"
        private const val REFRESH = "refresh-old"
        private const val API_KEY = "synthetic-node-key"
        private const val FRESH_AUTH =
            """{"authorizations":{"access_token":"$FRESH","refresh_token":"refresh-new"}}"""
        private const val DOJO_EXPIRED =
            """{"status":"error","error":"Invalid JSON Web Token"}"""
        // Well-known BIP32 test vector; used only as a syntactically valid xpub.
        private const val XPUB =
            "xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqYh2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3fDVmz"
        private val SIX_KEYS = listOf(
            PubKeyModel(XPUB, "Deposit BIP84", AddressTypes.BIP84),
            PubKeyModel(XPUB, "Premix", AddressTypes.BIP84),
            PubKeyModel(XPUB, "Postmix", AddressTypes.BIP84),
            PubKeyModel(XPUB, "Bad Bank", AddressTypes.BIP84),
            PubKeyModel(XPUB, "Deposit BIP44", AddressTypes.BIP44),
            PubKeyModel(XPUB, "Deposit BIP49", AddressTypes.BIP49),
        )
    }
}
