package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.outbound.ReceiptPagePreview
import de.littletrotter.application.invoice.port.outbound.ReceiptPdfReducer
import de.littletrotter.application.invoice.port.outbound.ReceiptReduction
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ReceiptReductionPreviewServiceTest {

    private val reducer: ReceiptPdfReducer = mock()
    private val pagePreview: ReceiptPagePreview = mock()
    private val service = ReceiptReductionPreviewService(reducer, pagePreview)

    @Test
    fun `a reduction within the target reports both sizes and the first page of the reduced version`() {
        val original = ByteArray(900_000)
        val reduced = ByteArray(300_000)
        whenever(reducer.reduce(original, ReceiptSizePolicy.TARGET_RECEIPT_SIZE))
            .thenReturn(ReceiptReduction.Reduced(reduced, ReceiptNormalisationStage.RASTERISED, 150))
        whenever(pagePreview.firstPagePng(reduced)).thenReturn(byteArrayOf(7))

        val preview = service.previewReduction(original)

        assertEquals(DocumentSize.ofBytes(900_000), preview.originalSize)
        assertEquals(DocumentSize.ofBytes(300_000), preview.reducedSize)
        assertTrue(preview.reachesTarget)
        assertArrayEquals(byteArrayOf(7), preview.firstPagePng)
    }

    @Test
    fun `a result above the target is reported as not reaching it`() {
        val original = ByteArray(900_000)
        val best = ByteArray(700_000)
        whenever(reducer.reduce(original, ReceiptSizePolicy.TARGET_RECEIPT_SIZE)).thenReturn(
            ReceiptReduction.StillTooLarge(5, DocumentSize.ofBytes(700_000), best, ReceiptNormalisationStage.RASTERISED),
        )

        val preview = service.previewReduction(original)

        assertEquals(DocumentSize.ofBytes(700_000), preview.reducedSize)
        assertFalse(preview.reachesTarget)
    }
}
