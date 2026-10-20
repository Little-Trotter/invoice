package de.littletrotter.shared.receipt.readability

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

/** The upload hint's thresholds against the synthetic receipts: sharp scans pass, blurred or coarse ones are reported. */
class ReadabilityDetectionTest {

    private val receipts = SyntheticReceiptKind.entries.map { SyntheticReceipts.create(it) }

    private fun assess(receipt: SyntheticReceipt, image: BufferedImage, dpi: Double): PageReadability =
        PageReadability(1, dpi, ImageMetrics.laplaceVariance(image, receipt.widthInches))

    @Test
    fun `default raster settings do not trigger the hint`() {
        val dpi = ReceiptRasterSettings.DEFAULT.dpi
        for (receipt in receipts) {
            val page = assess(receipt, RasterTrial.downscale(receipt.image, dpi), dpi.toDouble())
            assertFalse(page.suspect, "${receipt.kind} at $dpi dpi: $page")
        }
    }

    @Test
    fun `heavy blur triggers the hint`() {
        for (receipt in receipts) {
            val blurred = RasterTrial.blur(RasterTrial.downscale(receipt.image, 200), 3.0)
            val page = assess(receipt, blurred, 200.0)
            assertTrue(page.blurred, "${receipt.kind}: $page")
        }
    }

    @Test
    fun `coarse scan triggers the hint`() {
        for (receipt in receipts) {
            val coarse = RasterTrial.downscale(receipt.image, 60)
            assertTrue(assess(receipt, coarse, 60.0).lowResolution, receipt.kind.name)
        }
    }
}
