package com.samourai.sentinel.ui.fragments

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.invertedx.hummingbird.BbqrSkipReason
import com.invertedx.hummingbird.QrPerf
import com.samourai.sentinel.R
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.registry.RegistryType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class ImportLabelsScanBottomSheet : BottomSheetDialogFragment() {

    private var onLabelsDecoded: ((String) -> Unit)? = null
    private val scanFragment = ScanPubKeyFragment()
    private val labelGate = LabelScanGate()
    private val bbqrGate = Mutex()
    private var bbqrGeneration = 0
    private var conflictDialog: Dialog? = null

    fun setOnLabelsDecodedListener(callback: (String) -> Unit) {
        onLabelsDecoded = callback
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.import_labels_scan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scanFragment.setPasteVisible(false)
        // UR parts still report the fountain gauge through this listener.
        // BBQr parts report the header's exact count from the scan listener.
        scanFragment.setUrTransmissionListener { expected, processed, progress ->
            QrPerf.noteTransmission(expected, processed, progress)
        }
        scanFragment.setOnScanListener { payload ->
            // Join, inflate, and the label check run off this callback.
            // The callback stays on the camera thread and only schedules
            // the work. The camera runs until an import consumes the scan,
            // and it stays stopped while the BBQr conflict dialog is up.
            val generation = bbqrGeneration
            viewLifecycleOwner.lifecycleScope.launch {
                val action = try {
                    bbqrGate.withLock {
                        if (generation != bbqrGeneration) return@withLock null
                        receiveLabelFrame(payload, labelGate) {
                            this@launch.isActive && generation == bbqrGeneration
                        }
                    }
                } catch (abandoned: BbqrAbandoned) {
                    if (!isActive) throw CancellationException("BBQr")
                    null
                }
                if (action == null || !isActive || generation != bbqrGeneration) return@launch
                applyLabelScan(action)
            }
            false
        }
        scanFragment.setURPayloadDecoder(::labelsFromUR)
        childFragmentManager.beginTransaction()
            .replace(R.id.importLabelsScanContainer, scanFragment)
            .commit()
    }

    /**
     * Exact collected/total. A repeat of a part already stored does not
     * call this, so the ring and QRPerf stay put until a new index lands.
     */
    private fun noteBbqrProgress(progress: BbqrProgress) {
        if (progress.total <= 0) return
        scanFragment.showPartCount(progress.collected, progress.total)
        QrPerf.noteTransmission(
            progress.total,
            progress.collected,
            progress.collected.toDouble() / progress.total.toDouble()
        )
    }

    /** Missing indexes for the next QRPerf line. Does not move accepted/expected. */
    private fun noteBbqrWatch() {
        QrPerf.noteBbqrMissing(labelGate.bbqr.missingIndexes(), labelGate.bbqr.seriesId())
    }

    /**
     * A skipped frame is counted, not toasted. A this-series body that
     * fails to decode also notes that index once.
     */
    private fun noteBbqrSkip(action: LabelScanAction.Skipped) {
        QrPerf.noteBbqrSkip(
            bbqrSkipReason(action.reason),
            action.partIndex,
            oneShot = action.reason == BbqrSkip.Decode && action.thisSeries
        )
        noteBbqrWatch()
    }

    override fun onDestroyView() {
        conflictDialog?.dismiss()
        conflictDialog = null
        super.onDestroyView()
    }

    /**
     * UI for one [labelScanAction] result. Runs on the main thread after
     * the worker has finished, and only for the generation that started
     * the work.
     */
    private fun applyLabelScan(action: LabelScanAction) {
        if (!isAdded) return
        when (action) {
            is LabelScanAction.Import -> {
                action.bbqr?.let { noteBbqrProgress(it) }
                noteBbqrWatch()
                scanFragment.stopScanner()
                onLabelsDecoded?.invoke(action.jsonl)
                dismiss()
            }
            is LabelScanAction.Collecting -> {
                noteBbqrProgress(action.progress)
                noteBbqrWatch()
            }
            is LabelScanAction.Skipped -> noteBbqrSkip(action)
            is LabelScanAction.Notify -> {
                noteBbqrWatch()
                val ctx = context ?: return
                Toast.makeText(ctx, action.message, Toast.LENGTH_SHORT).show()
            }
            is LabelScanAction.Restart -> {
                noteBbqrSkip(
                    LabelScanAction.Skipped(
                        reason = BbqrSkip.Conflict,
                        partIndex = action.partIndex,
                    )
                )
                noteBbqrProgress(labelGate.bbqr.progress())
                noteBbqrWatch()
                scanFragment.stopScanner()
                showConflictDialog()
            }
            LabelScanAction.IgnoreAndResume -> Unit
        }
    }

    private fun showConflictDialog() {
        if (conflictDialog?.isShowing == true) return
        val ctx = context ?: return
        conflictDialog = MaterialAlertDialogBuilder(ctx)
            .setMessage(BBQR_CONFLICT_MESSAGE)
            .setPositiveButton(BBQR_START_OVER) { dialog, _ ->
                restartBbqrScan()
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    /** Drops the mixed series and resumes the camera so the export can be scanned again. */
    private fun restartBbqrScan() {
        bbqrGeneration += 1
        conflictDialog?.dismiss()
        conflictDialog = null
        val owner = viewLifecycleOwnerLiveData.value ?: return
        owner.lifecycleScope.launch {
            bbqrGate.withLock {
                labelGate.restart()
            }
            QrPerf.noteBbqrMissing(emptyList(), labelGate.bbqr.seriesId())
            scanFragment.clearPartCount()
            scanFragment.resumeScan()
        }
    }

    private fun labelsFromUR(result: URDecoder.Result): String? {
        if (result.ur.registryType != RegistryType.BYTES) return null
        val text = runCatching {
            String(result.ur.decodeFromRegistry() as ByteArray, StandardCharsets.UTF_8)
        }.getOrElse { return null }
        return if (isValidLabelsJsonl(text)) text else null
    }
}

/**
 * Ignores label QR frames while the BBQr conflict dialog is up.
 *
 * [BbqrAccumulator] still starts a new series when the six-character
 * signature changes, including after a conflict. This gate is what
 * stops that from running behind the dialog: [receive] returns null
 * until [restart], which is Start over. An import closes it too, so a
 * second frame cannot complete another import.
 */
internal class LabelScanGate(
    val bbqr: BbqrAccumulator = BbqrAccumulator(),
) {
    private var dialogShowing: Boolean = false
    private var importConsumed: Boolean = false

    fun conflictDialogShowing(): Boolean = dialogShowing

    /**
     * One label QR, or null when the conflict dialog is up or an import
     * already consumed the scan. A [LabelScanAction.Restart] raises the
     * dialog gate.
     */
    fun receive(payload: String, isActive: () -> Boolean = { true }): LabelScanAction? {
        if (dialogShowing || importConsumed) return null
        val action = labelScanAction(payload, bbqr, isActive)
        when (action) {
            is LabelScanAction.Import -> importConsumed = true
            is LabelScanAction.Restart -> dialogShowing = true
            is LabelScanAction.Collecting,
            is LabelScanAction.Skipped,
            is LabelScanAction.Notify,
            LabelScanAction.IgnoreAndResume -> Unit
        }
        return action
    }

    /** Start over. Drops the mixed series and admits frames again. */
    fun restart() {
        dialogShowing = false
        importConsumed = false
        bbqr.reset()
    }
}

fun isValidLabelsJsonl(text: String): Boolean {
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
    return runCatching { JSONObject(firstLine) }.isSuccess
}

sealed class LabelQrPayload {
    object UrFrame : LabelQrPayload()
    object BbqrFrame : LabelQrPayload()
    data class Labels(val jsonl: String) : LabelQrPayload()
    object Invalid : LabelQrPayload()
}

const val BBQR_DECODE_FAILED_MESSAGE = "BBQr label export could not be decoded"
const val BBQR_NOT_LABELS_MESSAGE = "BBQr payload is not a label export"
const val BBQR_CONFLICT_MESSAGE = "BBQr parts disagree. Start the scan over."
const val BBQR_START_OVER = "Start over"

fun bbqrFileTypeMessage(fileType: Char): String =
    "BBQr file type $fileType is not a label export"

/**
 * Coinkite BBQr, not the old "BQR:" guess. The header is eight characters:
 * `B$`, an encoding (`H` hex, `2` base32, `Z` zlib then base32), one
 * base-36 file type, two base-36 digits of part count, two base-36 digits
 * of part index. Sparrow label export is type `U`. See BBQr.md.
 * Lowercase and a leading "BQR" are not this header.
 */
fun isBbqrFrame(payload: String): Boolean {
    if (payload.length < BBQR_HEADER_LENGTH) return false
    if (payload[0] != 'B' || payload[1] != '$') return false
    val encoding = payload[2]
    if (encoding != 'H' && encoding != '2' && encoding != 'Z') return false
    for (i in 3 until BBQR_HEADER_LENGTH) {
        val c = payload[i]
        if (c !in '0'..'9' && c !in 'A'..'Z') return false
    }
    return true
}

fun classifyLabelQrPayload(payload: String): LabelQrPayload = when {
    payload.startsWith("UR:", ignoreCase = true) -> LabelQrPayload.UrFrame
    isBbqrFrame(payload) -> LabelQrPayload.BbqrFrame
    isValidLabelsJsonl(payload) -> LabelQrPayload.Labels(payload)
    else -> LabelQrPayload.Invalid
}

sealed class LabelScanAction {
    data class Import(
        val jsonl: String,
        val bbqr: BbqrProgress? = null
    ) : LabelScanAction()
    data class Collecting(val progress: BbqrProgress) : LabelScanAction()
    data class Skipped(
        val reason: BbqrSkip,
        val partIndex: Int? = null,
        val thisSeries: Boolean = true,
    ) : LabelScanAction()
    data class Notify(val message: String) : LabelScanAction()
    data class Restart(val message: String, val partIndex: Int) : LabelScanAction()
    object IgnoreAndResume : LabelScanAction()
}

/**
 * Join, inflate, and the JSONL door-check. [worker] is the camera
 * callback's escape: the sheet runs this off the main thread and passes
 * an [isActive] that goes false on dismiss or [BbqrAccumulator.reset].
 */
internal suspend fun runLabelFrame(
    payload: String,
    bbqr: BbqrAccumulator,
    worker: CoroutineDispatcher = Dispatchers.Default,
    isActive: () -> Boolean = { true },
): LabelScanAction = withContext(worker) {
    labelScanAction(payload, bbqr, isActive)
}

/**
 * [LabelScanGate.receive] off the camera thread. Null while the BBQr
 * conflict dialog is up, and after an import consumed the scan.
 */
internal suspend fun receiveLabelFrame(
    payload: String,
    gate: LabelScanGate,
    worker: CoroutineDispatcher = Dispatchers.Default,
    isActive: () -> Boolean = { true },
): LabelScanAction? = withContext(worker) {
    gate.receive(payload, isActive)
}

fun labelScanAction(
    payload: String,
    bbqr: BbqrAccumulator,
    isActive: () -> Boolean = { true },
): LabelScanAction {
    if (!isActive()) throw BbqrAbandoned()
    return when (val classified = classifyLabelQrPayload(payload)) {
        is LabelQrPayload.Labels -> LabelScanAction.Import(classified.jsonl)
        LabelQrPayload.BbqrFrame -> when (val received = bbqr.receive(payload, isActive)) {
            is BbqrReceive.Pending ->
                if (received.storedNewPart) {
                    LabelScanAction.Collecting(received.progress)
                } else {
                    LabelScanAction.Skipped(
                        reason = received.skip ?: BbqrSkip.Other,
                        partIndex = received.partIndex,
                        thisSeries = received.thisSeries,
                    )
                }
            is BbqrReceive.Conflict ->
                LabelScanAction.Restart(BBQR_CONFLICT_MESSAGE, received.partIndex)
            is BbqrReceive.Rejected -> LabelScanAction.Notify(bbqrRejectMessage(received))
            is BbqrReceive.Complete -> {
                if (!isActive()) throw BbqrAbandoned()
                val text = String(received.bytes, StandardCharsets.UTF_8)
                if (isBbqrLabelText(received.fileType) && isValidLabelsJsonl(text)) {
                    LabelScanAction.Import(text, received.progress)
                } else if (!isBbqrLabelText(received.fileType)) {
                    LabelScanAction.Notify(bbqrFileTypeMessage(received.fileType))
                } else {
                    LabelScanAction.Notify(BBQR_NOT_LABELS_MESSAGE)
                }
            }
        }
        LabelQrPayload.UrFrame, LabelQrPayload.Invalid ->
            LabelScanAction.IgnoreAndResume
    }
}

private fun bbqrSkipReason(skip: BbqrSkip): BbqrSkipReason = when (skip) {
    BbqrSkip.Held -> BbqrSkipReason.Held
    BbqrSkip.Conflict -> BbqrSkipReason.Conflict
    BbqrSkip.Decode -> BbqrSkipReason.Decode
    BbqrSkip.Index -> BbqrSkipReason.Index
    BbqrSkip.Closed -> BbqrSkipReason.Closed
    BbqrSkip.Other -> BbqrSkipReason.Other
}

private fun bbqrRejectMessage(rejected: BbqrReceive.Rejected): String =
    when (val failure = rejected.failure) {
        BbqrFailure.Decode -> BBQR_DECODE_FAILED_MESSAGE
        is BbqrFailure.FileType -> bbqrFileTypeMessage(failure.fileType)
    }
