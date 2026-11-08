package com.samourai.sentinel.api

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.tor.TorState
import com.samourai.sentinel.ui.dojo.DojoPairing
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * #470. /auth/login used to be built with [TokenAuthenticator] attached.
 * A 401 on that call runs [ApiService.refreshDojoAuth], whose fallback
 * is another login, and each nested login is a new OkHttp chain the
 * prior-response cap does not see. /auth/refresh already opts out of
 * the authenticator; login has to as well.
 *
 * The Dojo here is [MockWebServer] on loopback. The single-call cases
 * store the api key without a pairing payload: [DojoUtility.isDojoEnabled]
 * is payload presence, and a payload forces the Tor gate. The concurrent
 * cases turn that gate on and point it at [Proxy.NO_PROXY] so the access
 * token is actually attached, which is what a paired Dojo does, and the
 * test server stays on loopback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DojoAuthLoginLoopTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: PrefsUtil
    private lateinit var api: ApiService
    private val seen = CopyOnWriteArrayList<Hit>()

    private data class Hit(val path: String, val body: String)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)

        if (!this::api.isInitialized) {
            if (GlobalContext.getOrNull() != null) stopKoin()
            val app = ApplicationProvider.getApplicationContext<Application>()
            File(app.filesDir, "app_wallet/dojo.payload").delete()
            File(app.filesDir, "app_wallet/dojo.payload.bak").delete()
            prefs = PrefsUtil(app)
            prefs.enableTor = false
            prefs.testnet = false
            prefs.authorization = ""
            prefs.refreshToken = ""
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
            api = org.koin.java.KoinJavaComponent.get(ApiService::class.java)
            // SentinelState posts its keychain observer on first use.
            shadowOf(android.os.Looper.getMainLooper()).idle()
            startedKoin = true
        }
        prefs.apiEndPoint = server.url("/").toString().trimEnd('/')
        prefs.apiEndPointTor = null
        prefs.authorization = ""
        prefs.refreshToken = ""
        prefs.enableTor = false
        setApiKey("rotated-key")
        seen.clear()
    }

    @After
    fun tearDown() {
        if (this::api.isInitialized) {
            api.client.dispatcher.executorService.shutdown()
            api.client.connectionPool.evictAll()
        }
        restorePairingGate()
        server.shutdown()
    }

    /**
     * The reported failure: the Dojo rejects login (the api key was
     * rotated, there is nothing to refresh with). One POST, then the
     * existing terminal result. No nested login, no hang.
     */
    @Test(timeout = 15_000)
    fun `rejected login is a single request and refresh fails`() {
        server.dispatcher = dispatcher {
            MockResponse().setResponseCode(401).setBody("denied")
        }

        val refreshed = runBlocking { api.refreshDojoAuth() }

        assertFalse(refreshed)
        assertEquals(1, seen.size)
        assertEquals("/auth/login", seen.single().path)
        assertTrue(seen.single().body.contains("apikey=rotated-key"))
        assertEquals(0, seen.count { it.path.startsWith("/auth/refresh") })
    }

    /**
     * /auth/refresh must not re-enter either. A 401 there falls through
     * to one login; when that is also rejected the original call fails
     * with the 401 OkHttp already returns, and nothing is retried.
     */
    @Test(timeout = 15_000)
    fun `rejected refresh falls back to one login and the call fails`() {
        prefs.refreshToken = "refresh-old"
        prefs.authorization = "expired-token"
        server.dispatcher = dispatcher { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/auth/refresh") ->
                    MockResponse().setResponseCode(401).setBody("denied")
                path.startsWith("/auth/login") ->
                    MockResponse().setResponseCode(401).setBody("denied")
                path.startsWith("/wallet") ->
                    MockResponse().setResponseCode(401).setBody("denied")
                else -> error("unexpected $path")
            }
        }

        val response = runBlocking { api.getWallet("xpub-test") }
        try {
            assertEquals(401, response.code)
            assertFalse(response.isSuccessful)
        } finally {
            response.close()
        }

        assertEquals(3, seen.size)
        assertTrue(seen[0].path.startsWith("/wallet"))
        assertTrue(seen[1].path.startsWith("/auth/refresh"))
        assertTrue(seen[2].path.startsWith("/auth/login"))
        assertTrue(seen[1].body.contains("rt=refresh-old"))
        assertTrue(seen[2].body.contains("apikey=rotated-key"))
    }

    /**
     * The ordinary expiry path: one Dojo call gets a 401, one refresh
     * succeeds, the same call is retried with the new access token.
     */
    @Test(timeout = 15_000)
    fun `a 401 refreshes once and retries the Dojo call`() {
        prefs.refreshToken = "refresh-ok"
        prefs.authorization = "expired-token"
        var wallets = 0
        server.dispatcher = dispatcher { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/auth/refresh") ->
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """{"authorizations":{"access_token":"access-new"}}""",
                        )
                path.startsWith("/wallet") -> {
                    wallets++
                    if (wallets == 1) {
                        MockResponse().setResponseCode(401).setBody("expired")
                    } else {
                        MockResponse().setResponseCode(200).setBody("""{"status":"ok"}""")
                    }
                }
                else -> error("unexpected ${request.path}")
            }
        }

        val response = runBlocking { api.getWallet("xpub-test") }
        val body = response.body?.string()
        response.close()

        assertTrue(response.isSuccessful)
        assertEquals("""{"status":"ok"}""", body)
        assertEquals("access-new", prefs.authorization)
        assertEquals(3, seen.size)
        assertTrue(seen[0].path.startsWith("/wallet"))
        assertFalse(seen[0].path.contains("at="))
        assertTrue(seen[1].path.startsWith("/auth/refresh"))
        assertTrue(seen[1].body.contains("rt=refresh-ok"))
        assertTrue(seen[2].path.startsWith("/wallet"))
        assertTrue(seen[2].path.contains("at=access-new"))
        assertEquals(0, seen.count { it.path.startsWith("/auth/login") })
    }

    /**
     * One pull-to-refresh is several wallet calls. Against a Dojo that
     * rejects every auth request they must share one refresh and one
     * login, then fail together. The login must not carry the access
     * token that just got the 401.
     */
    @Test(timeout = 20_000)
    fun `concurrent 401s share one login and then fail`() {
        val held = burst(
            calls = 3,
            refreshCode = 401,
            refreshBody = "denied",
            loginCode = 401,
            loginBody = "denied",
        )
        try {
            assertEquals(3, held.responses.size)
            assertTrue(held.responses.all { it.code == 401 })
            assertEquals(1, held.refreshesWhileLeaderBlocked)
            assertEquals(1, paths("/auth/refresh").size)
            assertEquals(1, paths("/auth/login").size)
            assertEquals(3, paths("/wallet").size)
            val wallets = paths("/wallet").map { it.path }
            assertTrue(wallets.joinToString(), wallets.all { it.contains("at=$STALE") })
            assertFalse(paths("/auth/login").single().path.contains("at="))
            assertFalse(paths("/auth/refresh").single().path.contains("at="))
            assertTrue(paths("/auth/login").single().body.contains("apikey=rotated-key"))
        } finally {
            held.responses.forEach { it.close() }
        }
    }

    /**
     * The same burst when login succeeds: one login, no second auth
     * round, and every retry wears the new token rather than the one
     * that was rejected.
     */
    @Test(timeout = 20_000)
    fun `concurrent 401s retry once with the new token and login drops the old one`() {
        val held = burst(
            calls = 3,
            refreshCode = 401,
            refreshBody = "denied",
            loginCode = 200,
            loginBody = """{"authorizations":{"access_token":"$FRESH"}}""",
        )
        try {
            assertEquals(3, held.responses.size)
            assertTrue(held.responses.all { it.isSuccessful })
            assertEquals(1, held.refreshesWhileLeaderBlocked)
            assertEquals(1, paths("/auth/refresh").size)
            assertEquals(1, paths("/auth/login").size)
            assertFalse(paths("/auth/login").single().path.contains("at="))
            assertFalse(paths("/auth/login").single().path.contains(STALE))
            assertFalse(paths("/auth/refresh").single().path.contains(STALE))
            val retries = paths("/wallet").filter { it.path.contains("at=$FRESH") }
            assertEquals(3, retries.size)
            assertTrue(retries.all { !it.path.contains(STALE) })
            assertTrue(retries.all { it.path.split("at=").size - 1 == 1 })
            assertEquals(FRESH, prefs.authorization)
        } finally {
            held.responses.forEach { it.close() }
        }
    }

    /**
     * Hold the first refresh until every wallet 401 is in flight, so a
     * missing single-flight shows up as extra /auth/refresh calls.
     */
    private fun burst(
        calls: Int,
        refreshCode: Int,
        refreshBody: String,
        loginCode: Int,
        loginBody: String,
    ): BurstResult {
        val release = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val refreshCount = AtomicInteger(0)
        val walletCount = AtomicInteger(0)
        server.dispatcher = dispatcher { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/auth/refresh") -> {
                    refreshCount.incrementAndGet()
                    if (walletCount.get() >= calls) ready.countDown()
                    assertTrue(release.await(8, TimeUnit.SECONDS))
                    MockResponse().setResponseCode(refreshCode).setBody(refreshBody)
                }
                path.startsWith("/auth/login") ->
                    MockResponse().setResponseCode(loginCode).setBody(loginBody)
                path.startsWith("/wallet") -> {
                    val n = walletCount.incrementAndGet()
                    if (n >= calls && refreshCount.get() >= 1) ready.countDown()
                    if (path.contains("at=$FRESH")) {
                        MockResponse().setResponseCode(200).setBody("""{"status":"ok"}""")
                    } else {
                        MockResponse().setResponseCode(401).setBody("expired")
                    }
                }
                else -> error("unexpected $path")
            }
        }

        val refreshesWhileLeaderBlocked = AtomicInteger(-1)
        val waiter = Thread {
            try {
                if (ready.await(8, TimeUnit.SECONDS)) {
                    Thread.sleep(300)
                    refreshesWhileLeaderBlocked.set(refreshCount.get())
                }
            } finally {
                release.countDown()
            }
        }
        waiter.isDaemon = true
        waiter.start()

        val responses = try {
            withPairedDojo {
                prefs.authorization = STALE
                prefs.refreshToken = "refresh-old"
                // apply() posts the write. Flush it before the burst reads.
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertTrue(com.samourai.sentinel.core.SentinelState.isDojoEnabled())
                assertTrue(com.samourai.sentinel.tor.SentinelTorManager.isTorReady())
                runBlocking {
                    (1..calls).map { n ->
                        async(Dispatchers.IO) { api.getWallet("pub-$n") }
                    }.awaitAll()
                }
            }
        } finally {
            release.countDown()
            waiter.join(5_000)
        }
        return BurstResult(responses, refreshesWhileLeaderBlocked.get())
    }

    private data class BurstResult(
        val responses: List<Response>,
        val refreshesWhileLeaderBlocked: Int,
    )

    /**
     * A paired Dojo attaches `at` and refuses a direct client until Tor
     * is ready. [Proxy.NO_PROXY] satisfies the gate without leaving
     * loopback. Restored by [tearDown].
     */
    private fun <T> withPairedDojo(block: () -> T): T {
        val endpoint = server.url("/").toString().trimEnd('/')
        prefs.apiEndPoint = endpoint
        prefs.apiEndPointTor = endpoint
        prefs.enableTor = false
        setDojoPayload(DojoPairing())
        setTor(on = true)
        return try {
            block()
        } finally {
            restorePairingGate()
        }
    }

    private fun restorePairingGate() {
        if (this::prefs.isInitialized) {
            prefs.enableTor = false
        }
        if (startedKoin && GlobalContext.getOrNull() != null) {
            setDojoPayload(null)
        }
        setTor(on = false)
    }

    private fun setDojoPayload(payload: DojoPairing?) {
        // SentinelState caches the first DojoUtility it resolved. The
        // interceptor asks that instance, while refresh uses the Koin
        // singleton. A later test class can leave those as two objects.
        val targets = linkedSetOf<DojoUtility>()
        if (startedKoin && GlobalContext.getOrNull() != null) {
            targets.add(org.koin.java.KoinJavaComponent.get(DojoUtility::class.java))
        }
        stateDojo()?.let { targets.add(it) }
        val field = DojoUtility::class.java.getDeclaredField("dojoPayload")
        field.isAccessible = true
        targets.forEach { field.set(it, payload) }
    }

    private fun stateDojo(): DojoUtility? {
        val field = com.samourai.sentinel.core.SentinelState::class.java
            .getDeclaredField("dojoUtility\$delegate")
        field.isAccessible = true
        val lazy = field.get(null) as kotlin.Lazy<*>
        return lazy.value as DojoUtility
    }

    private fun setTor(on: Boolean) {
        val proxyField = SentinelTorManager::class.java.getDeclaredField("proxy")
        proxyField.isAccessible = true
        proxyField.set(SentinelTorManager, if (on) Proxy.NO_PROXY else null)

        val liveField = SentinelTorManager::class.java.getDeclaredField("torStateLiveDataPrivate")
        liveField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val live = liveField.get(SentinelTorManager) as MutableLiveData<TorState>
        live.value = TorState().apply {
            state = if (on) EnumTorState.ON else EnumTorState.OFF
            progressIndicator = if (on) 100 else 0
        }
    }

    private fun paths(prefix: String) = seen.filter { it.path.startsWith(prefix) }

    private fun dispatcher(
        handler: (RecordedRequest) -> MockResponse,
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            seen.add(Hit(request.path ?: "", request.body.readUtf8()))
            return handler(request)
        }
    }

    /** [DojoUtility] keeps the api key private and only publishes it from a payload. */
    private fun setApiKey(apiKey: String) {
        val dojo = org.koin.java.KoinJavaComponent.get(DojoUtility::class.java)
        val field = DojoUtility::class.java.getDeclaredField("apiKey")
        field.isAccessible = true
        field.set(dojo, apiKey)
    }

    companion object {
        private const val STALE = "stale-access"
        private const val FRESH = "access-new"
        private var startedKoin = false

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            if (startedKoin && GlobalContext.getOrNull() != null) {
                stopKoin()
                startedKoin = false
            }
        }
    }
}
