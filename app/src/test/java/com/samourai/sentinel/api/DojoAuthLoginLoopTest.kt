package com.samourai.sentinel.api

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #470. /auth/login used to be built with [TokenAuthenticator] attached.
 * A 401 on that call runs [ApiService.refreshDojoAuth], whose fallback
 * is another login, and each nested login is a new OkHttp chain the
 * prior-response cap does not see. /auth/refresh already opts out of
 * the authenticator; login has to as well.
 *
 * The Dojo here is [MockWebServer] on loopback. The api key is stored
 * without a pairing payload: [DojoUtility.isDojoEnabled] is payload
 * presence, and a payload would force the Tor gate in front of a
 * clearnet test server. RFC 2606 is not required; the host is the
 * test server itself, which is also what the origin gate compares.
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
