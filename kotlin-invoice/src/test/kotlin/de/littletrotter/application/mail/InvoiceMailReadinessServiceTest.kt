package de.littletrotter.application.mail

import de.littletrotter.application.invoice.IssuedDocumentLocator
import de.littletrotter.application.mail.port.inbound.MailDocumentReadiness
import de.littletrotter.domain.invoice.DocumentGenerationStatus
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceDocumentRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.shared.GenerationJobResultRefs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

@DisplayName("InvoiceMailReadinessService")
class InvoiceMailReadinessServiceTest {

    private lateinit var invoices: InvoiceRepository
    private lateinit var jobs: GenerationJobRepository
    private lateinit var service: InvoiceMailReadinessService

    @BeforeEach
    fun setUp() {
        invoices = mock()
        jobs = mock()
        service = InvoiceMailReadinessService(invoices, jobs, IssuedDocumentLocator(jobs, mock<InvoiceDocumentRepository>()))
    }

    private fun job(
        status: GenerationJobStatus = GenerationJobStatus.COMPLETED,
        issued: Boolean = true,
        key: String = "k.pdf",
    ) = GenerationJob().apply {
        this.id = 1L
        this.status = status
        this.issued = issued
        this.finishedAt = OffsetDateTime.parse("2026-10-01T10:00:00Z")
        this.resultRefs = GenerationJobResultRefs.encode(GenerationJobResultRefs(objectKey = key))
    }

    private fun invoiceWith(sha: String? = "a".repeat(64), key: String = "k.pdf", status: DocumentGenerationStatus = DocumentGenerationStatus.COMPLETED) =
        Invoice().apply {
            id = 5L
            documents.asMutableForTest().add(
                InvoiceDocument().apply {
                    objectKey = key
                    generationStatus = status
                    sha256Checksum = sha
                    mimeType = "application/pdf"
                },
            )
        }

    @Test
    fun `ready when the issued job is completed and its document is checksummed`() {
        whenever(jobs.findByInvoiceId(5L)).thenReturn(listOf(job()))
        whenever(invoices.findById(5L)).thenReturn(invoiceWith())
        assertEquals(MailDocumentReadiness.READY, service.readiness(5L))
    }

    @Test
    fun `not requested without any issued job`() {
        whenever(jobs.findByInvoiceId(5L)).thenReturn(listOf(job(issued = false)))
        assertEquals(MailDocumentReadiness.NOT_REQUESTED, service.readiness(5L))
    }

    @Test
    fun `pending while the issued job still runs`() {
        whenever(jobs.findByInvoiceId(5L)).thenReturn(listOf(job(status = GenerationJobStatus.RUNNING)))
        whenever(invoices.findById(5L)).thenReturn(invoiceWith())
        assertEquals(MailDocumentReadiness.PENDING, service.readiness(5L))
    }

    @Test
    fun `pending when the job is completed but the checksum is missing`() {
        whenever(jobs.findByInvoiceId(5L)).thenReturn(listOf(job()))
        whenever(invoices.findById(5L)).thenReturn(invoiceWith(sha = null))
        assertEquals(MailDocumentReadiness.PENDING, service.readiness(5L))
    }

    @Test
    fun `a failed issued job is not pending - sending starts a new generation`() {
        whenever(jobs.findByInvoiceId(5L)).thenReturn(listOf(job(status = GenerationJobStatus.FAILED)))
        whenever(invoices.findById(5L)).thenReturn(invoiceWith())
        assertEquals(MailDocumentReadiness.NOT_REQUESTED, service.readiness(5L))
    }

    @Test
    fun `unknown invoice counts as not requested`() {
        whenever(jobs.findByInvoiceId(5L)).thenReturn(listOf(job()))
        whenever(invoices.findById(5L)).thenReturn(null)
        assertEquals(MailDocumentReadiness.NOT_REQUESTED, service.readiness(5L))
    }
}
