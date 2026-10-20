package de.littletrotter.shared.receipt.readability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadabilityPolicyTest {

    @Test
    fun `effective dpi relates pixel width to the display width`() {
        // 1240 px on 595.2756 pt (A4 width) is 150 dpi
        assertEquals(150.0, ReadabilityPolicy.effectiveDpi(1240, 595.2756), 0.1)
        assertEquals(535.0, ReadabilityPolicy.effectiveDpi(4000, 538.0), 1.0)
    }

    @Test
    fun `effective dpi rejects a non positive display width`() {
        assertThrows(IllegalArgumentException::class.java) { ReadabilityPolicy.effectiveDpi(100, 0.0) }
    }

    @Test
    fun `low resolution is suspect`() {
        val page = PageReadability(1, effectiveDpi = 72.0, laplaceVariance = 500.0)
        assertTrue(page.lowResolution)
        assertFalse(page.blurred)
        assertTrue(page.suspect)
    }

    @Test
    fun `blurred page is suspect`() {
        val page = PageReadability(2, effectiveDpi = 200.0, laplaceVariance = 20.0)
        assertTrue(page.blurred)
        assertTrue(page.suspect)
    }

    @Test
    fun `sharp page with enough resolution is not suspect`() {
        assertFalse(PageReadability(1, 150.0, 800.0).suspect)
    }

    @Test
    fun `blank page is not judged blurred`() {
        assertFalse(PageReadability(1, 150.0, 0.0).suspect)
    }

    @Test
    fun `page without scanned image is not suspect`() {
        assertFalse(PageReadability(1, null, null).suspect)
    }

    @Test
    fun `thresholds sit exactly on the boundary`() {
        assertFalse(PageReadability(1, ReadabilityPolicy.MIN_EFFECTIVE_DPI, null).suspect)
        assertFalse(PageReadability(1, null, ReadabilityPolicy.MIN_LAPLACE_VARIANCE).suspect)
    }

    @Test
    fun `default raster settings are valid and fixed`() {
        assertEquals(120, ReceiptRasterSettings.DEFAULT.dpi)
        assertEquals(0.75f, ReceiptRasterSettings.DEFAULT.jpegQuality)
    }

    @Test
    fun `raster settings reject out of range values`() {
        assertThrows(IllegalArgumentException::class.java) { ReceiptRasterSettings(10, 0.75f) }
        assertThrows(IllegalArgumentException::class.java) { ReceiptRasterSettings(120, 0f) }
        assertThrows(IllegalArgumentException::class.java) { ReceiptRasterSettings(120, 1.5f) }
    }
}
