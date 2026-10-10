package de.littletrotter.application.invoice

import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.exception.InvoiceStatusTransitionException
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceDateBeforeServiceEndException
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.PdfUnrenderableTextException
import de.littletrotter.domain.repository.InvoiceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import de.littletrotter.shared.time.TimeSource
import java.time.LocalDate
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.never
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [InvoiceSendService.requestSend] is the sole entry point for the "Senden" action
 * (PO decision 2026-09-13). These tests pin its two responsibilities: the same
 * [de.littletrotter.domain.invoice.InvoiceStateMachine] guard [de.littletrotter.application.invoice.InvoiceLifecycleService]
 * itself enforces (so a stale UI action button can never bypass it), and that it always creates a
 * SEND-triggered job rather than mutating the invoice status directly.
 */
class InvoiceSendServiceTest {

    private val invoiceRepository = mock<InvoiceRepository>()
    private val creationService = mock<GenerationJobCreationService>()
    private val time = mock<TimeSource> { on { today() } doReturn LocalDate.of(2026, 9, 30) }
    private val b2bFieldValueService = mock<B2bFieldValueService>()
    private val policy = FinalizationPolicy(
        time, mock(), mock(), mock(), b2bFieldValueService, mock(), mock(),
    )
    private val service = InvoiceSendService(invoiceRepository, creationService, time, policy)

    @Test
    fun `happy path - requestSend creates a SEND-triggered job for an invoice in review`() {
        val invoice = Invoice.withInitialStatus(InvoiceStatus.IN_REVIEW).apply { id = 5L }
        whenever(invoiceRepository.findById(5L)).thenReturn(invoice)
        val job = GenerationJob().apply { id = 77L }
        whenever(creationService.createJob(5L, jobType = "SEND_ISSUE", lane = DocumentJobLane.INTERACTIVE, trigger = GenerationJobTrigger.SEND)).thenReturn(job)

        val result = service.requestSend(5L)

        assertEquals(job, result)
        verify(creationService).createJob(5L, jobType = "SEND_ISSUE", lane = DocumentJobLane.INTERACTIVE, trigger = GenerationJobTrigger.SEND)
    }

    @Test
    fun `boundary - requestSend throws InvoiceNotFoundException for an unknown invoice id`() {
        whenever(invoiceRepository.findById(9L)).thenReturn(null)

        assertThrows(InvoiceNotFoundException::class.java) { service.requestSend(9L) }
    }

    @Test
    fun `boundary - requestSend rejects a DRAFT invoice, which has no direct edge to SENT`() {
        val invoice = Invoice.withInitialStatus(InvoiceStatus.DRAFT).apply { id = 6L }
        whenever(invoiceRepository.findById(6L)).thenReturn(invoice)

        assertThrows(InvoiceStatusTransitionException::class.java) { service.requestSend(6L) }
    }

    @Test
    fun `boundary - requestSend rejects an already-SENT invoice, which has no SENT-to-SENT edge`() {
        val invoice = Invoice.withInitialStatus(InvoiceStatus.SENT).apply { id = 7L }
        whenever(invoiceRepository.findById(7L)).thenReturn(invoice)

        assertThrows(InvoiceStatusTransitionException::class.java) { service.requestSend(7L) }
    }

    private fun inReviewWithDeliveryDate(id: Long, delivery: LocalDate?): Invoice {
        val invoice = Invoice.withInitialStatus(InvoiceStatus.IN_REVIEW).apply {
            this.id = id
            deliveryDate = delivery
        }
        whenever(invoiceRepository.findById(id)).thenReturn(invoice)
        return invoice
    }

    @Test
    fun `boundary - service end equal to today still creates the job`() {
        inReviewWithDeliveryDate(11L, LocalDate.of(2026, 9, 30))
        val job = GenerationJob().apply { id = 78L }
        whenever(creationService.createJob(11L, jobType = "SEND_ISSUE", lane = DocumentJobLane.INTERACTIVE, trigger = GenerationJobTrigger.SEND)).thenReturn(job)

        assertEquals(job, service.requestSend(11L))
    }

    @Test
    fun `boundary - service end tomorrow is rejected synchronously and no job is created`() {
        inReviewWithDeliveryDate(12L, LocalDate.of(2026, 10, 1))

        assertThrows(InvoiceDateBeforeServiceEndException::class.java) { service.requestSend(12L) }
        verify(creationService, never()).createJob(12L, jobType = "SEND_ISSUE", lane = DocumentJobLane.INTERACTIVE, trigger = GenerationJobTrigger.SEND)
    }

    @Test
    fun `free text with a character the PDF fonts lack is rejected and no job is created`() {
        inReviewWithDeliveryDate(14L, null).freeText = "Danke 💩"

        val ex = assertThrows(PdfUnrenderableTextException::class.java) { service.requestSend(14L) }
        assertEquals(listOf("💩"), ex.fields["Freitext"])
        verify(creationService, never()).createJob(14L, jobType = "SEND_ISSUE", lane = DocumentJobLane.INTERACTIVE, trigger = GenerationJobTrigger.SEND)
    }

    @Test
    fun `a B2B value with a character the PDF fonts lack is rejected and no job is created`() {
        inReviewWithDeliveryDate(15L, null)
        whenever(b2bFieldValueService.findFieldValues(B2bFieldOwnerType.INVOICE, 15L)).thenReturn(mapOf("BT-18" to "Ref 😀"))

        val ex = assertThrows(PdfUnrenderableTextException::class.java) { service.requestSend(15L) }
        assertEquals(listOf("😀"), ex.fields["B2B-Feld BT-18"])
        verify(creationService, never()).createJob(15L, jobType = "SEND_ISSUE", lane = DocumentJobLane.INTERACTIVE, trigger = GenerationJobTrigger.SEND)
    }

    @Test
    fun `boundary - absent service end creates the job`() {
        inReviewWithDeliveryDate(13L, null)
        val job = GenerationJob().apply { id = 79L }
        whenever(creationService.createJob(13L, jobType = "SEND_ISSUE", lane = DocumentJobLane.INTERACTIVE, trigger = GenerationJobTrigger.SEND)).thenReturn(job)

        assertEquals(job, service.requestSend(13L))
    }
}
