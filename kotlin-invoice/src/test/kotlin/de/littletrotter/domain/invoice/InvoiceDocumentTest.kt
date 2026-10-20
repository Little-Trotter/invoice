package de.littletrotter.domain.invoice

import de.littletrotter.fixture.anInvoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class InvoiceDocumentTest {

    private val at = OffsetDateTime.parse("2026-10-01T10:00:00+00:00")
    private val invoice = anInvoice()

    private fun document(): InvoiceDocument =
        InvoiceDocument.create(invoice, "PDF", "invoices/2026/INV-1.pdf", "application/pdf")

    @Test
    fun `create derives file and short name from the object key`() {
        val doc = document()

        assertSame(invoice, doc.invoice)
        assertEquals("PDF", doc.documentType)
        assertEquals("invoices/2026/INV-1.pdf", doc.objectKey)
        assertEquals("INV-1.pdf", doc.fileName)
        assertEquals("INV-1.pdf", doc.shortName)
        assertEquals("application/pdf", doc.mimeType)
        assertEquals(DocumentGenerationStatus.PENDING, doc.generationStatus)
    }

    @Test
    fun `recordGenerated stores checksum and completes the row`() {
        val doc = document()

        doc.recordGenerated("abc", at)

        assertEquals("abc", doc.sha256Checksum)
        assertEquals(at, doc.generatedAt)
        assertEquals(DocumentGenerationStatus.COMPLETED, doc.generationStatus)
    }

    @Test
    fun `markReproduced tags the row with the lost original checksum`() {
        val doc = document()

        doc.markReproduced("old", at)

        assertTrue(doc.reproduced)
        assertEquals(at, doc.reproducedAt)
        assertEquals("old", doc.reproductionOfSha256)
    }

    @Test
    fun `markLost flags the stored object as gone`() {
        val doc = document().also { it.recordGenerated("abc", at) }

        doc.markLost()

        assertEquals(DocumentGenerationStatus.LOST, doc.generationStatus)
    }

    @Test
    fun `restoreOriginal takes the original checksum and drops the reproduction tag`() {
        val doc = document().also {
            it.recordGenerated("new", at)
            it.markReproduced("old", at)
        }

        doc.restoreOriginal("old")

        assertEquals("old", doc.sha256Checksum)
        assertFalse(doc.reproduced)
        assertNull(doc.reproducedAt)
        assertNull(doc.reproductionOfSha256)
    }
}
