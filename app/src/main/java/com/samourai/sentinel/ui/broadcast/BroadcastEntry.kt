package com.samourai.sentinel.ui.broadcast

/**
 * A rejected broadcast payload tried to replace the field. The previous
 * transaction is dropped and BROADCAST stays disabled, so the text on
 * screen and the button describe the same state.
 */
internal data class BroadcastEntry(
    val text: String,
    val broadcastEnabled: Boolean,
)

internal fun rejectedBroadcastEntry(): BroadcastEntry =
    BroadcastEntry(text = "", broadcastEnabled = false)
