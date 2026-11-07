package com.samourai.sentinel.ui.settings

import android.app.Application
import android.app.Dialog
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.LockScreenDialog
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import timber.log.Timber

/**
 * #420: the restore PIN prompt is a dialog, and Not now leaves the
 * install without a PIN. Set PIN opens the existing pad. The confirm
 * step is what calls enable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestorePinPromptTest {

    private lateinit var prefs: PrefsUtil
    private lateinit var access: AccessFactory
    private var controller: ActivityController<ImportBackUpActivity>? = null

    @Before
    fun setUp() {
        ShadowToast.reset()
        ShadowDialog.reset()
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        prefs = PrefsUtil(app)
        prefs.clearAll()
        access = AccessFactory.getInstance(app)
        clearAccess()
        access.admitSession()
        access.updatePin()
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                    single { access }
                },
            )
        }
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        clearAccess()
        prefs.clearAll()
        if (GlobalContext.getOrNull() != null) stopKoin()
        ShadowToast.reset()
        ShadowDialog.reset()
    }

    @Test
    fun currentFormatImportLeavesAKeylessInstallWithoutAPinAndOffersOne() {
        val incoming = JSONObject()
            .put("selectedCurrency", "GBP")
            .put("pinHash", "from-backup")
            .put("pinEnabled", true)
            .put("pinKeyMaterial", "from-backup-material")
        prefs.import(incoming)
        assertEquals("GBP", prefs.selectedCurrency)
        assertFalse(prefs.isPinConfigured())
        assertEquals("", prefs.pinHash)
        assertEquals(false, prefs.pinEnabled)
        assertEquals("", prefs.pinKeyMaterial)

        val activity = launch()
        activity.applyRestorePinFollowUp(
            restorePinFollowUp(
                legacyVerifierAccepted = false,
                deviceHadPin = prefs.isPinConfigured() || access.dataKey != null,
            ),
        )
        val dialog = onlyAlert()
        val text = dialogText(dialog)
        assertTrue(text.contains(activity.getString(R.string.restore_pin_offer_title)))
        assertTrue(text.contains(activity.getString(R.string.restore_pin_offer_message)))
        assertFalse(text.contains("from-backup"))
        assertEquals(
            activity.getString(R.string.restore_pin_set),
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString(),
        )
        assertEquals(
            activity.getString(R.string.restore_pin_not_now),
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString(),
        )
        assertNull(activity.supportFragmentManager.findFragmentByTag(ImportBackUpActivity.TAG_RESTORE_PIN_SET))
    }

    @Test
    fun notNowLeavesTheInstallWithoutAPin() {
        val activity = launch()
        activity.applyRestorePinFollowUp(RestorePinFollowUp.OFFER_SET)
        val dialog = onlyAlert()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertFalse(prefs.isPinConfigured())
        assertTrue(access.dataKey == null)
        assertNull(activity.supportFragmentManager.findFragmentByTag(ImportBackUpActivity.TAG_RESTORE_PIN_SET))
        assertNull(activity.supportFragmentManager.findFragmentByTag(ImportBackUpActivity.TAG_RESTORE_PIN_CONFIRM))

        controller = controller!!.recreate()
        idle()
        assertTrue(shownAlerts().isEmpty())
        assertFalse(prefs.isPinConfigured())
    }

    @Test
    fun cancellingTheOfferLeavesTheInstallWithoutAPin() {
        val activity = launch()
        activity.applyRestorePinFollowUp(RestorePinFollowUp.OFFER_SET)
        val dialog = onlyAlert()
        dialog.cancel()
        idle()
        assertFalse(dialog.isShowing)
        assertFalse(prefs.isPinConfigured())
        assertTrue(access.dataKey == null)
        assertNull(activity.supportFragmentManager.findFragmentByTag(ImportBackUpActivity.TAG_RESTORE_PIN_SET))
    }

    @Test
    fun setPinOpensTheExistingPadAndAMismatchDoesNotEnable() {
        val activity = launch()
        activity.applyRestorePinFollowUp(RestorePinFollowUp.OFFER_SET)
        onlyAlert().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()

        val setPad = activity.supportFragmentManager
            .findFragmentByTag(ImportBackUpActivity.TAG_RESTORE_PIN_SET) as LockScreenDialog
        assertEquals(LockScreenDialog.PURPOSE_SET, setPad.purpose)
        assertEquals(activity.getString(R.string.create_pin), setPad.requireArguments().getString(LockScreenDialog.ARG_MESSAGE))

        activity.supportFragmentManager.setFragmentResult(
            ImportBackUpActivity.REQUEST_KEY_RESTORE_PIN_SET,
            Bundle().apply { putString(LockScreenDialog.RESULT_PIN, PIN) },
        )
        idle()
        val confirm = activity.supportFragmentManager
            .findFragmentByTag(ImportBackUpActivity.TAG_RESTORE_PIN_CONFIRM) as LockScreenDialog
        assertEquals(LockScreenDialog.PURPOSE_CONFIRM, confirm.purpose)
        assertEquals(
            activity.getString(R.string.confirm_pin),
            confirm.requireArguments().getString(LockScreenDialog.ARG_MESSAGE),
        )

        ShadowToast.reset()
        activity.supportFragmentManager.setFragmentResult(
            ImportBackUpActivity.REQUEST_KEY_RESTORE_PIN_CONFIRM,
            Bundle().apply { putString(LockScreenDialog.RESULT_PIN, OTHER) },
        )
        idle()
        assertEquals(
            activity.getString(R.string.restore_pin_mismatch),
            ShadowToast.getTextOfLatestToast()?.toString(),
        )
        assertFalse(prefs.isPinConfigured())
        assertTrue(access.dataKey == null)
        assertNotNull(
            activity.supportFragmentManager.findFragmentByTag(ImportBackUpActivity.TAG_RESTORE_PIN_CONFIRM),
        )
    }

    @Test
    fun dismissingThePadAfterTheFirstEntryDoesNotEnable() {
        val activity = launch()
        activity.applyRestorePinFollowUp(RestorePinFollowUp.OFFER_SET)
        onlyAlert().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        activity.supportFragmentManager.setFragmentResult(
            ImportBackUpActivity.REQUEST_KEY_RESTORE_PIN_SET,
            Bundle().apply { putString(LockScreenDialog.RESULT_PIN, PIN) },
        )
        idle()
        val confirm = activity.supportFragmentManager
            .findFragmentByTag(ImportBackUpActivity.TAG_RESTORE_PIN_CONFIRM) as LockScreenDialog
        confirm.dismiss()
        idle()
        assertFalse(prefs.isPinConfigured())
        assertTrue(access.dataKey == null)
        assertEquals("", prefs.pinHash)
        assertEquals("", prefs.pinKeyMaterial)
    }

    @Test
    fun confirmingThePadEnablesAndDoesNotLogThePin() {
        val activity = launch()
        activity.applyRestorePinFollowUp(RestorePinFollowUp.OFFER_SET)
        onlyAlert().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        activity.supportFragmentManager.setFragmentResult(
            ImportBackUpActivity.REQUEST_KEY_RESTORE_PIN_SET,
            Bundle().apply { putString(LockScreenDialog.RESULT_PIN, PIN) },
        )
        idle()
        assertEquals(
            LockScreenDialog.PURPOSE_CONFIRM,
            (activity.supportFragmentManager
                .findFragmentByTag(ImportBackUpActivity.TAG_RESTORE_PIN_CONFIRM) as LockScreenDialog)
                .purpose,
        )

        val lines = ArrayList<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                lines.add(message)
                if (t != null) lines.add(t.toString())
            }
        }
        Timber.plant(tree)
        try {
            activity.supportFragmentManager.setFragmentResult(
                ImportBackUpActivity.REQUEST_KEY_RESTORE_PIN_CONFIRM,
                Bundle().apply { putString(LockScreenDialog.RESULT_PIN, PIN) },
            )
            awaitPinConfigured()
        } finally {
            Timber.uproot(tree)
        }
        idle()

        assertEquals(true, prefs.pinEnabled)
        assertTrue(prefs.pinHash.isNullOrBlank())
        val material = checkNotNull(prefs.pinKeyMaterial)
        assertTrue(material.isNotBlank())
        assertEquals("argon2id", JSONObject(material).getString("kdf"))
        assertFalse(material.contains(PIN))
        val logged = lines.joinToString("\n")
        assertFalse(logged.contains(PIN))
        assertFalse(logged.contains(material))
        assertNull(activity.supportFragmentManager.findFragmentByTag(ImportBackUpActivity.TAG_RESTORE_PIN_CONFIRM))
        assertNull(activity.supportFragmentManager.findFragmentByTag(ImportBackUpActivity.TAG_RESTORE_PIN_SET))
    }

    @Test
    fun legacyKeptNoticeDoesNotChangeTheDevicePin() {
        prefs.pinKeyMaterial = "existing-wrap"
        prefs.pinEnabled = true
        prefs.pinHash = ""
        val activity = launch()
        activity.applyRestorePinFollowUp(RestorePinFollowUp.NOTICE_KEPT)
        val dialog = onlyAlert()
        val text = dialogText(dialog)
        assertTrue(text.contains(activity.getString(R.string.restore_pin_kept_title)))
        assertTrue(text.contains(activity.getString(R.string.restore_pin_kept_message)))
        assertFalse(text.contains("existing-wrap"))
        assertEquals(
            activity.getString(R.string.ok),
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString(),
        )
        assertEquals("existing-wrap", prefs.pinKeyMaterial)
        assertEquals(true, prefs.pinEnabled)
        assertNull(activity.supportFragmentManager.findFragmentByTag(ImportBackUpActivity.TAG_RESTORE_PIN_SET))

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        controller = controller!!.recreate()
        idle()
        assertTrue(shownAlerts().isEmpty())
        assertEquals("existing-wrap", prefs.pinKeyMaterial)
    }

    @Test
    fun noFollowUpShowsNoDialog() {
        val activity = launch()
        activity.applyRestorePinFollowUp(RestorePinFollowUp.NONE)
        idle()
        assertTrue(shownAlerts().isEmpty())
        assertFalse(prefs.isPinConfigured())
    }

    @Test
    fun offerSurvivesRotationUntilItIsAnswered() {
        launch()
        controller!!.get().applyRestorePinFollowUp(RestorePinFollowUp.OFFER_SET)
        assertTrue(onlyAlert().isShowing)
        controller = controller!!.recreate()
        idle()
        val activity = controller!!.get()
        val text = dialogText(onlyAlert())
        assertTrue(text.contains(activity.getString(R.string.restore_pin_offer_title)))
        assertTrue(text.contains(activity.getString(R.string.restore_pin_offer_message)))
        assertFalse(prefs.isPinConfigured())
    }

    @Test
    fun offerStringsDoNotCarryAPin() {
        val activity = launch()
        val copy = listOf(
            R.string.restore_pin_offer_title,
            R.string.restore_pin_offer_message,
            R.string.restore_pin_set,
            R.string.restore_pin_not_now,
            R.string.restore_pin_kept_title,
            R.string.restore_pin_kept_message,
            R.string.restore_pin_mismatch,
            R.string.restore_pin_save_failed,
        ).joinToString("\n") { activity.getString(it) }
        assertFalse(copy.contains(PIN))
        assertFalse(copy.contains("from-backup"))
        assertTrue(copy.contains("PIN"))
        assertTrue(copy.contains("Not now"))
    }

    private fun launch(): ImportBackUpActivity {
        val created = Robolectric.buildActivity(ImportBackUpActivity::class.java).setup()
        controller = created
        return created.get()
    }

    private fun onlyAlert(): AlertDialog {
        val alerts = shownAlerts()
        assertEquals(1, alerts.size)
        return alerts.single()
    }

    private fun shownAlerts(): List<AlertDialog> {
        // getShownDialogs keeps dismissed windows. Only the ones still up count.
        return ShadowDialog.getShownDialogs()
            .filterIsInstance<AlertDialog>()
            .filter { it.isShowing }
    }

    private fun dialogText(dialog: Dialog): String {
        val root = dialog.window?.decorView ?: return ""
        val parts = ArrayList<String>()
        val pending = ArrayDeque<View>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (view is TextView) {
                val text = view.text?.toString().orEmpty()
                if (text.isNotEmpty()) parts.add(text)
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) pending.add(view.getChildAt(i))
            }
        }
        return parts.joinToString("\n")
    }

    private fun idle() {
        val activity = controller?.get()
        activity?.supportFragmentManager?.executePendingTransactions()
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        looper.runToEndOfTasks()
        activity?.supportFragmentManager?.executePendingTransactions()
    }

    private fun awaitPinConfigured() {
        val deadline = System.currentTimeMillis() + 90_000
        while (!prefs.isPinConfigured() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        assertTrue("PIN was not saved from the confirm pad", prefs.isPinConfigured())
    }

    private fun clearAccess() {
        access.clearDataKey()
        access.pin = null
        access.setPinProtected(false)
        access.setIsLoggedIn(false)
    }

    companion object {
        private const val PIN = "13579"
        private const val OTHER = "97531"
    }
}
