package com.samourai.sentinel.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class SingleFlightActionTest {
    @Test fun repeatedLaunchOnlyDispatchesOnceUntilReturn() {
        val guard = SingleFlightAction()
        var launches = 0
        repeat(10) { guard.run { launches++ } }
        assertEquals(1, launches)
    }

    @Test fun returnAllowsOneMoreLaunch() {
        val guard = SingleFlightAction()
        var launches = 0
        guard.run { launches++ }
        guard.reset()
        repeat(2) { guard.run { launches++ } }
        assertEquals(2, launches)
    }

    @Test fun guardIsClaimedBeforeReentrantDispatch() {
        val guard = SingleFlightAction()
        var launches = 0
        guard.run {
            launches++
            guard.run { launches++ }
        }
        assertEquals(1, launches)
    }

    @Test fun failedDispatchPropagatesAndCanBeRetried() {
        val guard = SingleFlightAction()
        val failure = IllegalStateException("dispatch failed")
        try {
            guard.run { throw failure }
            fail("failure must propagate")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        var launches = 0
        guard.run { launches++ }
        assertEquals(1, launches)
    }

    @Test fun independentDestinationsDoNotShareState() {
        val first = SingleFlightAction()
        val second = SingleFlightAction()
        var launches = 0
        first.run { launches++ }
        second.run { launches++ }
        first.run { launches++ }
        assertEquals(2, launches)
    }

    @Test fun terminalSaveDoesNotRepeatWithoutReset() {
        val guard = SingleFlightAction()
        var saves = 0
        var finishes = 0
        repeat(2) { guard.run { saves++; finishes++ } }
        assertEquals(1, saves)
        assertEquals(1, finishes)
    }
}
