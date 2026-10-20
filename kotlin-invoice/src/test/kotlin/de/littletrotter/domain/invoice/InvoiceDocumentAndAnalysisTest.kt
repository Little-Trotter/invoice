package de.littletrotter.domain.invoice

import de.littletrotter.fixture.anInvoiceDocument
import de.littletrotter.fixture.anInvoiceLlmAnalysis
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.HotelRate
import de.littletrotter.shared.Money
import de.littletrotter.domain.shared.ValidationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.fixture.anInvoice

class InvoiceDocumentAndAnalysisTest {

    // validation-status-truthful-pending.md R1/R7 — a freshly constructed Invoice/InvoiceDocument
    // has never been near an e-invoice run, so the default must be NOT_VALIDATED, not PENDING
    // (which now means "a generation job is genuinely in flight").
    @Test
    fun `invoice and invoice document default validationStatus to NOT_VALIDATED`() {
        assertEquals(ValidationStatus.NOT_VALIDATED, Invoice().validationStatus)
        assertEquals(ValidationStatus.NOT_VALIDATED, InvoiceDocument().validationStatus)
    }

    @Test
    fun `invoice document keeps assigned metadata and status fields`() {
        val invoice = anInvoice(id = 10L, invoiceNumber = InvoiceNumber("INV-2026-03-00010"))

        val document = anInvoiceDocument {
            id = 100L
            this.invoice = invoice
            documentType = "PDF"
            objectKey = "invoices/INV-2026-00010.pdf"
            fileName = "INV-2026-00010.pdf"
            mimeType = "application/pdf"
            sizeBytes = 2048
            sha256Checksum = "abc123"
            generatedAt = OffsetDateTime.parse("2026-04-01T12:00:00+00:00")
            generatedBy = "system"
            generationStatus = DocumentGenerationStatus.COMPLETED
            validationStatus = ValidationStatus.VALID
            validationMessages = "all good"
            kositReport = "ok"
            version = 3
        }

        assertEquals(invoice, document.invoice)
        assertEquals("PDF", document.documentType)
        assertEquals("invoices/INV-2026-00010.pdf", document.objectKey)
        assertEquals("INV-2026-00010.pdf", document.fileName)
        assertEquals("application/pdf", document.mimeType)
        assertEquals(2048L, document.sizeBytes)
        assertEquals("abc123", document.sha256Checksum)
        assertEquals(DocumentGenerationStatus.COMPLETED, document.generationStatus)
        assertEquals(ValidationStatus.VALID, document.validationStatus)
        assertEquals("all good", document.validationMessages)
        assertEquals("ok", document.kositReport)
        assertEquals(3L, document.version)
    }

    @Test
    fun `invoice llm analysis stores result payload and lifecycle metadata`() {
        val invoice = anInvoice(id = 11L, invoiceNumber = InvoiceNumber("INV-2026-03-00011"))

        val analysis = anInvoiceLlmAnalysis {
            id = 200L
            this.invoice = invoice
            status = LlmAnalysisStatus.COMPLETED
            rating = 4
            summary = "Structure is mostly valid"
            issues = "Missing BT-10"
            suggestions = "Add seller contact"
            improvedXml = "<xml/>"
            modelName = "gpt-x"
            analyzedAt = OffsetDateTime.parse("2026-04-01T12:30:00Z")
            version = 2
        }

        assertEquals(invoice, analysis.invoice)
        assertEquals(LlmAnalysisStatus.COMPLETED, analysis.status)
        assertEquals(4, analysis.rating)
        assertEquals("Structure is mostly valid", analysis.summary)
        assertEquals("Missing BT-10", analysis.issues)
        assertEquals("Add seller contact", analysis.suggestions)
        assertEquals("<xml/>", analysis.improvedXml)
        assertEquals("gpt-x", analysis.modelName)
        assertEquals(OffsetDateTime.parse("2026-04-01T12:30:00Z"), analysis.analyzedAt)
        assertEquals(2L, analysis.version)
    }

    @Test
    fun `hotel rate defaults and assignments are stable`() {
        val hotelRate = HotelRate()

        assertNull(hotelRate.country)
        assertEquals(Money.ZERO, hotelRate.ratePerNight)
        assertEquals(0L, hotelRate.version)

        hotelRate.edit { country = Country.DE }
        hotelRate.edit { ratePerNight = Money.of("129.90") }
        hotelRate.edit { validFrom = LocalDate.of(2026, 1, 1) }
        hotelRate.edit { validTo = LocalDate.of(2026, 12, 31) }

        assertEquals(Country.DE, hotelRate.country)
        assertEquals(Money.of("129.90"), hotelRate.ratePerNight)
        assertEquals(LocalDate.of(2026, 1, 1), hotelRate.validFrom)
        assertEquals(LocalDate.of(2026, 12, 31), hotelRate.validTo)
    }
}
