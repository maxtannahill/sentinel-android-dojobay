package com.samourai.sentinel.ui.utils

/**
 * Main-thread navigation/action latch. Claim before dispatch (including while
 * an activity launch is still queued), not for an arbitrary debounce timeout.
 * The owner releases it when the destination returns; failed dispatch may retry.
 */
internal class SingleFlightAction {
    private var inFlight = false

    fun run(action: () -> Unit) {
        if (inFlight) return
        inFlight = true
        try {
            action()
        } catch (failure: Throwable) {
            reset()
            throw failure
        }
    }

    fun reset() {
        inFlight = false
    }
}
