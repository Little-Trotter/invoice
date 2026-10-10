package de.littletrotter.xpdf.impl

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Encodes the EPC QR payload as a ZXing [BitMatrix] and renders it as an SVG for the Typst
 * payment block.
 *
 * SVG (a vector) rather than a raster image keeps the exported PDF PDF/A-3b conformant without a
 * DPI trade-off; a bitmap embedded at a fixed pixel size either blurs on print or bloats the file.
 */
internal object QrSvg {

    private val HINTS =
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            // No quiet zone here: the Typst layout reserves the white margin around the code
            // itself, so baking a second one into the matrix would double it.
            EncodeHintType.MARGIN to 0,
        )

    fun encode(payload: String): BitMatrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, HINTS)

    /**
     * Renders one `<rect>` per contiguous horizontal run of dark modules per row instead of one
     * `<rect>` per module — far fewer elements for the same image, and `shape-rendering:
     * crispEdges` avoids anti-aliasing hairlines between adjacent module rects at print
     * resolution.
     */
    fun render(matrix: BitMatrix): String {
        val size = matrix.width
        val rects = StringBuilder()
        for (y in 0 until matrix.height) {
            var runStart = -1
            for (x in 0 until size) {
                val dark = matrix.get(x, y)
                if (dark && runStart == -1) {
                    runStart = x
                } else if (!dark && runStart != -1) {
                    rects.append(rect(runStart, y, x - runStart))
                    runStart = -1
                }
            }
            if (runStart != -1) rects.append(rect(runStart, y, size - runStart))
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $size $size\" " +
            "shape-rendering=\"crispEdges\">$rects</svg>"
    }

    private fun rect(x: Int, y: Int, width: Int): String =
        "<rect x=\"$x\" y=\"$y\" width=\"$width\" height=\"1\" fill=\"#000\"/>"
}
