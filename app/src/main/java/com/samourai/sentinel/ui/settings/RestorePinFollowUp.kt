package com.samourai.sentinel.ui.settings

import com.samourai.sentinel.core.access.PinVault
import com.samourai.sentinel.data.db.PayloadRecord
import org.json.JSONObject

/**
 * #420: what to tell the user about a PIN after a Sentinel restore succeeds.
 *
 * Current-format backups omit unlock material. A keyless install therefore
 * has no PIN afterwards, and the user is offered one. An older backup that
 * still carries a verifier installs that PIN through
 * [PinVault.protectFreshImport] when the install is keyless, and must not
 * also offer a second PIN. When the device already has a PIN, that PIN is
 * kept and the backup PIN is left unused.
 */
internal enum class RestorePinFollowUp {
    /** Keyless install, backup had no verifier. Offer to set a PIN. */
    OFFER_SET,

    /** Verifier accepted, and this device already had a PIN. */
    NOTICE_KEPT,

    /** Nothing further. Includes a verifier that just enabled a PIN. */
    NONE,
}

/**
 * #420: an older backup still names a PIN verifier. Current backups
 * omit both keys, so neither is present.
 */
internal fun backupCarriesLegacyPinVerifier(prefs: JSONObject): Boolean {
    return prefs.optBoolean("pinEnabled", false) &&
        prefs.optString("pinHash", "").isNotEmpty()
}

/**
 * True when [PinVault.protectFreshImport] will call [PinVault.enable].
 * [dataKey] is the in-memory key, null until a PIN is unlocked.
 */
internal fun isKeylessInstall(pinConfigured: Boolean, dataKey: ByteArray?): Boolean {
    return dataKey == null && !pinConfigured
}

/**
 * [legacyVerifierAccepted] is true only after the user entered a PIN that
 * matched an older backup's verifier. [deviceHadPin] is this install
 * before that import wrote anything: the legacy enable runs during the
 * import, so the follow-up must not read PIN state again afterwards.
 */
internal fun restorePinFollowUp(
    legacyVerifierAccepted: Boolean,
    deviceHadPin: Boolean,
): RestorePinFollowUp = when {
    legacyVerifierAccepted && deviceHadPin -> RestorePinFollowUp.NOTICE_KEPT
    legacyVerifierAccepted -> RestorePinFollowUp.NONE
    deviceHadPin -> RestorePinFollowUp.NONE
    else -> RestorePinFollowUp.OFFER_SET
}

/**
 * #420: first PIN on a keyless install.
 *
 * The same vault calls Settings makes after the confirm pad.
 * [PinVault.enable] mints the data key. [PinVault.upgradeExisting]
 * rewrites payload files that were just restored in the open, which
 * a later read would refuse once a PIN is configured. This does not
 * take the "already unlocked" arm of [PinVault.enable]: the caller
 * only reaches here when [isKeylessInstall] is true, so an existing
 * device PIN is not wrapped again.
 */
internal fun enablePinOnKeylessInstall(
    vault: PinVault,
    pin: String,
    records: List<PayloadRecord> = emptyList(),
) {
    vault.enable(pin)
    if (!vault.upgradeExisting(records)) {
        throw IllegalStateException("Could not update saved payloads")
    }
    vault.markPayloadsCurrent()
}
