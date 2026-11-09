package com.samourai.sentinel.ui.settings

import android.app.Application
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
import com.samourai.sentinel.ui.views.PinEntryView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/**
 * #420: a restore PIN pad covers the import screen, and the confirm
 * pad covers the set pad. Accessibility services must reach only the
 * pad that is actually on top. Backspace is an image, so it needs a
 * localized name on every pad that shares the keypad.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestorePinPadAccessibilityTest {

    private lateinit var prefs: PrefsUtil
    private lateinit var access: AccessFactory
    private var controller: ActivityController<ImportBackUpActivity>? = null

    @Before
    fun setUp() {
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
        ShadowDialog.reset()
    }

    @Test
    fun restoreSetPadHidesImportContentAndConfirmPadHidesTheSetPad() {
        val activity = launch()
        revealImportControls(activity)
        assertImportContentExposed(activity, exposed = true)
        assertEquals(0, confirmControls(activity).size)

        activity.applyRestorePinFollowUp(RestorePinFollowUp.OFFER_SET)
        shownAlert().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()

        val setPad = pad(activity, ImportBackUpActivity.TAG_RESTORE_PIN_SET)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, setPad.requireView().importantForAccessibility)
        assertImportContentExposed(activity, exposed = false)
        assertPadKeysExposed(setPad, exposed = true)
        assertEquals(
            activity.getString(R.string.pin_pad_backspace),
            setPad.requireView().findViewById<View>(R.id.tback).contentDescription,
        )
        pressDigits(setPad, PIN)
        assertEquals(View.VISIBLE, setPad.requireView().findViewById<View>(R.id.tconfirm).visibility)
        assertEquals(listOf(setPad), padsOf(confirmControls(activity)))

        activity.supportFragmentManager.setFragmentResult(
            ImportBackUpActivity.REQUEST_KEY_RESTORE_PIN_SET,
            Bundle().apply { putString(LockScreenDialog.RESULT_PIN, PIN) },
        )
        idle()

        val confirm = pad(activity, ImportBackUpActivity.TAG_RESTORE_PIN_CONFIRM)
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            setPad.requireView().importantForAccessibility,
        )
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, confirm.requireView().importantForAccessibility)
        assertImportContentExposed(activity, exposed = false)
        assertPadKeysExposed(setPad, exposed = false)
        assertPadKeysExposed(confirm, exposed = true)
        // The set pad's confirm control is still visible. It must not be reachable.
        assertEquals(View.VISIBLE, setPad.requireView().findViewById<View>(R.id.tconfirm).visibility)
        assertEquals(listOf(confirm), padsOf(confirmControls(activity)))
        assertEquals(
            activity.getString(R.string.pin_pad_backspace),
            confirm.requireView().findViewById<View>(R.id.tback).contentDescription,
        )

        pressDigits(confirm, PIN)
        assertEquals(View.VISIBLE, confirm.requireView().findViewById<View>(R.id.tconfirm).visibility)
        val exposedConfirms = confirmControls(activity).filter { it.visibility == View.VISIBLE }
        assertEquals(1, exposedConfirms.size)
        assertEquals(confirm.requireView(), padRootOf(exposedConfirms.single()))

        confirm.dismiss()
        idle()
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, setPad.requireView().importantForAccessibility)
        assertImportContentExposed(activity, exposed = false)
        assertPadKeysExposed(setPad, exposed = true)
        assertEquals(listOf(setPad), padsOf(confirmControls(activity)))

        setPad.dismiss()
        idle()
        assertTrue(activity.supportFragmentManager.findFragmentByTag(ImportBackUpActivity.TAG_RESTORE_PIN_SET) == null)
        assertImportContentExposed(activity, exposed = true)
        assertEquals(0, confirmControls(activity).size)
    }

    @Test
    fun importPinPadHidesImportContentUntilItCloses() {
        val activity = launch()
        revealImportControls(activity)
        val dialog = LockScreenDialog.newInstance(
            message = "Enter pin code",
            requestKey = ImportBackUpActivity.REQUEST_KEY_IMPORT_PIN,
            purpose = LockScreenDialog.PURPOSE_IMPORT,
        )
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, dialog, ImportBackUpActivity.TAG_IMPORT_PIN)
            .commit()
        idle()

        val importPad = pad(activity, ImportBackUpActivity.TAG_IMPORT_PIN)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, importPad.requireView().importantForAccessibility)
        assertImportContentExposed(activity, exposed = false)
        assertPadKeysExposed(importPad, exposed = true)
        assertEquals(listOf(importPad), padsOf(confirmControls(activity)))
        assertEquals(
            activity.getString(R.string.pin_pad_backspace),
            importPad.requireView().findViewById<View>(R.id.tback).contentDescription,
        )

        importPad.dismiss()
        idle()
        assertTrue(activity.supportFragmentManager.findFragmentByTag(ImportBackUpActivity.TAG_IMPORT_PIN) == null)
        assertImportContentExposed(activity, exposed = true)
        assertEquals(0, confirmControls(activity).size)
    }

    @Test
    fun restoredSetPadStaysIsolatedAfterRecreation() {
        val activity = launch()
        revealImportControls(activity)
        activity.applyRestorePinFollowUp(RestorePinFollowUp.OFFER_SET)
        shownAlert().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertNotNull(activity.supportFragmentManager.findFragmentByTag(ImportBackUpActivity.TAG_RESTORE_PIN_SET))

        controller = controller!!.recreate()
        idle()
        val recreated = controller!!.get()
        revealImportControls(recreated)
        val setPad = pad(recreated, ImportBackUpActivity.TAG_RESTORE_PIN_SET)
        assertImportContentExposed(recreated, exposed = false)
        assertPadKeysExposed(setPad, exposed = true)
        assertEquals(listOf(setPad), padsOf(confirmControls(recreated)))
    }

    @Test
    fun alreadyHiddenOrExcludedSiblingKeepsItsAccessibilityMode() {
        val activity = launch()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val excluded = View(activity)
        excluded.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val alreadyHidden = View(activity)
        alreadyHidden.importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val explicit = View(activity)
        explicit.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        content.addView(excluded)
        content.addView(alreadyHidden)
        content.addView(explicit)

        val dialog = LockScreenDialog.newInstance(
            message = "Enter pin code",
            requestKey = ImportBackUpActivity.REQUEST_KEY_IMPORT_PIN,
            purpose = LockScreenDialog.PURPOSE_IMPORT,
        )
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, dialog, ImportBackUpActivity.TAG_IMPORT_PIN)
            .commit()
        idle()

        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            excluded.importantForAccessibility,
        )
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            alreadyHidden.importantForAccessibility,
        )
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            explicit.importantForAccessibility,
        )
        assertImportContentExposed(activity, exposed = false)

        dialog.dismiss()
        idle()
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, excluded.importantForAccessibility)
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            alreadyHidden.importantForAccessibility,
        )
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, explicit.importantForAccessibility)
        assertImportContentExposed(activity, exposed = true)
    }

    @Test
    fun backspaceOnTheSharedKeypadUsesTheLocalizedName() {
        val activity = launch()
        val keypad = PinEntryView(activity)
        assertEquals(
            activity.getString(R.string.pin_pad_backspace),
            keypad.findViewById<View>(R.id.tback).contentDescription,
        )
        assertEquals("Backspace", activity.getString(R.string.pin_pad_backspace))
    }

    @Test
    @Config(sdk = [34], qualifiers = "fr")
    fun backspaceNameFollowsTheDeviceLanguage() {
        val activity = launch()
        val keypad = PinEntryView(activity)
        assertEquals("Retour arrière", activity.getString(R.string.pin_pad_backspace))
        assertEquals(
            activity.getString(R.string.pin_pad_backspace),
            keypad.findViewById<View>(R.id.tback).contentDescription,
        )
    }

    private fun revealImportControls(activity: ImportBackUpActivity) {
        activity.findViewById<View>(R.id.importSentinelBackUpLayout).visibility = View.VISIBLE
    }

    private fun assertImportContentExposed(activity: ImportBackUpActivity, exposed: Boolean) {
        val screen = screenRoot(activity)
        assertEquals(
            if (exposed) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            screen.importantForAccessibility,
        )
        val ids = intArrayOf(
            R.id.importPayloadTextView,
            R.id.importChoosePayloadBtn,
            R.id.importPastePayloadBtn,
            R.id.importCollections,
            R.id.importPrefs,
            R.id.importDojo,
            R.id.importClearExisting,
        )
        for (id in ids) {
            val view = activity.findViewById<View>(id)
            assertEquals(exposed, !hiddenFromAccessibility(view))
        }
    }

    private fun assertPadKeysExposed(pad: LockScreenDialog, exposed: Boolean) {
        val key = pad.requireView().findViewById<View>(R.id.ta)
        val backspace = pad.requireView().findViewById<View>(R.id.tback)
        assertEquals(exposed, !hiddenFromAccessibility(key))
        assertEquals(exposed, !hiddenFromAccessibility(backspace))
    }

    private fun screenRoot(activity: ImportBackUpActivity): View {
        var view: View = activity.findViewById(R.id.importPastePayloadBtn)
        while (view.parent is View && (view.parent as View).id != android.R.id.content) {
            view = view.parent as View
        }
        return view
    }

    /**
     * Confirm controls that accessibility services can still reach.
     * A control under NO_HIDE_DESCENDANTS is not one of them, even
     * when the widget itself is still visible.
     */
    private fun confirmControls(activity: ImportBackUpActivity): List<View> {
        val found = ArrayList<View>()
        fun walk(view: View) {
            if (view.id == R.id.tconfirm && !hiddenFromAccessibility(view)) found.add(view)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(activity.window.decorView)
        return found
    }

    private fun padsOf(controls: List<View>): List<LockScreenDialog> {
        return controls.map { control ->
            var view: View? = padRootOf(control)
            var pad: LockScreenDialog? = null
            val activity = controller!!.get()
            for (tag in listOf(
                ImportBackUpActivity.TAG_IMPORT_PIN,
                ImportBackUpActivity.TAG_RESTORE_PIN_SET,
                ImportBackUpActivity.TAG_RESTORE_PIN_CONFIRM,
            )) {
                val fragment = activity.supportFragmentManager.findFragmentByTag(tag) as? LockScreenDialog
                if (fragment?.view === view) pad = fragment
            }
            checkNotNull(pad) { "confirm control is not on a known pad" }
            pad
        }
    }

    private fun padRootOf(control: View): View {
        var view = control
        while (view.parent is View && (view.parent as View).id != android.R.id.content) {
            val parent = view.parent as View
            if (parent.id == android.R.id.content) break
            view = parent
        }
        return view
    }

    private fun hiddenFromAccessibility(view: View): Boolean {
        val self = view.importantForAccessibility
        if (self == View.IMPORTANT_FOR_ACCESSIBILITY_NO ||
            self == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        ) {
            return true
        }
        var parent = view.parent
        while (parent is View) {
            if (parent.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    private fun pressDigits(pad: LockScreenDialog, digits: String) {
        for (ch in digits) {
            digit(pad, ch.toString()).performClick()
        }
    }

    private fun digit(pad: LockScreenDialog, label: String): View {
        val matches = ArrayList<View>()
        fun walk(view: View) {
            if (view is TextView && view.text?.toString() == label) matches.add(view)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(pad.requireView())
        assertEquals(label, 1, matches.size)
        return matches.single()
    }

    private fun pad(activity: ImportBackUpActivity, tag: String): LockScreenDialog {
        return activity.supportFragmentManager.findFragmentByTag(tag) as LockScreenDialog
    }

    private fun shownAlert(): AlertDialog {
        val alerts = ShadowDialog.getShownDialogs()
            .filterIsInstance<AlertDialog>()
            .filter { it.isShowing }
        assertEquals(1, alerts.size)
        return alerts.single()
    }

    private fun launch(): ImportBackUpActivity {
        val created = Robolectric.buildActivity(ImportBackUpActivity::class.java).setup()
        controller = created
        return created.get()
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

    private fun clearAccess() {
        access.clearDataKey()
        access.pin = null
        access.setPinProtected(false)
        access.setIsLoggedIn(false)
    }

    companion object {
        private const val PIN = "13579"
    }
}
