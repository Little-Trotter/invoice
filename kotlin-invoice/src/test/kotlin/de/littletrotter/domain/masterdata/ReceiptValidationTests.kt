package de.littletrotter.domain.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.fixture.aReceipt
import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class ReceiptValidationTests {

    /** All mandatory fields set — the single spot each edge test below breaks one field of. */
    private fun validReceipt() = aReceipt {
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
        val r = validReceipt().edit { description = "" }
        assertThrows(InvalidParameterException::class.java) { r.validate() }
    }

    @Test
    fun `whitespace-only description throws`() {
        val r = validReceipt().edit { description = "   " }
        assertThrows(InvalidParameterException::class.java) { r.validate() }
    }

    @Test
    fun `missing document date throws`() {
        val r = validReceipt().edit { documentDate = null }
        assertThrows(InvalidParameterException::class.java) { r.validate() }
    }

    @Test
    fun `missing fileName throws`() {
        val r = aReceipt { fileName = "" }
        assertThrows(InvalidParameterException::class.java) { r.validate() }
    }

    @Test
    fun `negative size throws`() {
        val r = validReceipt().edit { sizeBytes = -10L }
        assertThrows(InvalidParameterException::class.java) { r.validate() }
    }

    @Test
    fun `missing checksum or upload time throws`() {
        val r1 = validReceipt().edit { sha256Checksum = "" }
        assertThrows(InvalidParameterException::class.java) { r1.validate() }

        val r2 = validReceipt().edit { uploadedAt = null }
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
        val r = validReceipt().edit { originalSizeBytes = null }
        assertDoesNotThrow { r.validate() }
    }

    @Test
    fun `a non-negative originalSizeBytes is valid`() {
        val r = validReceipt().edit { originalSizeBytes = 0L }
        assertDoesNotThrow { r.validate() }
    }

    @Test
    fun `a negative originalSizeBytes throws`() {
        val r = validReceipt().edit { originalSizeBytes = -1L }
        assertThrows(InvalidParameterException::class.java) { r.validate() }
    }

    private fun upload() = ReceiptUpload(
        fileName = "bill.pdf",
        description = "Hotel Bonn",
        objectKey = "belege/bill.pdf",
        documentDate = LocalDate.of(2026, 3, 14),
        sizeBytes = 12345L,
        sha256 = "abc",
        uploadedAt = OffsetDateTime.parse("2026-03-15T08:00:00Z"),
        normalisationStage = ReceiptNormalisationStage.NONE,
        originalSizeBytes = 12345L,
    )

    @Test
    fun `create stores the upload fields and yields a valid receipt`() {
        val r = Receipt.create(upload())
        assertEquals("bill.pdf", r.fileName)
        assertEquals("Hotel Bonn", r.description)
        assertEquals("belege/bill.pdf", r.objectKey)
        assertEquals(LocalDate.of(2026, 3, 14), r.documentDate)
        assertEquals(12345L, r.sizeBytes)
        assertEquals("abc", r.sha256Checksum)
        assertEquals(ReceiptNormalisationStage.NONE, r.normalisationStage)
        assertEquals(12345L, r.originalSizeBytes)
        assertDoesNotThrow { r.validate() }
    }

    @Test
    fun `describe replaces description and document date`() {
        val r = Receipt.create(upload())
        r.describe("Taxi", LocalDate.of(2026, 4, 1))
        assertEquals("Taxi", r.description)
        assertEquals(LocalDate.of(2026, 4, 1), r.documentDate)
    }

    @Test
    fun `recordOriginalSize keeps the first value`() {
        val r = Receipt.create(upload().copy(originalSizeBytes = null))
        r.recordOriginalSize(500L)
        r.recordOriginalSize(900L)
        assertEquals(500L, r.originalSizeBytes)
    }
}
