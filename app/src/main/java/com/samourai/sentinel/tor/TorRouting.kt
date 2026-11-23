package com.samourai.sentinel.tor

import com.samourai.sentinel.ui.utils.PrefsUtil

/**
 * Tor Routing, as the Settings toggle sets it: the [PrefsUtil.enableTor]
 * pref plus starting or stopping the daemon. Community Dojos uses the
 * same enable path so Settings shows the toggle on after TURN ON TOR.
 */
object TorRouting {

    /** Same as turning Tor Routing on in Settings. */
    fun enable(prefs: PrefsUtil) {
        SentinelTorManager.start()
        prefs.enableTor = true
    }

    /**
     * The Community Dojos list may start or wait for Tor when the user
     * has Tor Routing on, or when Tor is already on. STARTING only
     * counts when the setting is on — that is the "still starting
     * because they enabled it" case, including Turn on Tor, which sets
     * the pref and then sees STARTING. A STARTING daemon with routing
     * off does not pass the gate, and must not be restarted.
     */
    fun directoryMayUseTor(prefs: PrefsUtil): Boolean {
        if (prefs.enableTor == true) return true
        if (SentinelTorManager.isTorReady()) return true
        return SentinelTorManager.getTorState().state == EnumTorState.ON
    }
}
