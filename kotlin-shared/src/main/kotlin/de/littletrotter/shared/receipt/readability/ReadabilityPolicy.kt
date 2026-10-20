package de.littletrotter.shared.receipt.readability

/**
 * Raster values for receipt pages: pixels per inch relative to the size a page is shown at, and
 * the JPEG quality (0 = smallest file, 1 = best picture). The defaults are the smallest
 * combination that kept 6-8 pt text legible in the measurement series (OCR hit rate and SSIM per
 * resolution and quality, see [ReadabilityPolicy]); callers read them from configuration and fall
 * back to [DEFAULT].
 */
data class ReceiptRasterSettings(val dpi: Int, val jpegQuality: Float) {

    init {
        require(dpi in MIN_DPI..MAX_DPI) { "dpi must be within $MIN_DPI..$MAX_DPI" }
        require(jpegQuality > 0f && jpegQuality <= 1f) { "jpegQuality must be within (0, 1]" }
    }

    companion object {
        const val DEFAULT_DPI = 120
        const val DEFAULT_JPEG_QUALITY = 0.75f
        private const val MIN_DPI = 50
        private const val MAX_DPI = 600

        val DEFAULT = ReceiptRasterSettings(DEFAULT_DPI, DEFAULT_JPEG_QUALITY)
    }
}

/**
 * Thresholds for the upload-time readability hint, taken from the same measurement series as the
 * raster defaults: below [MIN_EFFECTIVE_DPI] small print loses more than 3 % of its characters to
 * OCR, and a Laplacian variance below [MIN_LAPLACE_VARIANCE] (measured at 150 dpi, see
 * [ImageMetrics.laplaceVariance]) corresponds to blur at which text recognition falls below 90 %.
 */
object ReadabilityPolicy {

    /** Pixels per inch of the scan at its display size below which a page is reported. */
    const val MIN_EFFECTIVE_DPI = 100.0

    /** Laplacian variance (at 150 dpi) below which a page is reported as blurred. */
    const val MIN_LAPLACE_VARIANCE = 50.0

    /** Variance below which a page is plain (blank) and sharpness is not judged. */
    const val FLAT_PAGE_VARIANCE = 1.0

    /** Pages inspected per upload; further pages of a long document are not measured. */
    const val MAX_PAGES_CHECKED = 10

    /** Pixels per inch of an image [pixelWidth] wide that is shown [displayWidthPoints] wide (72 points per inch). */
    fun effectiveDpi(pixelWidth: Int, displayWidthPoints: Double): Double {
        require(displayWidthPoints > 0.0) { "displayWidthPoints must be positive" }
        return pixelWidth / (displayWidthPoints / POINTS_PER_INCH)
    }

    private const val POINTS_PER_INCH = 72.0
}

/**
 * Measured legibility of one page. [effectiveDpi] and [laplaceVariance] are null when the page has
 * no scanned image to measure (text or vector page) or is blank.
 */
data class PageReadability(val pageNumber: Int, val effectiveDpi: Double?, val laplaceVariance: Double?) {

    val lowResolution: Boolean get() = effectiveDpi != null && effectiveDpi < ReadabilityPolicy.MIN_EFFECTIVE_DPI

    val blurred: Boolean
        get() = laplaceVariance != null &&
            laplaceVariance >= ReadabilityPolicy.FLAT_PAGE_VARIANCE &&
            laplaceVariance < ReadabilityPolicy.MIN_LAPLACE_VARIANCE

    /** True when the page is likely hard to read in print; a hint, never a block. */
    val suspect: Boolean get() = lowResolution || blurred
}
