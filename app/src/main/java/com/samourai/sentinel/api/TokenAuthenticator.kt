package com.samourai.sentinel.api

import com.samourai.sentinel.ui.utils.PrefsUtil
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

/**
 * Re-authenticates with the Dojo when a request comes back 401.
 *
 * Dojo access tokens expire after 15 minutes, so this runs routinely, not just
 * in error cases.
 *
 * The previous implementation was broken in three ways, which together meant
 * balances stopped loading permanently 15 minutes after pairing:
 *
 *  1. It returned null, which tells OkHttp "authentication is impossible" and
 *     fails the call. The original request was never retried even when the
 *     refresh succeeded.
 *  2. It called `authenticateDojo()` (which returns a Job on another scope)
 *     without awaiting it, then slept a fixed 100ms. Over Tor a login round trip
 *     takes seconds, so the refresh was still in flight.
 *  3. Even on retry, the token was injected from a value captured when the
 *     OkHttp client was built, so the stale token was resent.
 */
class TokenAuthenticator(private val apiService: ApiService) : Authenticator {

    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)

    companion object {
        /** Max consecutive re-auth attempts for one logical request. */
        private const val MAX_RETRIES = 2

        /**
         * #468: true when [target] speaks to the configured Dojo endpoint.
         *
         * Strict on scheme, host and effective port - a credential gate
         * fails closed, so anything but an exact origin match is false.
         * okhttp resolves default ports, so an endpoint configured without
         * one still matches the 80/443 the request actually used.
         */
        internal fun isDojoTarget(configuredUrl: String?, target: HttpUrl): Boolean {
            val configured = configuredUrl?.toHttpUrlOrNull() ?: return false
            return configured.scheme == target.scheme &&
                configured.host.equals(target.host, ignoreCase = true) &&
                configured.port == target.port
        }
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        // Give up rather than loop forever if the server keeps rejecting us.
        if (responseCount(response) >= MAX_RETRIES) {
            Timber.w("Dojo re-auth failed after $MAX_RETRIES attempts; giving up")
            return null
        }

        // #468: this authenticator exists for exactly one origin - the
        // configured Dojo endpoint. A 401 from anywhere else (the exchange
        // providers routed through ApiService.request()) is not ours to
        // answer: return null and fail the call rather than burn a Dojo
        // re-login and retry a third-party URL with `at=<Dojo token>`.
        // A missing or unparseable endpoint fails closed the same way.
        if (!isDojoTarget(runCatching { apiService.getAPIUrl() }.getOrNull(), response.request.url)) {
            Timber.w("TokenAuthenticator: 401 from non-Dojo origin; declining to attach the Dojo token")
            return null
        }

        val refreshed = runBlocking {
            try {
                // Blocking and awaited, unlike the old fire-and-forget Job.
                apiService.refreshDojoAuth()
            } catch (e: ApiService.ApiNotConfigured) {
                // #125: ApiNotConfigured extends Throwable (:364), so the Exception wall
                // below cannot intercept it - and neither can refreshDojoAuth's own
                // Exception-only walls (:129, :141-149) nor OkHttp's interceptor chain,
                // so the throw escaped runBlocking on an OkHttp dispatcher thread. Degrade
                // to false: refreshed == false returns null (:59) and the call fails, the
                // honest outcome when there is no endpoint to re-auth against.
                Timber.e(e, "Dojo re-auth aborted: no API endpoint configured")
                false
            } catch (e: Exception) {
                Timber.e(e, "Dojo re-authentication failed")
                false
            }
        }

        if (!refreshed) {
            // Returning null fails the call, which is correct here: we genuinely
            // could not obtain a token.
            return null
        }

        val token = prefsUtil.authorization
        if (token.isNullOrEmpty()) {
            Timber.e("Dojo re-auth reported success but no token was stored")
            return null
        }

        // Rebuild the request with the NEW token so OkHttp retries it.
        val url = response.request.url.newBuilder()
            .removeAllQueryParameters("at")
            .addQueryParameter("at", token)
            .build()

        return response.request.newBuilder()
            .url(url)
            .build()
    }

    /** Number of times this request has already been retried. */
    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
