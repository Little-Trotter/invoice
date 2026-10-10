package de.littletrotter.application.invoice

import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.domain.invoice.Invoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/**
 * [EditInvoiceHeader]'s own tests: the header/reverse-charge/recalculate/snapshot-refresh write
 * path behind [de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase].
 */
class EditInvoiceHeaderTest {

    private fun useCase(
        headerUpdateService: InvoiceHeaderUpdateService = mock(),
        reverseChargeService: InvoiceReverseChargeService = mock(),
        invoiceLifecycleService: InvoiceLifecycleService = mock(),
        invoiceReadRepository: InvoiceReadRepository = mock(),
    ) = EditInvoiceHeader(headerUpdateService, reverseChargeService, invoiceLifecycleService, invoiceReadRepository)

    @Test
    fun `happy path - updateInvoiceHeader delegates to InvoiceHeaderUpdateService and reloads the invoice`() {
        val updated = Invoice().apply { id = 45L }
        val command = mock<UpdateInvoiceHeaderCommand>()
        val headerUpdateService = mock<InvoiceHeaderUpdateService> { on { updateHeader(command) }.thenReturn(updated) }
        val dto = mock<InvoiceDetailDto>()
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(45L) }.thenReturn(dto) }
        val useCase = useCase(headerUpdateService = headerUpdateService, invoiceReadRepository = invoiceReadRepository)

        assertEquals(dto, useCase.updateInvoiceHeader(command))
        verify(headerUpdateService).updateHeader(command)
    }

    @Test
    fun `boundary - updateInvoiceHeader throws when the invoice cannot be found again right after saving`() {
        val updated = Invoice().apply { id = 45L }
        val command = mock<UpdateInvoiceHeaderCommand>()
        val headerUpdateService = mock<InvoiceHeaderUpdateService> { on { updateHeader(command) }.thenReturn(updated) }
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(45L) }.thenReturn(null) }
        val useCase = useCase(headerUpdateService = headerUpdateService, invoiceReadRepository = invoiceReadRepository)

        val ex = assertThrows(IllegalStateException::class.java) { useCase.updateInvoiceHeader(command) }
        assertTrue(ex.message!!.contains("not found after save"))
    }

    @Test
    fun `boundary - setReverseChargeDecision delegates with the expected version and decision`() {
        val updated = Invoice().apply { id = 46L }
        val reverseChargeService = mock<InvoiceReverseChargeService> { on { setReverseChargeDecision(46L, 3L, true) }.thenReturn(updated) }
        val dto = mock<InvoiceDetailDto>()
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(46L) }.thenReturn(dto) }
        val useCase = useCase(reverseChargeService = reverseChargeService, invoiceReadRepository = invoiceReadRepository)

        assertEquals(dto, useCase.setReverseChargeDecision(46L, 3L, true))
        verify(reverseChargeService).setReverseChargeDecision(46L, 3L, true)
    }

    @Test
    fun `boundary - recalculateInvoice reloads the invoice returned by InvoiceLifecycleService`() {
        val updated = Invoice().apply { id = 48L }
        val lifecycleService = mock<InvoiceLifecycleService> { on { recalculate(48L) }.thenReturn(updated) }
        val dto = mock<InvoiceDetailDto>()
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(48L) }.thenReturn(dto) }
        val useCase = useCase(invoiceLifecycleService = lifecycleService, invoiceReadRepository = invoiceReadRepository)

        assertEquals(dto, useCase.recalculateInvoice(48L))
        verify(lifecycleService).recalculate(48L)
    }

    @Test
    fun `boundary - refreshPartySnapshots reloads the invoice returned by InvoiceLifecycleService`() {
        val refreshed = Invoice().apply { id = 50L }
        val lifecycleService = mock<InvoiceLifecycleService> { on { refreshPartySnapshots(50L) }.thenReturn(refreshed) }
        val dto = mock<InvoiceDetailDto>()
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(50L) }.thenReturn(dto) }
        val useCase = useCase(invoiceLifecycleService = lifecycleService, invoiceReadRepository = invoiceReadRepository)

        assertEquals(dto, useCase.refreshPartySnapshots(50L))
        verify(lifecycleService).refreshPartySnapshots(50L)
    }
}
