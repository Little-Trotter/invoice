package de.littletrotter.shared.receipt

import de.littletrotter.shared.receipt.readability.ReadabilityPolicy

/**
 * The resolution and JPEG quality a receipt page is rasterised with, relative to its display size.
 * One value object for upload reduction and the print annex so a reduced receipt is already at the
 * resolution the print would produce and can be imported unchanged. The values come from
 * configuration; [DEFAULT] is what applies without any. [maxPixels] is a silent upper bound for
 * one page bitmap, a safety net should the dpi be raised far beyond the A4 target: the resolution
 * is lowered to stay within it, a page is never refused.
 */
data class ReceiptRaster(val dpi: Float, val jpegQuality: Float, val maxPixels: Long = DEFAULT_MAX_PIXELS) {

    init {
        require(dpi.isFinite() && dpi > 0f) { "The raster resolution must be positive, was $dpi." }
        require(jpegQuality > 0f && jpegQuality <= 1f) { "The JPEG quality must be in (0, 1], was $jpegQuality." }
        require(maxPixels > 0) { "The pixel bound must be positive, was $maxPixels." }
    }

    companion object {
        const val DEFAULT_MAX_PIXELS = 4_500_000L
        val DEFAULT = ReceiptRaster(dpi = 150f, jpegQuality = 0.75f)
    }
}

/**
 * This raster, with the resolution lifted to the readability threshold of the measured policy when
 * a configured value falls below it — never a weaker setting. The JPEG quality has no threshold of
 * its own: the measurement series fixes the resolution floor only.
 */
fun ReceiptRaster.atLeastReadable(): ReceiptRaster =
    if (dpi >= ReadabilityPolicy.MIN_EFFECTIVE_DPI) this else copy(dpi = ReadabilityPolicy.MIN_EFFECTIVE_DPI.toFloat())
