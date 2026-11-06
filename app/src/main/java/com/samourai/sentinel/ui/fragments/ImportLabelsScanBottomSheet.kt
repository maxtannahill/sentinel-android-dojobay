package com.samourai.sentinel.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.invertedx.hummingbird.QrPerf
import com.samourai.sentinel.R
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.registry.RegistryType
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class ImportLabelsScanBottomSheet : BottomSheetDialogFragment() {

    private var onLabelsDecoded: ((String) -> Unit)? = null
    private val scanFragment = ScanPubKeyFragment()
    private val bbqrLabels = BbqrAccumulator()

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
        // #71: counts only. processedPartsCount is the accepted (non-duplicate)
        // part gauge the scanner already exposes. No progress UI change.
        scanFragment.setUrTransmissionListener { expected, processed, progress ->
            QrPerf.noteTransmission(expected, processed, progress)
        }
        scanFragment.setOnScanListener { payload ->
            // #232: same pure action logic, new contract - the scanner's
            // dispatch stops the camera when a decode is consumed, so the
            // resumeScan() calls the unconsumed arms needed (the scanner
            // never stopped) die with the upfront stop.
            when (val action = labelScanAction(payload, bbqrLabels)) {
                is LabelScanAction.Import -> {
                    onLabelsDecoded?.invoke(action.jsonl)
                    dismiss()
                    true
                }
                is LabelScanAction.Notify -> {
                    Toast.makeText(context, action.message, Toast.LENGTH_SHORT).show()
                    false
                }
                LabelScanAction.IgnoreAndResume -> false
            }
        }
        scanFragment.setURPayloadDecoder(::labelsFromUR)
        childFragmentManager.beginTransaction()
            .replace(R.id.importLabelsScanContainer, scanFragment)
            .commit()
    }

    private fun labelsFromUR(result: URDecoder.Result): String? {
        if (result.ur.registryType != RegistryType.BYTES) return null
        val text = runCatching {
            String(result.ur.decodeFromRegistry() as ByteArray, StandardCharsets.UTF_8)
        }.getOrElse { return null }
        return if (isValidLabelsJsonl(text)) text else null
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
    data class Import(val jsonl: String) : LabelScanAction()
    data class Notify(val message: String) : LabelScanAction()
    object IgnoreAndResume : LabelScanAction()
}

fun labelScanAction(payload: String, bbqr: BbqrAccumulator): LabelScanAction =
    when (val classified = classifyLabelQrPayload(payload)) {
        is LabelQrPayload.Labels -> LabelScanAction.Import(classified.jsonl)
        LabelQrPayload.BbqrFrame -> when (val received = bbqr.receive(payload)) {
            BbqrReceive.Pending -> LabelScanAction.IgnoreAndResume
            is BbqrReceive.Rejected -> LabelScanAction.Notify(bbqrRejectMessage(received))
            is BbqrReceive.Complete -> {
                val text = String(received.bytes, StandardCharsets.UTF_8)
                if (isBbqrLabelText(received.fileType) && isValidLabelsJsonl(text)) {
                    LabelScanAction.Import(text)
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

private fun bbqrRejectMessage(rejected: BbqrReceive.Rejected): String =
    when (val failure = rejected.failure) {
        BbqrFailure.Decode -> BBQR_DECODE_FAILED_MESSAGE
        is BbqrFailure.FileType -> bbqrFileTypeMessage(failure.fileType)
    }
