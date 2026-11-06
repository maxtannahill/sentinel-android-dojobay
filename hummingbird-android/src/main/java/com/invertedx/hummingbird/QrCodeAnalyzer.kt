package com.invertedx.hummingbird

import android.graphics.ImageFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * One QR per frame. Label UR, XPUB, Dojo pairing, and the broadcast
 * scanner all show a single code, so this is [QRCodeReader] rather than
 * [com.google.zxing.multi.qrcode.QRCodeMultiReader]. The multi reader
 * searches for every finder-pattern triple; the QR reader stops at one
 * code. [HINTS] names QR as the only format. [QRCodeReader] does not
 * consult that hint (it never tries another format); it is the contract
 * for this scanner.
 *
 * The camera executor is single-threaded, and [QRCodeReader] is not
 * safe for concurrent decode.
 *
 * Inverted frames: see [InvertDecodePolicy]. The inverted pass is not
 * removed. It runs on every 4th consecutive miss, and on every miss
 * after an inverted code has already decoded in this analyzer.
 */
class QrCodeAnalyzer(
    private val onQrCodesDetected: (qrCode: Result) -> Unit
) : ImageAnalysis.Analyzer {

    private val yuvFormats = mutableListOf(ImageFormat.YUV_420_888)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            yuvFormats.addAll(listOf(ImageFormat.YUV_422_888, ImageFormat.YUV_444_888))
        }
    }

    private val reader = QRCodeReader()
    private val invertPolicy = InvertDecodePolicy()

    override fun analyze(image: ImageProxy) {
        val startedNs = SystemClock.elapsedRealtimeNanos()
        var logNow = false
        try {
            logNow = QrPerf.noteAttempt()
            if (image.format !in yuvFormats) {
                Log.e(TAG, "Format non pris en charge: ${image.format}")
                return
            }
            val yPlane = image.planes[0]
            QrPerf.noteFrameGeometry(image.width, image.height, yPlane.rowStride)
            val yBuffer = yPlane.buffer
            yBuffer.rewind()
            val yBytes = ByteArray(yBuffer.remaining())
            yBuffer.get(yBytes)
            // Same window as before (origin, ImageProxy width/height). The Y
            // plane's row stride is the luminance data width, so padding
            // between rows is not read as the next row's pixels.
            val frame = yLuminance(
                yBytes,
                yPlane.rowStride,
                yPlane.pixelStride,
                0,
                0,
                image.width,
                image.height
            )
            if (frame.width <= 0 || frame.height <= 0) {
                return
            }
            val source = PlanarYUVLuminanceSource(
                frame.bytes,
                frame.dataWidth,
                frame.dataHeight,
                frame.left,
                frame.top,
                frame.width,
                frame.height,
                false
            )
            val normal = decode(source)
            if (normal != null) {
                invertPolicy.onNormalDecoded()
                deliver(normal)
                return
            }
            invertPolicy.onNormalMiss()
            if (!invertPolicy.shouldInvert()) {
                return
            }
            val inverted = decode(source.invert())
            if (inverted != null) {
                invertPolicy.onInvertedDecoded()
                deliver(inverted)
            }
        } finally {
            image.close()
            QrPerf.noteAnalyzeDurationNs(SystemClock.elapsedRealtimeNanos() - startedNs)
            if (logNow) QrPerf.logPeriodic()
        }
    }

    private fun decode(source: LuminanceSource): Result? {
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), HINTS)
        } catch (e: NotFoundException) {
            null
        } catch (e: FormatException) {
            null
        } catch (e: ChecksumException) {
            null
        } catch (t: Throwable) {
            null
        }
    }

    private fun deliver(result: Result) {
        QrPerf.noteDecoded()
        Log.i(TAG, "Success QR code")
        onQrCodesDetected(result)
    }

    companion object {
        private const val TAG = "QrCodeAnalyzer"

        private val HINTS: Map<DecodeHintType, Any> = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
        )
    }
}
