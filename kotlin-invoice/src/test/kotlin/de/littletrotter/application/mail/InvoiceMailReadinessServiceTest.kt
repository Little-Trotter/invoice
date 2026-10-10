package de.littletrotter.application.mail

import de.littletrotter.application.invoice.IssuedDocumentLocator
import de.littletrotter.application.mail.port.inbound.MailSendGate
import de.littletrotter.domain.invoice.DocumentGenerationStatus
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.invoice.forceStatusForTest
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
            forceStatusForTest(InvoiceStatus.SENT)
            validationStatus = ValidationStatus.VALID
            recipient = Recipient().apply { email = Email("kunde@example.com") }
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
        assertEquals(MailSendGate.READY, service.readiness(5L))
    }

    @Test
    fun `document missing without any issued job`() {
        whenever(jobs.findByInvoiceId(5L)).thenReturn(listOf(job(issued = false)))
        whenever(invoices.findById(5L)).thenReturn(invoiceWith())
        assertEquals(MailSendGate.DOCUMENT_MISSING, service.readiness(5L))
    }

    @Test
    fun `document missing when the checksum is missing`() {
        whenever(jobs.findByInvoiceId(5L)).thenReturn(listOf(job()))
        whenever(invoices.findById(5L)).thenReturn(invoiceWith(sha = null))
        assertEquals(MailSendGate.DOCUMENT_MISSING, service.readiness(5L))
    }

    @Test
    fun `a running send job blocks with job in flight`() {
        whenever(jobs.findByInvoiceId(5L)).thenReturn(
            listOf(job().apply { trigger = GenerationJobTrigger.SEND; status = GenerationJobStatus.RUNNING }),
        )
        whenever(invoices.findById(5L)).thenReturn(invoiceWith())
        assertEquals(MailSendGate.JOB_IN_FLIGHT, service.readiness(5L))
    }

    @Test
    fun `unknown invoice counts as not issued`() {
        whenever(invoices.findById(5L)).thenReturn(null)
        assertEquals(MailSendGate.NOT_ISSUED, service.readiness(5L))
    }
}
