package de.littletrotter.application.invoice.port.outbound

import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.shared.receipt.DocumentSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** [ReceiptReduction.Reduced]'s hand-written `equals`/`hashCode` (content-based, because a
 *  `data class` would otherwise compare its `ByteArray` by identity) and [NoopReceiptPdfReducer],
 *  the backwards-compatible stand-in [de.littletrotter.application.invoice.ReceiptUploadService]'s
 *  secondary constructors use. */
class ReceiptReductionTest {

    @Test
    fun `Reduced instances with the same content, stage and dpi are equal`() {
        val a = ReceiptReduction.Reduced(byteArrayOf(1, 2, 3), ReceiptNormalisationStage.RASTERISED, dpi = 150)
        val b = ReceiptReduction.Reduced(byteArrayOf(1, 2, 3), ReceiptNormalisationStage.RASTERISED, dpi = 150)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Reduced instances differ by bytes, stage, dpi or type`() {
        val base = ReceiptReduction.Reduced(byteArrayOf(1, 2, 3), ReceiptNormalisationStage.RASTERISED, dpi = 150)

        assertNotEquals(base, ReceiptReduction.Reduced(byteArrayOf(9, 9, 9), ReceiptNormalisationStage.RASTERISED, dpi = 150))
        assertNotEquals(base, ReceiptReduction.Reduced(byteArrayOf(1, 2, 3), ReceiptNormalisationStage.IMAGES_DOWNSAMPLED, dpi = 150))
        assertNotEquals(base, ReceiptReduction.Reduced(byteArrayOf(1, 2, 3), ReceiptNormalisationStage.RASTERISED, dpi = 72))
        assertNotEquals(base, ReceiptReduction.StillTooLarge(1, DocumentSize.ZERO, byteArrayOf(1), ReceiptNormalisationStage.NONE))
    }

    @Test
    fun `StillTooLarge instances with the same content are equal and differ in any field`() {
        fun tooLarge(
            pages: Int = 3,
            size: Long = 900L,
            bytes: ByteArray = byteArrayOf(1, 2),
            stage: ReceiptNormalisationStage = ReceiptNormalisationStage.RASTERISED,
        ) = ReceiptReduction.StillTooLarge(pages, DocumentSize.ofBytes(size), bytes, stage)

        assertEquals(tooLarge(), tooLarge())
        assertEquals(tooLarge().hashCode(), tooLarge().hashCode())
        assertNotEquals(tooLarge(), tooLarge(pages = 4))
        assertNotEquals(tooLarge(), tooLarge(size = 901L))
        assertNotEquals(tooLarge(), tooLarge(bytes = byteArrayOf(9)))
        assertNotEquals(tooLarge(), tooLarge(stage = ReceiptNormalisationStage.IMAGES_DOWNSAMPLED))
        assertNotEquals(tooLarge(), "other")
    }

    @Test
    fun `NoopReceiptPdfReducer reports every input as already meeting the target`() {
        val bytes = byteArrayOf(1, 2, 3)

        val result = NoopReceiptPdfReducer().reduce(bytes, DocumentSize.ZERO)

        check(result is ReceiptReduction.Reduced)
        assertEquals(ReceiptNormalisationStage.NONE, result.stage)
        assertEquals(0, result.dpi)
        assertEquals(bytes, result.bytes)
    }
}
