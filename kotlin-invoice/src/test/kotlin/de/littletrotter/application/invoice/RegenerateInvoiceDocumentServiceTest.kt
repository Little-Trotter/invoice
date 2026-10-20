package de.littletrotter.application.invoice

import de.littletrotter.domain.exception.InvoiceDocumentRegenerationNotApplicableException
import de.littletrotter.domain.exception.InvoiceGenerationAlreadyRunningException
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.forceStatusForTest
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.fixture.anInvoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

class RegenerateInvoiceDocumentServiceTest {

    private val invoices: InvoiceRepository = mock()
    private val jobs: GenerationJobRepository = mock()
    private val creation: GenerationJobCreationService = mock()
    private val service = RegenerateInvoiceDocumentService(invoices, jobs, creation)

    private fun invoice(status: InvoiceStatus) = anInvoice(id = 7L, invoiceNumber = InvoiceNumber("RE-2025-01-00007"))
        .also { it.forceStatusForTest(status) }

    private fun job(id: Long, status: GenerationJobStatus, hour: Long, type: String = "PRINT_JOB") = aGenerationJob {
        this.id = id
        this.status = status
        this.jobType = type
        this.createdAt = OffsetDateTime.parse("2026-10-01T00:00:00Z").plusHours(hour)
    }

    private fun given(status: InvoiceStatus, vararg existing: GenerationJob) {
        whenever(invoices.findByIdWithItems(7L)).thenReturn(invoice(status))
        whenever(jobs.findByInvoiceId(7L)).thenReturn(existing.toList())
    }

    @Test
    fun `a failed latest job of an issued invoice gets a new recovery job`() {
        given(InvoiceStatus.ISSUED, job(1, GenerationJobStatus.COMPLETED, 0), job(2, GenerationJobStatus.FAILED, 1))
        whenever(creation.createJob(eq(7L), eq("PRINT_JOB"), eq(DocumentJobLane.BULK), anyOrNull(), any(), eq(GenerationJobTrigger.REGENERATION)))
            .thenReturn(job(3, GenerationJobStatus.PENDING, 2))

        assertEquals(3L, service.regenerate(7L))
    }

    @ParameterizedTest
    @EnumSource(GenerationJobStatus::class, names = ["PENDING", "DELEGATED", "RUNNING"])
    fun `a job still in flight rejects the request`(status: GenerationJobStatus) {
        given(InvoiceStatus.ISSUED, job(1, GenerationJobStatus.FAILED, 0), job(2, status, 1))

        assertThrows<InvoiceGenerationAlreadyRunningException> { service.regenerate(7L) }
        verify(creation, never()).createJob(any<Long>(), any(), any(), anyOrNull(), any(), any())
    }

    @Test
    fun `a completed latest job or no job at all is not a failure to repair`() {
        given(InvoiceStatus.ISSUED, job(1, GenerationJobStatus.FAILED, 0), job(2, GenerationJobStatus.COMPLETED, 1))
        assertThrows<InvoiceDocumentRegenerationNotApplicableException> { service.regenerate(7L) }

        given(InvoiceStatus.ISSUED)
        assertThrows<InvoiceDocumentRegenerationNotApplicableException> { service.regenerate(7L) }
    }

    @Test
    fun `only print jobs count when picking the latest job`() {
        given(InvoiceStatus.ISSUED, job(1, GenerationJobStatus.FAILED, 0), job(2, GenerationJobStatus.RUNNING, 1, type = "OTHER"))
        whenever(creation.createJob(eq(7L), eq("PRINT_JOB"), eq(DocumentJobLane.BULK), anyOrNull(), any(), eq(GenerationJobTrigger.REGENERATION)))
            .thenReturn(job(3, GenerationJobStatus.PENDING, 2))

        assertEquals(3L, service.regenerate(7L))
    }

    @ParameterizedTest
    @EnumSource(InvoiceStatus::class, names = ["DRAFT", "IN_REVIEW", "VOIDED"])
    fun `an invoice that is not issued is rejected`(status: InvoiceStatus) {
        given(status, job(1, GenerationJobStatus.FAILED, 0))

        assertThrows<InvoiceDocumentRegenerationNotApplicableException> { service.regenerate(7L) }
        verify(creation, never()).createJob(any<Long>(), any(), any(), anyOrNull(), any(), any())
    }

    @Test
    fun `an unknown invoice is reported as not found`() {
        whenever(invoices.findByIdWithItems(7L)).thenReturn(null)

        assertThrows<InvoiceNotFoundException> { service.regenerate(7L) }
    }
}
