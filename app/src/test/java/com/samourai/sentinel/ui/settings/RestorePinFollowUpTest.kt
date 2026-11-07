package com.samourai.sentinel.ui.settings

import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.core.access.MemoryPinMaterialStore
import com.samourai.sentinel.core.access.NoAtRestSealer
import com.samourai.sentinel.core.access.PinVault
import com.samourai.sentinel.data.db.PayloadCipher
import com.samourai.sentinel.data.db.PayloadRecord
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.io.File

/**
 * #420: a current-format restore does not install a PIN. The follow-up
 * offers one on a keyless install. A legacy verifier still installs on
 * a keyless install, and on a device that already has a PIN the existing
 * wrap is kept.
 */
class RestorePinFollowUpTest {

    private val access = AccessFactory.getInstance(null)

    @Before
    fun reset() = clearAccess()

    @After
    fun cleanup() = clearAccess()

    @Test
    fun currentFormatOnAKeylessInstallOffersAndDoesNotEnable() {
        val store = MemoryPinMaterialStore()
        val deviceHadPin = !isKeylessInstall(store.isPinConfigured(), access.dataKey)
        assertFalse(deviceHadPin)
        assertEquals(
            RestorePinFollowUp.OFFER_SET,
            restorePinFollowUp(legacyVerifierAccepted = false, deviceHadPin = deviceHadPin),
        )
        assertFalse(store.isPinConfigured())
        assertTrue(access.dataKey == null)
    }

    @Test
    fun currentFormatOnADeviceThatAlreadyHasAPinSaysNothing() {
        assertEquals(
            RestorePinFollowUp.NONE,
            restorePinFollowUp(legacyVerifierAccepted = false, deviceHadPin = true),
        )
    }

    @Test
    fun acceptingTheOfferUsesEnableAndDoesNotLogThePin() {
        val dir = tempDir()
        val record = PayloadRecord(dir.absolutePath, "collections.payload")
        val plain = """{"collections":[{"id":"restored"}]}"""
        record.file.parentFile?.mkdirs()
        record.file.writeText(plain)

        val store = MemoryPinMaterialStore()
        val vault = PinVault(store, access, NoAtRestSealer)
        val lines = ArrayList<String>()
        val tree = capturingTree(lines)
        Timber.plant(tree)
        try {
            enablePinOnKeylessInstall(vault, NEW_PIN, listOf(record))
            assertTrue(store.pinEnabled == true)
            assertTrue(store.pinHash.isNullOrBlank())
            assertFalse(store.pinKeyMaterial.isNullOrBlank())
            val material = store.pinKeyMaterial!!
            val wrap = JSONObject(material)
            assertEquals("argon2id", wrap.getString("kdf"))
            assertEquals(1, wrap.getInt("v"))
            assertFalse(material.contains(NEW_PIN))
            assertTrue(PayloadCipher.isModern(record.file.readText()))
            assertEquals(plain, record.decrypt(record.file.readText()))
            val logged = lines.joinToString("\n")
            assertFalse(logged.contains(NEW_PIN))
            assertFalse(logged.contains(material))
        } finally {
            Timber.uproot(tree)
            dir.deleteRecursively()
        }
    }

    @Test
    fun legacyVerifierOnAKeylessInstallEnablesAndDoesNotOffer() {
        val store = MemoryPinMaterialStore()
        val vault = PinVault(store, access, NoAtRestSealer)
        val deviceHadPin = !isKeylessInstall(store.isPinConfigured(), access.dataKey)
        assertFalse(deviceHadPin)
        vault.protectFreshImport(NEW_PIN)
        assertTrue(store.isPinConfigured())
        assertTrue(store.pinEnabled == true)
        assertTrue(store.pinHash.isNullOrBlank())
        assertFalse(store.pinKeyMaterial!!.contains(NEW_PIN))
        assertEquals(
            RestorePinFollowUp.NONE,
            restorePinFollowUp(legacyVerifierAccepted = true, deviceHadPin = deviceHadPin),
        )
    }

    @Test
    fun legacyVerifierOnAPinnedDeviceKeepsTheExistingPin() {
        val store = MemoryPinMaterialStore()
        val vault = PinVault(store, access, NoAtRestSealer)
        vault.enable(EXISTING)
        val kept = store.pinKeyMaterial
        assertTrue(access.dataKey != null)
        val deviceHadPin = !isKeylessInstall(store.isPinConfigured(), access.dataKey)
        assertTrue(deviceHadPin)

        vault.protectFreshImport(BACKUP)
        assertEquals(kept, store.pinKeyMaterial)

        access.clearDataKey()
        access.pin = null
        access.setPinProtected(false)
        vault.protectFreshImport(BACKUP)
        assertEquals(kept, store.pinKeyMaterial)
        assertEquals(
            RestorePinFollowUp.NOTICE_KEPT,
            restorePinFollowUp(legacyVerifierAccepted = true, deviceHadPin = deviceHadPin),
        )
    }

    @Test
    fun legacyVerifierNeedsTheFlagAndAHash() {
        assertFalse(backupCarriesLegacyPinVerifier(JSONObject()))
        assertFalse(backupCarriesLegacyPinVerifier(JSONObject().put("pinEnabled", true)))
        assertFalse(backupCarriesLegacyPinVerifier(JSONObject().put("pinEnabled", false).put("pinHash", "abc")))
        assertFalse(backupCarriesLegacyPinVerifier(JSONObject().put("pinHash", "abc")))
        assertTrue(
            backupCarriesLegacyPinVerifier(
                JSONObject().put("pinEnabled", true).put("pinHash", "abc"),
            ),
        )
    }

    @Test
    fun keylessMatchesTheFreshImportEnableArm() {
        assertTrue(isKeylessInstall(pinConfigured = false, dataKey = null))
        assertFalse(isKeylessInstall(pinConfigured = true, dataKey = null))
        assertFalse(isKeylessInstall(pinConfigured = false, dataKey = byteArrayOf(1)))
    }

    @Test
    fun successfulImportWiresTheFollowUpAndTheLegacyEnable() {
        val launch = activitySource()
            .substringAfter("private fun launchSentinelImport")
            .substringBefore("private fun restart()")
        val beforeJob = launch.substringBefore("viewModel.viewModelScope.launch")
        assertTrue(beforeJob.contains("val pinFollowUp = restorePinFollowUp("))
        assertTrue(beforeJob.contains("legacyVerifierAccepted = freshPin != null"))
        val fresh = launch.substringAfter("if (freshPin != null)")
            .substringBefore("withContext(Dispatchers.Main)")
        assertTrue(fresh.contains("protectFreshImport"))
        val success = launch.substringAfter("if (it == null)")
            .substringBefore("} else if (it is CancellationException)")
        // #460: the success block defers the restart snackbar, then
        // applies the follow-up. The decision call stays on that path.
        assertTrue(success.contains("presentSentinelImportSuccess("))
        assertTrue(success.contains("pinFollowUp"))
        val presenter = activitySource()
            .substringAfter("fun presentSentinelImportSuccess")
            .substringBefore("fun applyRestorePinFollowUp")
        assertTrue(presenter.contains("applyRestorePinFollowUp(followUp)"))
        val afterSuccess = launch.substringAfter("} else if (it is CancellationException)")
        assertFalse(afterSuccess.contains("applyRestorePinFollowUp"))

        val gate = activitySource()
            .substringAfter("backupCarriesLegacyPinVerifier(payload.second)")
            .substringBefore("ImportType.SAMOURAI")
        assertTrue(gate.contains("validateHash"))
        assertTrue(gate.contains("launchSentinelImport(payload, networkPlan, entered)"))
        assertTrue(gate.contains("freshPin = null"))
    }

    @Test
    fun restoreSavePathDoesNotLogThePinOrTheKey() {
        val save = activitySource()
            .substringAfter("private fun onRestorePinConfirmed")
            .substringBefore("private fun dismissRestorePinPad")
        assertTrue(save.contains("enablePinOnKeylessInstall"))
        assertTrue(save.contains("LockScreenDialog.PURPOSE_CONFIRM"))
        assertTrue(save.contains("Could not save PIN after restore"))
        assertFalse(save.contains("\$chosen"))
        assertFalse(save.contains("\$pin"))
        assertFalse(save.contains("\$first"))
        assertFalse(save.contains("pinHash"))
        assertFalse(save.contains("pinKeyMaterial"))
    }

    private fun capturingTree(lines: MutableList<String>): Timber.Tree {
        return object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                lines.add(message)
                if (t != null) lines.add(t.toString())
            }
        }
    }

    private fun clearAccess() {
        access.clearDataKey()
        access.pin = null
        access.setPinProtected(false)
        access.setIsLoggedIn(false)
    }

    private fun tempDir(): File {
        val dir = File.createTempFile("restore-pin-", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    private fun activitySource(): String {
        val relative = "app/src/main/java/com/samourai/sentinel/ui/settings/ImportBackUpActivity.kt"
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }

    companion object {
        private const val NEW_PIN = "13579"
        private const val EXISTING = "24680"
        private const val BACKUP = "97531"
    }
}
