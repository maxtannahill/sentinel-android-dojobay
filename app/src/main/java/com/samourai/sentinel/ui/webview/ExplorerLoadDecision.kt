package com.samourai.sentinel.ui.webview

/**
 * #472: how the explorer WebView may load, decided from routing state.
 *
 * The rule: when Tor is required (paired Dojo or the user's Tor toggle,
 * [com.samourai.sentinel.core.SentinelState.isTorRequired]), the explorer
 * loads only through a verified Tor proxy - never directly. Direct loading
 * stays available, as an explicit choice, only when Tor is optional.
 */
enum class ExplorerLoadDecision {
    /** Install the Tor proxy override, then load. */
    LOAD_VIA_PROXY,

    /** Tor is optional and off: load directly. */
    LOAD_DIRECT,

    /** Tor is starting: fetch nothing until it is ready. */
    WAIT_FOR_TOR,

    /** Tor is required but off: fetch nothing, explain, offer an exit. */
    BLOCK_TOR_OFF,

    /** Tor is required but this WebView cannot be proxied: explain and exit. */
    BLOCK_PROXY_UNSUPPORTED,

    /**
     * WebView cannot be proxied and Tor is optional: the user may choose
     * to continue without Tor.
     */
    OFFER_DIRECT_UNSUPPORTED,
}

fun explorerLoadDecision(
    proxyOverrideSupported: Boolean,
    torReady: Boolean,
    torOff: Boolean,
    torRequired: Boolean,
): ExplorerLoadDecision = when {
    !proxyOverrideSupported ->
        if (torRequired) ExplorerLoadDecision.BLOCK_PROXY_UNSUPPORTED
        else ExplorerLoadDecision.OFFER_DIRECT_UNSUPPORTED
    torReady -> ExplorerLoadDecision.LOAD_VIA_PROXY
    torOff ->
        if (torRequired) ExplorerLoadDecision.BLOCK_TOR_OFF
        else ExplorerLoadDecision.LOAD_DIRECT
    else -> ExplorerLoadDecision.WAIT_FOR_TOR
}

/**
 * Tor was required and this screen has not loaded a page yet. STARTING
 * still waits ([explorerLoadDecision] returns [ExplorerLoadDecision.WAIT_FOR_TOR]).
 * If Tor then reports off, that is the same block as opening while Tor
 * is already off. A page that already loaded is left as it is.
 */
fun explorerWhenTorStopsBeforeLoad(
    torRequired: Boolean,
    pageLoaded: Boolean,
): ExplorerLoadDecision? =
    if (torRequired && !pageLoaded) ExplorerLoadDecision.BLOCK_TOR_OFF else null
