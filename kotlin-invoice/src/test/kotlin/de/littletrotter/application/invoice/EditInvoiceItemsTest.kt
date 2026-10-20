package de.littletrotter.application.invoice

import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.outbound.InvoiceReadRepository
import de.littletrotter.fixture.anInvoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/**
 * [EditInvoiceItems]'s own tests: the item/trip write path behind
 * [de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase].
 */
class EditInvoiceItemsTest {

    private fun useCase(
        itemMutationService: InvoiceItemMutationService = mock(),
        tripService: TripService = mock(),
        invoiceReadRepository: InvoiceReadRepository = mock(),
    ) = EditInvoiceItems(itemMutationService, tripService, invoiceReadRepository)

    @Test
    fun `happy path - addItemToInvoice reloads the invoice returned by InvoiceItemMutationService`() {
        val updated = anInvoice(id = 41L)
        val command = mock<InvoiceItemCommand>()
        val itemMutationService = mock<InvoiceItemMutationService> { on { addItem(41L, command) }.thenReturn(updated) }
        val dto = mock<InvoiceDetailDto>()
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(41L) }.thenReturn(dto) }
        val useCase = useCase(itemMutationService = itemMutationService, invoiceReadRepository = invoiceReadRepository)

        assertEquals(dto, useCase.addItemToInvoice(41L, command))
        verify(itemMutationService).addItem(41L, command)
    }

    @Test
    fun `boundary - addItemToInvoice throws when the invoice cannot be found again right after saving`() {
        val updated = anInvoice(id = 41L)
        val command = mock<InvoiceItemCommand>()
        val itemMutationService = mock<InvoiceItemMutationService> { on { addItem(41L, command) }.thenReturn(updated) }
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(41L) }.thenReturn(null) }
        val useCase = useCase(itemMutationService = itemMutationService, invoiceReadRepository = invoiceReadRepository)

        val ex = assertThrows(IllegalStateException::class.java) { useCase.addItemToInvoice(41L, command) }
        assertTrue(ex.message!!.contains("not found after save"))
    }

    @Test
    fun `boundary - updateInvoiceItem reloads the invoice returned by InvoiceItemMutationService`() {
        val updated = anInvoice(id = 47L)
        val command = mock<InvoiceItemCommand>()
        val itemMutationService = mock<InvoiceItemMutationService> { on { updateItem(47L, command) }.thenReturn(updated) }
        val dto = mock<InvoiceDetailDto>()
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(47L) }.thenReturn(dto) }
        val useCase = useCase(itemMutationService = itemMutationService, invoiceReadRepository = invoiceReadRepository)

        assertEquals(dto, useCase.updateInvoiceItem(47L, command))
        verify(itemMutationService).updateItem(47L, command)
    }

    @Test
    fun `boundary - deleteItemFromInvoiceByPosition reloads the invoice returned by InvoiceItemMutationService`() {
        val updated = anInvoice(id = 42L)
        val itemMutationService = mock<InvoiceItemMutationService> { on { deleteItemByPosition(42L, "pos-1") }.thenReturn(updated) }
        val dto = mock<InvoiceDetailDto>()
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(42L) }.thenReturn(dto) }
        val useCase = useCase(itemMutationService = itemMutationService, invoiceReadRepository = invoiceReadRepository)

        assertEquals(dto, useCase.deleteItemFromInvoiceByPosition(42L, "pos-1"))
        verify(itemMutationService).deleteItemByPosition(42L, "pos-1")
    }

    @Test
    fun `boundary - addTripToInvoice delegates to TripService and reloads the invoice`() {
        val updated = anInvoice(id = 43L)
        val command = mock<TripCommand>()
        val tripService = mock<TripService> { on { addTrip(43L, command) }.thenReturn(updated) }
        val dto = mock<InvoiceDetailDto>()
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(43L) }.thenReturn(dto) }
        val useCase = useCase(tripService = tripService, invoiceReadRepository = invoiceReadRepository)

        assertEquals(dto, useCase.addTripToInvoice(43L, command))
        verify(tripService).addTrip(43L, command)
    }

    @Test
    fun `boundary - updateTripInInvoice delegates to TripService and reloads the invoice`() {
        val updated = anInvoice(id = 44L)
        val command = mock<TripCommand>()
        val tripService = mock<TripService> { on { updateTrip(44L, "pos-2", command) }.thenReturn(updated) }
        val dto = mock<InvoiceDetailDto>()
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(44L) }.thenReturn(dto) }
        val useCase = useCase(tripService = tripService, invoiceReadRepository = invoiceReadRepository)

        assertEquals(dto, useCase.updateTripInInvoice(44L, "pos-2", command))
        verify(tripService).updateTrip(44L, "pos-2", command)
    }
}
