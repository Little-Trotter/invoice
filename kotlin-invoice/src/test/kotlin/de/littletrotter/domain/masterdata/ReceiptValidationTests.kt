package de.littletrotter.domain.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class ReceiptValidationTests {

    /** All mandatory fields set — the single spot each edge test below breaks one field of. */
    private fun validReceipt() = Receipt().apply {
        fileName = "bill.pdf"
        description = "Hotel Bonn"
        objectKey = "belege/bill.pdf"
        sizeBytes = 12345L
        sha256Checksum = "abc"
        uploadedAt = OffsetDateTime.now()
        documentDate = LocalDate.of(2026, 3, 14)
    }

    @Test
    fun `valid receipt passes`() {
        assertDoesNotThrow { validReceipt().validate() }
    }

    @Test
    fun `blank description throws`() {
        val r = validReceipt().apply { description = "" }
        assertThrows(InvalidParameterException::class.java) { r.validate() }
    }

    @Test
    fun `whitespace-only description throws`() {
        val r = validReceipt().apply { description = "   " }
        assertThrows(InvalidParameterException::class.java) { r.validate() }
    }

    @Test
    fun `missing document date throws`() {
        val r = validReceipt().apply { documentDate = null }
        assertThrows(InvalidParameterException::class.java) { r.validate() }
    }

    @Test
    fun `missing fileName throws`() {
        val r = Receipt().apply { fileName = "" }
        assertThrows(InvalidParameterException::class.java) { r.validate() }
    }

    @Test
    fun `negative size throws`() {
        val r = validReceipt().apply { sizeBytes = -10L }
        assertThrows(InvalidParameterException::class.java) { r.validate() }
    }

    @Test
    fun `missing checksum or upload time throws`() {
        val r1 = validReceipt().apply { sha256Checksum = "" }
        assertThrows(InvalidParameterException::class.java) { r1.validate() }

        val r2 = validReceipt().apply { uploadedAt = null }
        assertThrows(InvalidParameterException::class.java) { r2.validate() }
    }

    @Test
    fun `defaults to normalisation stage NONE and no original size`() {
        val r = validReceipt()

        assertEquals(ReceiptNormalisationStage.NONE, r.normalisationStage)
        assertNull(r.originalSizeBytes)
    }

    @Test
    fun `a null originalSizeBytes is valid — not every receipt has been through a reducer`() {
        val r = validReceipt().apply { originalSizeBytes = null }
        assertDoesNotThrow { r.validate() }
    }

    @Test
    fun `a non-negative originalSizeBytes is valid`() {
        val r = validReceipt().apply { originalSizeBytes = 0L }
        assertDoesNotThrow { r.validate() }
    }

    @Test
    fun `a negative originalSizeBytes throws`() {
        val r = validReceipt().apply { originalSizeBytes = -1L }
        assertThrows(InvalidParameterException::class.java) { r.validate() }
    }
}
