package de.littletrotter.shared.receipt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReceiptSizePolicyTest {

    @Test
    fun `the numbers are 440 KiB target, 1 MiB unreduced cap, 10 MiB upload cap, 19 and 20 MiB`() {
        assertEquals(440L * 1024, ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES)
        assertEquals(1L * 1024 * 1024, ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE_BYTES)
        assertEquals(10L * 1024 * 1024, ReceiptSizePolicy.MAX_UPLOAD_SIZE_BYTES)
        assertEquals(19, ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE)
        assertEquals(20L * 1024 * 1024, ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE_BYTES)
        assertEquals(256L * 1024, ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE_BYTES)

        assertEquals(
            DocumentSize.ofBytes(ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES),
            ReceiptSizePolicy.TARGET_RECEIPT_SIZE,
        )
        assertEquals(
            DocumentSize.ofBytes(ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE_BYTES),
            ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE,
        )
        assertEquals(DocumentSize.ofBytes(ReceiptSizePolicy.MAX_UPLOAD_SIZE_BYTES), ReceiptSizePolicy.MAX_UPLOAD_SIZE)
        assertEquals(
            DocumentSize.ofBytes(ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE_BYTES),
            ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE,
        )
        assertEquals(
            DocumentSize.ofBytes(ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE_BYTES),
            ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE,
        )
    }

    /**
     * IR-1 — the invariant that makes the budget coherent: 19 receipts at the 440 KiB target, each
     * once as Base64 (BG-24) and once as rasterised pages of about its own size, plus the 256 KiB
     * reserve for the invoice document itself, fit into 20 MiB, and one more receipt would not. A
     * future edit to one constant that breaks this must fail the build, not silently ship a limit
     * that is unreachable or fires before another one does.
     */
    @Test
    fun `19 receipts at the target size, as base64 and as pages, plus reserve fit and a 20th would not`() {
        val perReceipt = ReceiptSizePolicy.TARGET_RECEIPT_SIZE.base64Encoded() + ReceiptSizePolicy.TARGET_RECEIPT_SIZE
        val total = perReceipt * ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE + ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE
        assertTrue(total <= ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE, "total was $total")
        assertTrue(total + perReceipt > ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE)
    }

    @Test
    fun `uploadDecision accepts the target size as-is and refuses one byte more`() {
        assertEquals(ReceiptUploadDecision.ACCEPT_AS_IS, ReceiptSizePolicy.uploadDecision(DocumentSize.ofBytes(1L)))
        assertEquals(
            ReceiptUploadDecision.ACCEPT_AS_IS,
            ReceiptSizePolicy.uploadDecision(ReceiptSizePolicy.TARGET_RECEIPT_SIZE),
        )
        assertEquals(
            ReceiptUploadDecision.OFFER_REDUCTION,
            ReceiptSizePolicy.uploadDecision(ReceiptSizePolicy.TARGET_RECEIPT_SIZE + DocumentSize.ofBytes(1L)),
        )
    }

    @Test
    fun `uploadDecision offers reduction up to the upload cap and rejects beyond it`() {
        assertEquals(
            ReceiptUploadDecision.OFFER_REDUCTION,
            ReceiptSizePolicy.uploadDecision(ReceiptSizePolicy.MAX_UPLOAD_SIZE),
        )
        assertEquals(
            ReceiptUploadDecision.REJECT,
            ReceiptSizePolicy.uploadDecision(ReceiptSizePolicy.MAX_UPLOAD_SIZE + DocumentSize.ofBytes(1L)),
        )
    }

    @Test
    fun `meetsTarget accepts exactly the target size and refuses one byte more`() {
        assertTrue(ReceiptSizePolicy.meetsTarget(ReceiptSizePolicy.TARGET_RECEIPT_SIZE))
        assertFalse(ReceiptSizePolicy.meetsTarget(ReceiptSizePolicy.TARGET_RECEIPT_SIZE + DocumentSize.ofBytes(1L)))
    }

    @Test
    fun `acceptsUnreduced accepts exactly the unreduced cap and refuses one byte more`() {
        assertTrue(ReceiptSizePolicy.acceptsUnreduced(DocumentSize.ofBytes(1L)))
        assertTrue(ReceiptSizePolicy.acceptsUnreduced(ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE))
        assertFalse(
            ReceiptSizePolicy.acceptsUnreduced(ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE + DocumentSize.ofBytes(1L)),
        )
    }
}
