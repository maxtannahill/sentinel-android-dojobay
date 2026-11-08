package com.samourai.sentinel.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #465: the XPUB and account type stay in-process; the Intent carries
 * only an opaque, single-use token.
 */
class ImportSegWitRequestsTest {

    private val xpub =
        "xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqYh2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3fDVmz"

    @After
    fun tearDown() = ImportSegWitRequests.clear()

    @Test
    fun `token reveals nothing about the xpub or account type`() {
        val token = ImportSegWitRequests.put(xpub, "bip84")
        assertFalse(token.contains(xpub))
        assertFalse(token.contains("bip84"))
        assertFalse(token.contains(xpub.substring(4, 20)))
    }

    @Test
    fun `token returns the request exactly once`() {
        val token = ImportSegWitRequests.put(xpub, "bip49")
        assertEquals(ImportSegWitRequests.Request(xpub, "bip49"), ImportSegWitRequests.take(token))
        assertNull(ImportSegWitRequests.take(token))
    }

    @Test
    fun `unknown or missing token returns nothing`() {
        ImportSegWitRequests.put(xpub, "bip84")
        assertNull(ImportSegWitRequests.take(null))
        assertNull(ImportSegWitRequests.take("not-a-token"))
        // A raw xpub where the token belongs (the old extras shape) is refused.
        assertNull(ImportSegWitRequests.take(xpub))
    }

    @Test
    fun `tokens are distinct per request`() {
        assertNotEquals(
            ImportSegWitRequests.put(xpub, "bip84"),
            ImportSegWitRequests.put(xpub, "bip84")
        )
    }

    @Test
    fun `toString never prints the xpub`() {
        val text = ImportSegWitRequests.Request(xpub, "bip84").toString()
        assertFalse(text.contains(xpub))
    }
}
