package de.littletrotter.application.invoice

import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.outbound.InvoiceReadRepository
import de.littletrotter.fixture.anInvoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/** [CreateInvoice]'s own forwarding tests: createInvoiceWithRecipient behind
 *  [de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase]. */
class CreateInvoiceTest {

    @Test
    fun `happy path - createInvoiceWithRecipient omits reverseChargeDecision and reloads the created invoice`() {
        val created = anInvoice(id = 30L)
        val lifecycleService = mock<InvoiceLifecycleService> { on { create(1L, 2L, null) }.thenReturn(created) }
        val dto = mock<InvoiceDetailDto>()
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(30L) }.thenReturn(dto) }
        val useCase = CreateInvoice(lifecycleService, invoiceReadRepository)

        val result = useCase.createInvoiceWithRecipient(1L, 2L)

        assertEquals(dto, result)
        verify(lifecycleService).create(1L, 2L, null)
    }

    @Test
    fun `boundary - createInvoiceWithRecipient forwards an explicit reverseChargeDecision`() {
        val created = anInvoice(id = 31L)
        val lifecycleService = mock<InvoiceLifecycleService> { on { create(1L, 2L, true) }.thenReturn(created) }
        val dto = mock<InvoiceDetailDto>()
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(31L) }.thenReturn(dto) }
        val useCase = CreateInvoice(lifecycleService, invoiceReadRepository)

        assertEquals(dto, useCase.createInvoiceWithRecipient(1L, 2L, true))
        verify(lifecycleService).create(1L, 2L, true)
    }
}
