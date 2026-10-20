package de.littletrotter.application.invoice

import de.littletrotter.shared.receipt.DocumentSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The size refusals carry the values the UI words its message from, and their own message names them in MiB. */
class ReceiptSizeExceptionsTest {

    private val mib = 1024L * 1024

    @Test
    fun `an oversized upload names the uploaded and the allowed size`() {
        val ex = ReceiptUploadTooLargeException(DocumentSize.ofBytes(12 * mib), DocumentSize.ofBytes(10 * mib))

        assertEquals(DocumentSize.ofBytes(12 * mib), ex.uploaded)
        assertEquals(DocumentSize.ofBytes(10 * mib), ex.limit)
        assertEquals("Receipt of 12 MiB exceeds the maximum upload size of 10 MiB.", ex.message)
    }

    @Test
    fun `an oversized unreduced receipt names the smaller unreduced cap`() {
        val ex = ReceiptUnreducedTooLargeException(DocumentSize.ofBytes(3 * mib), DocumentSize.ofBytes(2 * mib))

        assertEquals(DocumentSize.ofBytes(3 * mib), ex.uploaded)
        assertEquals(DocumentSize.ofBytes(2 * mib), ex.limit)
        assertEquals("Unreduced receipt of 3 MiB exceeds the maximum of 2 MiB.", ex.message)
    }

    @Test
    fun `a failed reduction names page count and remaining size`() {
        val ex = ReceiptReductionFailedException(7, DocumentSize.ofBytes(5 * mib))

        assertEquals(7, ex.pageCount)
        assertEquals(DocumentSize.ofBytes(5 * mib), ex.resultingSize)
        assertEquals("Receipt could not be reduced below the target size: 7 page(s), 5 MiB remaining.", ex.message)
    }
}
