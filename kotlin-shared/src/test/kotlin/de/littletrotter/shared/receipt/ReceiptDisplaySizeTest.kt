package de.littletrotter.shared.receipt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.ceil

class ReceiptDisplaySizeTest {

    private val receipt80mm = 80f / 25.4f * 72f

    @Test
    fun `small receipt keeps its size`() {
        val size = ReceiptDisplaySize.of(receipt80mm, 600f)
        assertEquals(1f, size.scale)
        assertEquals(receipt80mm, size.width, 0.01f)
        assertEquals(600f, size.height, 0.01f)
        assertFalse(size.landscape)
        assertEquals(150f, size.renderDpi(150f))
    }

    @Test
    fun `huge photo page is fitted landscape and renders within the A4 raster`() {
        val size = ReceiptDisplaySize.of(4000f, 3000f)
        assertTrue(size.landscape)
        assertTrue(size.width <= ReceiptDisplaySize.areaWidth(true) + 0.01f)
        assertTrue(size.height <= ReceiptDisplaySize.areaHeight(true) + 0.01f)
        val dpi = size.renderDpi(150f)
        assertTrue(ceil(4000f * dpi / 72f) <= 1754f)
        assertTrue(ceil(3000f * dpi / 72f) <= 1240f)
    }

    @Test
    fun `A4 page is scaled into the print area`() {
        val size = ReceiptDisplaySize.of(595.2756f, 841.8898f)
        assertFalse(size.landscape)
        assertTrue(size.scale < 1f)
        assertEquals(ReceiptDisplaySize.areaHeight(false), size.height, 0.01f)
    }

    @Test
    fun `rotated page is judged by its width and height as given`() {
        val size = ReceiptDisplaySize.of(842f, 595f)
        assertTrue(size.landscape)
        assertTrue(size.scale < 1f)
    }

    @Test
    fun `degenerate sizes are rejected`() {
        assertThrows<IllegalArgumentException> { ReceiptDisplaySize.of(0f, 100f) }
        assertThrows<IllegalArgumentException> { ReceiptDisplaySize.of(100f, -1f) }
        assertThrows<IllegalArgumentException> { ReceiptDisplaySize.of(Float.NaN, 100f) }
        assertThrows<IllegalArgumentException> { ReceiptDisplaySize.of(100f, Float.POSITIVE_INFINITY) }
    }

    @Test
    fun `quarter turn swaps the box sides, half turn keeps them`() {
        assertTrue(ReceiptDisplaySize.ofRotated(400f, 300f, 0).landscape)
        assertFalse(ReceiptDisplaySize.ofRotated(400f, 300f, 90).landscape)
        assertTrue(ReceiptDisplaySize.ofRotated(400f, 300f, 180).landscape)
        assertFalse(ReceiptDisplaySize.ofRotated(400f, 300f, -90).landscape)
        assertEquals(300f, ReceiptDisplaySize.ofRotated(400f, 300f, 270).width, 0.01f)
    }
}
