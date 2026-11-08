package com.samourai.sentinel.ui.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #472: when Tor is required, no state may produce a direct (clearnet)
 * load. Direct loading stays available when Tor is genuinely optional.
 */
class ExplorerLoadDecisionTest {

    private val directLoads = setOf(
        ExplorerLoadDecision.LOAD_DIRECT,
        ExplorerLoadDecision.OFFER_DIRECT_UNSUPPORTED,
    )

    @Test
    fun `tor required never loads direct in any state`() {
        for (supported in listOf(true, false))
            for (ready in listOf(true, false))
                for (off in listOf(true, false)) {
                    val d = explorerLoadDecision(supported, ready, off, torRequired = true)
                    assertTrue(
                        "supported=$supported ready=$ready off=$off gave $d",
                        d !in directLoads
                    )
                }
    }

    @Test
    fun `tor required and off blocks`() {
        assertEquals(
            ExplorerLoadDecision.BLOCK_TOR_OFF,
            explorerLoadDecision(proxyOverrideSupported = true, torReady = false, torOff = true, torRequired = true)
        )
    }

    @Test
    fun `tor required and proxy unsupported blocks with no continue`() {
        for (ready in listOf(true, false))
            for (off in listOf(true, false))
                assertEquals(
                    ExplorerLoadDecision.BLOCK_PROXY_UNSUPPORTED,
                    explorerLoadDecision(proxyOverrideSupported = false, torReady = ready, torOff = off, torRequired = true)
                )
    }

    @Test
    fun `tor ready loads via proxy whether required or not`() {
        for (required in listOf(true, false))
            assertEquals(
                ExplorerLoadDecision.LOAD_VIA_PROXY,
                explorerLoadDecision(proxyOverrideSupported = true, torReady = true, torOff = false, torRequired = required)
            )
    }

    @Test
    fun `tor starting waits whether required or not`() {
        for (required in listOf(true, false))
            assertEquals(
                ExplorerLoadDecision.WAIT_FOR_TOR,
                explorerLoadDecision(proxyOverrideSupported = true, torReady = false, torOff = false, torRequired = required)
            )
    }

    @Test
    fun `optional tor off still loads direct`() {
        assertEquals(
            ExplorerLoadDecision.LOAD_DIRECT,
            explorerLoadDecision(proxyOverrideSupported = true, torReady = false, torOff = true, torRequired = false)
        )
    }

    @Test
    fun `optional tor on unsupported device offers an explicit direct choice`() {
        val d = explorerLoadDecision(proxyOverrideSupported = false, torReady = false, torOff = true, torRequired = false)
        assertEquals(ExplorerLoadDecision.OFFER_DIRECT_UNSUPPORTED, d)
        assertNotEquals(ExplorerLoadDecision.LOAD_DIRECT, d)
    }
}
