package de.littletrotter.application.invoice

import de.littletrotter.application.cache.NoopInvoiceCachePort
import de.littletrotter.application.invoice.port.outbound.ReceiptPdfReducer
import de.littletrotter.application.invoice.port.outbound.ReceiptReduction
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.test.FIXED_TIME_SOURCE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate

private fun validPdfBytes(): ByteArray = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37)

private fun paddedPdf(totalSize: Long): ByteArray = validPdfBytes() + ByteArray((totalSize - validPdfBytes().size).toInt())

/**
 * [ReceiptUploadService.upload]'s decision matrix for [ReceiptUploadMode]: whether the reducer is
 * called at all, the two size caps (`MAX_UPLOAD_SIZE` always, `MAX_UNREDUCED_RECEIPT_SIZE` only for
 * [ReceiptUploadMode.KEEP_UNREDUCED]), and that a [ReceiptReduction.Reduced]/[ReceiptReduction
 * .StillTooLarge] result is translated into the persisted fields / the typed exception.
 */
class ReceiptUploadServiceModeTest {

    private val receiptRepository: ReceiptRepository = mock()
    private val documentStore: DocumentStore = mock()
    private val receiptPdfReducer: ReceiptPdfReducer = mock()
    private val service = ReceiptUploadService(
        receiptRepository,
        documentStore,
        NoopInvoiceCachePort(),
        FIXED_TIME_SOURCE,
        receiptPdfReducer,
    )

    init {
        whenever(receiptRepository.saveAndFlush(any())).thenAnswer { it.getArgument<Receipt>(0) }
    }

    @Test
    fun `REDUCE calls the reducer and persists the stage and original size from a Reduced result`() {
        val pdf = paddedPdf(1_000_000L)
        val reducedBytes = byteArrayOf(1, 2, 3)
        whenever(receiptPdfReducer.reduce(pdf, ReceiptSizePolicy.TARGET_RECEIPT_SIZE))
            .thenReturn(ReceiptReduction.Reduced(reducedBytes, ReceiptNormalisationStage.IMAGES_DOWNSAMPLED, dpi = 150))

        val receipt = service.upload("r.pdf", "desc", pdf, LocalDate.of(2026, 1, 1))

        assertEquals(ReceiptNormalisationStage.IMAGES_DOWNSAMPLED, receipt.normalisationStage)
        assertEquals(pdf.size.toLong(), receipt.originalSizeBytes)
        assertEquals(reducedBytes.size.toLong(), receipt.sizeBytes)
        verify(documentStore).put(receipt.objectKey, reducedBytes, "application/pdf")
    }

    @Test
    fun `REDUCE with a StillTooLarge result throws ReceiptReductionFailedException carrying the page count`() {
        val pdf = paddedPdf(1_000_000L)
        whenever(receiptPdfReducer.reduce(pdf, ReceiptSizePolicy.TARGET_RECEIPT_SIZE))
            .thenReturn(ReceiptReduction.StillTooLarge(pageCount = 7, resultingSize = DocumentSize.ofBytes(900_000)))

        val ex = assertThrows(ReceiptReductionFailedException::class.java) {
            service.upload("r.pdf", "desc", pdf, LocalDate.of(2026, 1, 1))
        }
        assertEquals(7, ex.pageCount)
    }

    @Test
    fun `KEEP_UNREDUCED never calls the reducer and keeps stage NONE with the original bytes`() {
        val pdf = paddedPdf(1_000_000L)

        val receipt = service.upload("r.pdf", "desc", pdf, LocalDate.of(2026, 1, 1), mode = ReceiptUploadMode.KEEP_UNREDUCED)

        verify(receiptPdfReducer, never()).reduce(any(), any())
        assertEquals(ReceiptNormalisationStage.NONE, receipt.normalisationStage)
        assertEquals(pdf.size.toLong(), receipt.sizeBytes)
        assertEquals(pdf.size.toLong(), receipt.originalSizeBytes)
    }

    @Test
    fun `upload rejects one byte above the 10 MiB upload cap regardless of mode`() {
        val pdf = paddedPdf(ReceiptSizePolicy.MAX_UPLOAD_SIZE_BYTES + 1)

        val ex = assertThrows(ReceiptUploadTooLargeException::class.java) {
            service.upload("r.pdf", "desc", pdf, LocalDate.of(2026, 1, 1))
        }
        assertEquals(DocumentSize.ofBytes(pdf.size.toLong()), ex.uploaded)
        verify(receiptPdfReducer, never()).reduce(any(), any())
    }

    @Test
    fun `REDUCE accepts exactly the 10 MiB upload cap and forwards it to the reducer`() {
        val pdf = paddedPdf(ReceiptSizePolicy.MAX_UPLOAD_SIZE_BYTES)
        whenever(receiptPdfReducer.reduce(pdf, ReceiptSizePolicy.TARGET_RECEIPT_SIZE))
            .thenReturn(ReceiptReduction.Reduced(byteArrayOf(1), ReceiptNormalisationStage.RASTERISED, dpi = 150))

        val receipt = service.upload("r.pdf", "desc", pdf, LocalDate.of(2026, 1, 1))

        assertEquals(ReceiptNormalisationStage.RASTERISED, receipt.normalisationStage)
    }

    @Test
    fun `KEEP_UNREDUCED rejects one byte above the 1 MiB unreduced cap`() {
        val pdf = paddedPdf(ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE_BYTES + 1)

        val ex = assertThrows(ReceiptUnreducedTooLargeException::class.java) {
            service.upload("r.pdf", "desc", pdf, LocalDate.of(2026, 1, 1), mode = ReceiptUploadMode.KEEP_UNREDUCED)
        }
        assertEquals(DocumentSize.ofBytes(ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE_BYTES), ex.limit)
    }

    @Test
    fun `KEEP_UNREDUCED accepts exactly the 1 MiB unreduced cap`() {
        val pdf = paddedPdf(ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE_BYTES)

        val receipt = service.upload("r.pdf", "desc", pdf, LocalDate.of(2026, 1, 1), mode = ReceiptUploadMode.KEEP_UNREDUCED)

        assertEquals(ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE_BYTES, receipt.sizeBytes)
    }
}
