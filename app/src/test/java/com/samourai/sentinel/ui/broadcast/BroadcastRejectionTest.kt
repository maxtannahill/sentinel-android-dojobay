package com.samourai.sentinel.ui.broadcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BroadcastRejectionTest {

    @Test
    fun `a rejected payload clears the field and disables broadcast`() {
        val rejected = rejectedBroadcastEntry()
        assertEquals("", rejected.text)
        assertFalse(rejected.broadcastEnabled)
    }
}
