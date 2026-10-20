package de.littletrotter.application.invoice

import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.outbound.InvoiceReadRepository
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.fixture.anInvoice
import jakarta.enterprise.event.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/**
 * [ChangeInvoiceStatus]'s own tests: the lifecycle-transition/send/Cancellation write path behind
 * [de.littletrotter.application.invoice.port.inbound.ChangeInvoiceStatusUseCase].
 */
class ChangeInvoiceStatusTest {

    private fun useCase(
        invoiceLifecycleService: InvoiceLifecycleService = mock(),
        invoiceSendService: InvoiceSendService = mock(),
        invoiceCancellationService: InvoiceCancellationService = mock(),
        invoiceReadRepository: InvoiceReadRepository = mock(),
        creditNoteIssued: Event<CreditNoteIssued> = mock(),
    ) = ChangeInvoiceStatus(invoiceLifecycleService, invoiceSendService, invoiceCancellationService, invoiceReadRepository, creditNoteIssued)

    @Test
    fun `happy path - transitionInvoice omits overrideReason and reloads the transitioned invoice`() {
        val transitioned = anInvoice(id = 32L)
        val lifecycleService = mock<InvoiceLifecycleService> { on { transition(32L, InvoiceStatus.ISSUED, null) }.thenReturn(transitioned) }
        val dto = mock<InvoiceDetailDto>()
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(32L) }.thenReturn(dto) }
        val useCase = useCase(invoiceLifecycleService = lifecycleService, invoiceReadRepository = invoiceReadRepository)

        assertEquals(dto, useCase.transitionInvoice(32L, InvoiceStatus.ISSUED))
        verify(lifecycleService).transition(32L, InvoiceStatus.ISSUED, null)
    }

    @Test
    fun `boundary - transitionInvoice forwards an explicit overrideReason`() {
        val transitioned = anInvoice(id = 33L)
        val lifecycleService = mock<InvoiceLifecycleService> { on { transition(33L, InvoiceStatus.ISSUED, "reason") }.thenReturn(transitioned) }
        val dto = mock<InvoiceDetailDto>()
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(33L) }.thenReturn(dto) }
        val useCase = useCase(invoiceLifecycleService = lifecycleService, invoiceReadRepository = invoiceReadRepository)

        assertEquals(dto, useCase.transitionInvoice(33L, InvoiceStatus.ISSUED, "reason"))
        verify(lifecycleService).transition(33L, InvoiceStatus.ISSUED, "reason")
    }

    @Test
    fun `boundary - transitionInvoice throws when the invoice cannot be found again right after saving`() {
        val transitioned = anInvoice(id = 34L)
        val lifecycleService = mock<InvoiceLifecycleService> { on { transition(34L, InvoiceStatus.ISSUED, null) }.thenReturn(transitioned) }
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(34L) }.thenReturn(null) }
        val useCase = useCase(invoiceLifecycleService = lifecycleService, invoiceReadRepository = invoiceReadRepository)

        val ex = assertThrows(IllegalStateException::class.java) { useCase.transitionInvoice(34L, InvoiceStatus.ISSUED) }
        assertTrue(ex.message!!.contains("not found after save"))
    }

    @Test
    fun `happy path - requestSend delegates to InvoiceSendService and reloads by id`() {
        val invoiceSendService = mock<InvoiceSendService> { on { requestSend(40L) }.thenReturn(mock<GenerationJob>()) }
        val dto = mock<InvoiceDetailDto>()
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(40L) }.thenReturn(dto) }
        val useCase = useCase(invoiceSendService = invoiceSendService, invoiceReadRepository = invoiceReadRepository)

        assertEquals(dto, useCase.requestSend(40L))
        verify(invoiceSendService).requestSend(40L)
    }

    @Test
    fun `boundary - requestSend throws when the invoice cannot be reloaded after sending`() {
        val invoiceSendService = mock<InvoiceSendService> { on { requestSend(40L) }.thenReturn(mock<GenerationJob>()) }
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(40L) }.thenReturn(null) }
        val useCase = useCase(invoiceSendService = invoiceSendService, invoiceReadRepository = invoiceReadRepository)

        val ex = assertThrows(IllegalStateException::class.java) { useCase.requestSend(40L) }
        assertTrue(ex.message!!.contains("not found after requesting send"))
    }

    @Test
    fun `happy path - createCreditNote delegates to InvoiceCancellationService and reloads the saved credit note`() {
        val creditNote = anInvoice(id = 77L)
        val invoiceCancellationService = mock<InvoiceCancellationService> {
            on { createCreditNote(42L, "Falsch berechnet", null) }.thenReturn(creditNote)
        }
        val dto = mock<InvoiceDetailDto>()
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(77L) }.thenReturn(dto) }
        val useCase = useCase(invoiceCancellationService = invoiceCancellationService, invoiceReadRepository = invoiceReadRepository)

        val result = useCase.createCreditNote(42L, "Falsch berechnet")

        assertEquals(dto, result)
        verify(invoiceCancellationService).createCreditNote(42L, "Falsch berechnet", null)
    }

    @Test
    fun `boundary - createCreditNote forwards an explicit reasonEn`() {
        val creditNote = anInvoice(id = 78L)
        val invoiceCancellationService = mock<InvoiceCancellationService> {
            on { createCreditNote(43L, "Falsch berechnet", "Wrong amount") }.thenReturn(creditNote)
        }
        val dto = mock<InvoiceDetailDto>()
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(78L) }.thenReturn(dto) }
        val useCase = useCase(invoiceCancellationService = invoiceCancellationService, invoiceReadRepository = invoiceReadRepository)

        val result = useCase.createCreditNote(43L, "Falsch berechnet", "Wrong amount")

        assertEquals(dto, result)
        verify(invoiceCancellationService).createCreditNote(43L, "Falsch berechnet", "Wrong amount")
    }

    @Test
    fun `createCreditNote fires the issued event for the saved credit note and reloads it`() {
        val creditNote = anInvoice(id = 77L)
        val cancellation = mock<InvoiceCancellationService> { on { createCreditNote(5L, "Grund", null) }.thenReturn(creditNote) }
        val dto = mock<InvoiceDetailDto>()
        val readRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(77L) }.thenReturn(dto) }
        val events = mock<Event<CreditNoteIssued>>()

        val result = useCase(invoiceCancellationService = cancellation, invoiceReadRepository = readRepository, creditNoteIssued = events)
            .createCreditNote(5L, "Grund", null)

        assertEquals(dto, result)
        verify(events).fire(CreditNoteIssued(77L))
    }
}
