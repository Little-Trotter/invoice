package de.littletrotter.shared.receipt

import kotlin.math.min

/**
 * How one receipt page is shown on an A4 annex page: in its original size, never enlarged, only
 * shrunk until it fits the print area. The raster resolution is defined relative to that display
 * size, so a page with a faulty huge size yields a small bitmap instead of exhausting memory.
 *
 * Pure geometry in PDF points (1/72 inch); print and upload reduction both size pages through this
 * one rule, so a page is reduced to exactly the resolution it is later shown at.
 */
class ReceiptDisplaySize private constructor(
    val landscape: Boolean,
    val scale: Float,
    val width: Float,
    val height: Float,
) {

    /** Dpi to render the source page at so that its bitmap has [baseDpi] relative to the display size. */
    fun renderDpi(baseDpi: Float): Float = baseDpi * scale

    companion object {
        const val A4_SHORT_SIDE = 595.2756f
        const val A4_LONG_SIDE = 841.8898f
        const val MARGIN = 36f
        const val HEADER_BAND_HEIGHT = 50f
        private const val QUARTER_TURN_DEGREES = 90

        /** Width and height of the A4 page in the given orientation. */
        fun pageSize(landscape: Boolean): Pair<Float, Float> =
            if (landscape) A4_LONG_SIDE to A4_SHORT_SIDE else A4_SHORT_SIDE to A4_LONG_SIDE

        fun areaWidth(landscape: Boolean): Float = pageSize(landscape).first - 2 * MARGIN

        fun areaHeight(landscape: Boolean): Float = pageSize(landscape).second - 2 * MARGIN - HEADER_BAND_HEIGHT

        /** Display size of a page box of [boxWidth] × [boxHeight] shown with its /Rotate of [rotationDegrees]. */
        fun ofRotated(boxWidth: Float, boxHeight: Float, rotationDegrees: Int): ReceiptDisplaySize {
            val quarterTurn = Math.floorMod(rotationDegrees / QUARTER_TURN_DEGREES, 2) == 1
            return if (quarterTurn) of(boxHeight, boxWidth) else of(boxWidth, boxHeight)
        }

        fun of(pageWidth: Float, pageHeight: Float): ReceiptDisplaySize {
            require(pageWidth.isFinite() && pageHeight.isFinite() && pageWidth > 0f && pageHeight > 0f) {
                "Invalid page size ${pageWidth}x$pageHeight"
            }
            val landscape = pageWidth > pageHeight
            val scale = min(1f, min(areaWidth(landscape) / pageWidth, areaHeight(landscape) / pageHeight))
            return ReceiptDisplaySize(landscape, scale, pageWidth * scale, pageHeight * scale)
        }
    }
}
