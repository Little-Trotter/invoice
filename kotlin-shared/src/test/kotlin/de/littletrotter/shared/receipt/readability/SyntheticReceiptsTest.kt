package de.littletrotter.shared.receipt.readability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyntheticReceiptsTest {

    @Test
    fun `every kind yields a bitmap and a ground truth text`() {
        for (kind in SyntheticReceiptKind.entries) {
            val receipt = SyntheticReceipts.create(kind)
            assertTrue(receipt.image.width > 0 && receipt.image.height > 0, "$kind has no pixels")
            assertTrue(receipt.text.length > 200, "$kind has too little text")
        }
    }

    @Test
    fun `generation is repeatable`() {
        val a = SyntheticReceipts.create(SyntheticReceiptKind.KASSENBON)
        val b = SyntheticReceipts.create(SyntheticReceiptKind.KASSENBON)
        assertEquals(a.text, b.text)
    }

    @Test
    fun `receipt is a4 or smaller`() {
        val maxWidthInches = 8.28
        for (kind in SyntheticReceiptKind.entries) {
            assertTrue(SyntheticReceipts.create(kind).widthInches <= maxWidthInches)
        }
    }

    @Test
    fun `downscale and jpeg encoding shrink the master`() {
        val master = SyntheticReceipts.create(SyntheticReceiptKind.KASSENBON).image
        val small = RasterTrial.downscale(master, 100)
        assertEquals(master.width / 4, small.width)
        assertTrue(RasterTrial.encodeJpeg(RasterTrial.toGrey(small), 0.75f).size < master.width * master.height / 20)
    }
}
