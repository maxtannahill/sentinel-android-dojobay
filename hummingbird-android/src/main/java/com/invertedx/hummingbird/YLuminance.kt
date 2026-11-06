package com.invertedx.hummingbird

/**
 * Arguments for [com.google.zxing.PlanarYUVLuminanceSource].
 *
 * [dataWidth] is the byte stride of each row in [bytes]. ZXing addresses
 * row `y` at `y * dataWidth`. Passing the image width there when the Y
 * plane's row stride is wider pulls the row padding into the next row
 * and shears the code. When the plane is already one byte per pixel and
 * the buffer covers the crop, [bytes] is the Y plane itself and
 * [dataWidth] is [rowStride]. Otherwise the crop is packed tightly and
 * [dataWidth] equals [width].
 */
internal data class YLuminance(
    val bytes: ByteArray,
    val dataWidth: Int,
    val dataHeight: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val rowStride: Int
)

/**
 * Build the luminance window ZXing actually reads.
 *
 * [cropLeft], [cropTop], [cropWidth], and [cropHeight] are the window
 * in pixels. The analyzer passes the ImageProxy origin and its width and
 * height, which is the window the scanner already used. [rowStride] and
 * [pixelStride] are the Y plane's. U and V are not read: the QR decoder
 * uses only Y.
 */
internal fun yLuminance(
    yBytes: ByteArray,
    rowStride: Int,
    pixelStride: Int,
    cropLeft: Int,
    cropTop: Int,
    cropWidth: Int,
    cropHeight: Int
): YLuminance {
    val stride = if (rowStride > 0) rowStride else cropWidth.coerceAtLeast(1)
    val pix = if (pixelStride > 0) pixelStride else 1
    val left = cropLeft.coerceAtLeast(0)
    val top = cropTop.coerceAtLeast(0)
    val width = cropWidth.coerceAtLeast(0)
    val height = cropHeight.coerceAtLeast(0)
    if (width == 0 || height == 0) {
        return YLuminance(
            bytes = ByteArray(0),
            dataWidth = stride,
            dataHeight = 0,
            left = 0,
            top = 0,
            width = 0,
            height = 0,
            rowStride = stride
        )
    }
    val lastIndex = (top + height - 1L) * stride + (left + width - 1L) * pix
    val fitsStrided = pix == 1 &&
        stride >= left + width &&
        lastIndex >= 0 &&
        lastIndex < yBytes.size
    if (fitsStrided) {
        return YLuminance(
            bytes = yBytes,
            dataWidth = stride,
            dataHeight = top + height,
            left = left,
            top = top,
            width = width,
            height = height,
            rowStride = stride
        )
    }
    val packed = ByteArray(width * height)
    var dst = 0
    for (row in 0 until height) {
        val rowStart = (top + row).toLong() * stride + left.toLong() * pix
        var col = 0
        while (col < width) {
            val src = rowStart + col.toLong() * pix
            packed[dst] = if (src >= 0 && src < yBytes.size) yBytes[src.toInt()] else 0
            dst++
            col++
        }
    }
    return YLuminance(
        bytes = packed,
        dataWidth = width,
        dataHeight = height,
        left = 0,
        top = 0,
        width = width,
        height = height,
        rowStride = stride
    )
}
