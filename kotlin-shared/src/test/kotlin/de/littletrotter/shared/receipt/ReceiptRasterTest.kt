package de.littletrotter.shared.receipt

import de.littletrotter.shared.receipt.readability.ReadabilityPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReceiptRasterTest {

    @Test
    fun `a raster the gate permits is kept`() {
        val raster = ReceiptRaster(dpi = 200f, jpegQuality = 0.8f)

        assertEquals(raster, raster.atLeastReadable())
    }

    @Test
    fun `a raster below the floor is lifted to the floor, never used`() {
        val lifted = ReceiptRaster(dpi = 72f, jpegQuality = 0.3f).atLeastReadable()

        assertEquals(ReadabilityPolicy.MIN_EFFECTIVE_DPI.toFloat(), lifted.dpi)
        assertEquals(0.3f, lifted.jpegQuality)
    }

    @Test
    fun `invalid values are refused`() {
        assertThrows(IllegalArgumentException::class.java) { ReceiptRaster(0f, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { ReceiptRaster(150f, 1.5f) }
    }

    @Test
    fun `the default raster satisfies the gate`() {
        assertEquals(ReceiptRaster.DEFAULT, ReceiptRaster.DEFAULT.atLeastReadable())
    }
}
