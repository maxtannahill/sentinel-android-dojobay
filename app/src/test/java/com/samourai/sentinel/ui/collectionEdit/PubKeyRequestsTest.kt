package com.samourai.sentinel.ui.collectionEdit

import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #484: the add-key model stays in-process; the Intent carries only an
 * opaque, single-use token.
 */
class PubKeyRequestsTest {

    // Deliberately NOT a real key: this fixture only proves the token
    // carries nothing about the model it guards.
    private val model = PubKeyModel(
        pubKey = "not-a-real-key-test-fixture",
        label = "test-label",
        type = AddressTypes.BIP84
    )

    @After
    fun tearDown() = PubKeyRequests.clear()

    @Test
    fun `token reveals nothing about the model`() {
        val token = PubKeyRequests.put(model)
        assertFalse(token.contains(model.pubKey))
        assertFalse(token.contains(model.label))
        assertFalse(token.contains(model.pubKey.substring(4, 20)))
    }

    @Test
    fun `token returns the model exactly once`() {
        val token = PubKeyRequests.put(model)
        assertEquals(PubKeyRequests.Request(model), PubKeyRequests.take(token))
        assertNull(PubKeyRequests.take(token))
    }

    @Test
    fun `unknown or missing token returns nothing`() {
        PubKeyRequests.put(model)
        assertNull(PubKeyRequests.take(null))
        assertNull(PubKeyRequests.take("not-a-token"))
        // A raw key where the token belongs (the old extras shape) is refused.
        assertNull(PubKeyRequests.take(model.pubKey))
    }

    @Test
    fun `tokens are distinct per request`() {
        assertNotEquals(PubKeyRequests.put(model), PubKeyRequests.put(model))
    }

    @Test
    fun `toString never prints the model`() {
        val text = PubKeyRequests.Request(model).toString()
        assertFalse(text.contains(model.pubKey))
        assertFalse(text.contains(model.label))
    }
}
