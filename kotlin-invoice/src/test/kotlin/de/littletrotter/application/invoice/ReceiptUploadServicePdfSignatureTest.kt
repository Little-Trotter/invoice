package de.littletrotter.application.invoice

import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.DocumentStore
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * Pins every branch of [ReceiptUploadService]'s private `isPdf` magic-byte check — the merged
 * short-circuit chain (`data != null && size >= 4 && %PDF`) evaluates each byte position
 * independently, so each wrong-byte case below exercises a distinct false-branch no upload
 * fixture elsewhere reaches (real uploads are either valid PDFs or rejected earlier).
 * Reflection because the check is deliberately private (same pattern as StatsEntityContractTest).
 */
class ReceiptUploadServicePdfSignatureTest {

    private val service = receiptUploadService(mock<ReceiptRepository>(), mock<DocumentStore>(), time = FIXED_TIME_SOURCE)

    private fun isPdf(data: ByteArray?): Boolean {
        val m = ReceiptUploadService::class.java.getDeclaredMethod("isPdf", ByteArray::class.java)
        m.isAccessible = true
        return m.invoke(service, data) as Boolean
    }

    @Test
    fun `null data is not a pdf`() = assertFalse(isPdf(null))

    @Test
    fun `fewer than four bytes is not a pdf`() = assertFalse(isPdf(byteArrayOf(0x25, 0x50, 0x44)))

    @Test
    fun `valid magic bytes are a pdf`() = assertTrue(isPdf("%PDF-1.7".toByteArray(Charsets.US_ASCII)))

    @Test
    fun `wrong byte at each of the four positions is not a pdf`() {
        val valid = "%PDF".toByteArray(Charsets.US_ASCII)
        for (position in 0..3) {
            val corrupted = valid.copyOf(8)
            corrupted[position] = 'X'.code.toByte()
            assertFalse(isPdf(corrupted), "byte $position corrupted must fail the signature check")
        }
    }
}
