package de.littletrotter.shared.receipt.readability

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Readability gate for the raster configuration: every synthetic receipt, encoded with the default
 * settings in colour and in grey, must be read back by OCR with at least 98 % of its characters
 * and look like the 200 dpi reference (SSIM >= 0.95). The noisy, skewed phone photo is held to the
 * achievable level of its noise instead (OCR 93 %, SSIM 0.85), because JPEG removing sensor noise
 * lowers SSIM by itself. Tesseract runs in a pinned container image via Testcontainers.
 */
class ReceiptRasterGateIT {

    private companion object {
        const val OCR_MIN = 0.98
        const val SSIM_MIN = 0.95
        const val PHOTO_OCR_MIN = 0.93
        const val PHOTO_SSIM_MIN = 0.85
    }

    private fun gate(kind: SyntheticReceiptKind, config: RasterConfig) {
        val result = RasterTrial.run(SyntheticReceipts.create(kind), config)
        val photo = kind == SyntheticReceiptKind.PHOTO_SKEW
        assertTrue(result.ocrAccuracy >= if (photo) PHOTO_OCR_MIN else OCR_MIN, "OCR $result")
        assertTrue(result.ssim >= if (photo) PHOTO_SSIM_MIN else SSIM_MIN, "SSIM $result")
    }

    @Test
    fun `default settings keep every receipt legible in colour`() {
        val defaults = ReceiptRasterSettings.DEFAULT
        SyntheticReceiptKind.entries.forEach { gate(it, RasterConfig(defaults.dpi, defaults.jpegQuality, grey = false)) }
    }

    @Test
    fun `default settings keep every receipt legible in grey`() {
        val defaults = ReceiptRasterSettings.DEFAULT
        SyntheticReceiptKind.entries.forEach { gate(it, RasterConfig(defaults.dpi, defaults.jpegQuality, grey = true)) }
    }

    @Test
    fun `a coarse configuration fails the gate`() {
        val result = RasterTrial.run(SyntheticReceipts.create(SyntheticReceiptKind.KASSENBON), RasterConfig(60, 0.5f, grey = false))
        assertTrue(result.ocrAccuracy < OCR_MIN || result.ssim < SSIM_MIN, "gate must reject 60 dpi: $result")
    }
}
