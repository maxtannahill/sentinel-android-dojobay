package com.samourai.sentinel.ui.settings

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.samourai.sentinel.R
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.wallet.crypto.AESUtil
import com.samourai.wallet.crypto.BackupCodec
import com.samourai.wallet.util.CharSequenceX
import org.bouncycastle.util.encoders.Base64
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * SET-06 follow-up: structurally damaged or truncated Sentinel backups are
 * caught at paste time before collection checkboxes appear, while valid
 * envelopes with damaged inner JSON report damaged/incomplete after decrypt
 * and valid backups with a wrong password still report Incorrect password.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportCorruptBackupTest {

    private lateinit var app: Application
    private lateinit var prefs: PrefsUtil
    private var savedPinHash: String? = null
    private var savedPinKey: String? = null
    private var savedPinEnabled: Boolean? = null
    private var savedAdmitted = false
    private var savedLastPin = 0L
    private var controller: ActivityController<ImportBackUpActivity>? = null

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        app = ApplicationProvider.getApplicationContext()
        prefs = PrefsUtil(app)
        savedPinHash = prefs.pinHash
        savedPinKey = prefs.pinKeyMaterial
        savedPinEnabled = prefs.pinEnabled
        prefs.pinHash = ""
        prefs.pinKeyMaterial = ""
        prefs.pinEnabled = false
        savedAdmitted = staticBoolean("sessionAdmitted")
        savedLastPin = staticLong("lastPin")
        val access = AccessFactory.getInstance(app)
        access.admitSession()
        access.updatePin()
        startKoin {
            androidContext(app)
            modules(module {
                single { prefs }
                single { access }
            })
        }
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        if (GlobalContext.getOrNull() != null) stopKoin()
        prefs.pinHash = savedPinHash
        prefs.pinKeyMaterial = savedPinKey
        prefs.pinEnabled = savedPinEnabled
        staticBoolean("sessionAdmitted", savedAdmitted)
        staticLong("lastPin", savedLastPin)
    }

    @Test
    fun `pasting truncated legacy backup hides checkboxes and reports damaged`() {
        val activity = launch()
        val valid = legacyV2Envelope(VALID_INNER)
        val truncated = valid.substring(0, valid.length / 2) + "\"}"

        pasteAndAwait(activity, truncated) { snackbarTexts(activity).isNotEmpty() }

        assertEquals(View.GONE, activity.findViewById<View>(R.id.importSentinelBackUpLayout).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.importStartBtn).visibility)
        assertFalse(activity.findViewById<View>(R.id.importStartBtn).isEnabled)
        assertEquals("", activity.findViewById<TextView>(R.id.importPayloadTextView).text.toString())
        assertEquals(
            listOf(activity.getString(R.string.backup_damaged_or_incomplete)),
            snackbarTexts(activity),
        )
    }

    @Test
    fun `pasting truncated format 3 or corrupt base64 backup hides checkboxes and reports damaged`() {
        val activity = launch()
        val env = BackupCodec.encrypt(VALID_INNER, PASSWORD, 55, 1_700_000_000_000L)
        val corruptPayload = JSONObject(env).apply {
            val raw = getString("payload")
            put("payload", raw.substring(0, raw.length / 2 - 1))
        }.toString()

        pasteAndAwait(activity, corruptPayload) { snackbarTexts(activity).isNotEmpty() }

        assertEquals(View.GONE, activity.findViewById<View>(R.id.importSentinelBackUpLayout).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.importStartBtn).visibility)
        assertEquals(
            listOf(activity.getString(R.string.backup_damaged_or_incomplete)),
            snackbarTexts(activity),
        )

        val badChar = JSONObject(env).apply {
            val raw = getString("payload")
            put("payload", "!" + raw.substring(1))
        }.toString()
        pasteAndAwait(activity, badChar) {
            snackbarTexts(activity).lastOrNull() == activity.getString(R.string.backup_damaged_or_incomplete)
        }
        assertEquals(View.GONE, activity.findViewById<View>(R.id.importSentinelBackUpLayout).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.importStartBtn).visibility)
    }

    @Test
    fun `pasting truncated json without closing brace disarms and reports damaged`() {
        val activity = launch()
        val valid = BackupCodec.encrypt(VALID_INNER, PASSWORD, 55, 1_700_000_000_000L)
        pasteAndAwait(activity, valid) {
            activity.findViewById<View>(R.id.importSentinelBackUpLayout).visibility == View.VISIBLE
        }

        val brokenJson = valid.substring(0, valid.length / 2)
        pasteAndAwait(activity, brokenJson) {
            activity.findViewById<View>(R.id.importSentinelBackUpLayout).visibility == View.GONE &&
                snackbarTexts(activity).isNotEmpty()
        }

        assertEquals(View.GONE, activity.findViewById<View>(R.id.importStartBtn).visibility)
        assertFalse(activity.findViewById<View>(R.id.importStartBtn).isEnabled)
        assertEquals("", activity.findViewById<TextView>(R.id.importPayloadTextView).text.toString())
        assertEquals(
            listOf(activity.getString(R.string.backup_damaged_or_incomplete)),
            snackbarTexts(activity),
        )
    }

    @Test
    fun `valid backup shows checkboxes and wrong password reports incorrect password`() {
        val activity = launch()
        val valid = BackupCodec.encrypt(VALID_INNER, PASSWORD, 55, 1_700_000_000_000L)
        pasteAndAwait(activity, valid) {
            activity.findViewById<View>(R.id.importSentinelBackUpLayout).visibility == View.VISIBLE
        }

        val startBtn = activity.findViewById<Button>(R.id.importStartBtn)
        assertEquals(View.VISIBLE, startBtn.visibility)
        assertTrue(startBtn.isEnabled)
        assertTrue(activity.findViewById<TextView>(R.id.importPayloadTextView).text.isNotEmpty())

        val pwLayout = activity.findViewById<TextInputLayout>(R.id.importPasswordInputLayout)
        activity.findViewById<TextInputEditText>(R.id.importPasswordInput).setText("wrong-password")
        startBtn.performClick()
        awaitCondition { pwLayout.error != null }

        assertEquals(activity.getString(R.string.backup_password_wrong), pwLayout.error.toString())
        assertNotEquals("Imported \u2713", startBtn.text.toString())
    }

    @Test
    fun `decrypted backup with truncated inner json reports damaged instead of wrong password`() {
        val activity = launch()
        val damagedInner = BackupCodec.encrypt("""{"collections":[""", PASSWORD, 55, 1_700_000_000_000L)
        pasteAndAwait(activity, damagedInner) {
            activity.findViewById<View>(R.id.importSentinelBackUpLayout).visibility == View.VISIBLE
        }

        val startBtn = activity.findViewById<Button>(R.id.importStartBtn)
        val pwLayout = activity.findViewById<TextInputLayout>(R.id.importPasswordInputLayout)
        activity.findViewById<TextInputEditText>(R.id.importPasswordInput).setText(PASSWORD)
        startBtn.performClick()
        awaitCondition { pwLayout.error != null }

        assertEquals(
            activity.getString(R.string.backup_damaged_or_incomplete),
            pwLayout.error.toString(),
        )
        assertNotEquals("Imported \u2713", startBtn.text.toString())
    }

    @Test
    fun `corrupted ciphertext byte in structurally valid envelope fails cleanly without importing`() {
        val activity = launch()
        val env = JSONObject(BackupCodec.encrypt(VALID_INNER, PASSWORD, 55, 1_700_000_000_000L))
        val blob = Base64.decode(env.getString("payload"))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        env.put("payload", Base64.toBase64String(blob))

        pasteAndAwait(activity, env.toString()) {
            activity.findViewById<View>(R.id.importSentinelBackUpLayout).visibility == View.VISIBLE
        }

        val startBtn = activity.findViewById<Button>(R.id.importStartBtn)
        val pwLayout = activity.findViewById<TextInputLayout>(R.id.importPasswordInputLayout)
        activity.findViewById<TextInputEditText>(R.id.importPasswordInput).setText(PASSWORD)
        startBtn.performClick()
        awaitCondition { pwLayout.error != null }

        // With AES-GCM (and AES-CBC), a single-bit ciphertext tamper is
        // cryptographically indistinguishable from a wrong password at tag/padding check.
        assertEquals(activity.getString(R.string.backup_password_wrong), pwLayout.error.toString())
        assertNotEquals("Imported \u2713", startBtn.text.toString())
    }

    private fun pasteAndAwait(
        activity: ImportBackUpActivity,
        text: String,
        predicate: () -> Boolean,
    ) {
        val cm = app.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("backup", text))
        activity.findViewById<View>(R.id.importPastePayloadBtn).performClick()
        awaitCondition(predicate = predicate)
    }

    private fun awaitCondition(timeoutMs: Long = 10_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            if (predicate()) return
            Thread.sleep(20)
        }
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertTrue("Timed out waiting for condition", predicate())
    }

    private fun snackbarTexts(activity: ImportBackUpActivity): List<String> {
        val found = ArrayList<String>()
        fun walk(view: View) {
            if (view.id == R.id.mainText && view is TextView && view.isShown) {
                val text = view.text?.toString().orEmpty()
                if (text.isNotEmpty()) found.add(text)
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(activity.window.decorView)
        return found
    }

    private fun legacyV2Envelope(plain: String): String {
        val cipher = AESUtil.encryptSHA256(
            plain,
            CharSequenceX(PASSWORD),
            AESUtil.DefaultPBKDF2HMACSHA256Iterations,
        ).replace("\n", "")
        return JSONObject()
            .put("version", 2)
            .put("time", 1_700_000_000_000L)
            .put("payload", cipher)
            .toString()
    }

    private fun launch(): ImportBackUpActivity {
        val created = Robolectric.buildActivity(ImportBackUpActivity::class.java).setup()
        controller = created
        return created.get()
    }

    private fun staticBoolean(name: String): Boolean {
        val field = AccessFactory::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.getBoolean(null)
    }

    private fun staticBoolean(name: String, value: Boolean) {
        val field = AccessFactory::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.setBoolean(null, value)
    }

    private fun staticLong(name: String): Long {
        val field = AccessFactory::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.getLong(null)
    }

    private fun staticLong(name: String, value: Long) {
        val field = AccessFactory::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.setLong(null, value)
    }

    companion object {
        private const val PASSWORD = "correct horse"
        private const val VALID_INNER = """{"collections":[],"prefs":{"selectedCurrency":"USD"}}"""
    }
}
