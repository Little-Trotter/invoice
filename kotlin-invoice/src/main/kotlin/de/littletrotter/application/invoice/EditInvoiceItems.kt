package de.littletrotter.application.invoice

import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.domain.invoice.Invoice
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [EditInvoiceItemsUseCase] on top of [InvoiceItemMutationService] and
 *  [TripService]. */
@ApplicationScoped
class EditInvoiceItems @Inject constructor(
    private val itemMutationService: InvoiceItemMutationService,
    private val tripService: TripService,
    private val invoiceReadRepository: InvoiceReadRepository,
) : EditInvoiceItemsUseCase {

    override fun addItemToInvoice(invoiceId: Long, command: InvoiceItemCommand): InvoiceDetailDto =
        reload(itemMutationService.addItem(invoiceId, command))

    override fun updateInvoiceItem(invoiceId: Long, command: InvoiceItemCommand): InvoiceDetailDto =
        reload(itemMutationService.updateItem(invoiceId, command))

    override fun deleteItemFromInvoiceByPosition(invoiceId: Long, positionId: String, expectedVersion: Long?): InvoiceDetailDto =
        reload(itemMutationService.deleteItemByPosition(invoiceId, positionId, expectedVersion))

    override fun addTripToInvoice(invoiceId: Long, command: TripCommand): InvoiceDetailDto =
        reload(tripService.addTrip(invoiceId, command))

    override fun updateTripInInvoice(invoiceId: Long, positionId: String, command: TripCommand): InvoiceDetailDto =
        reload(tripService.updateTrip(invoiceId, positionId, command))

    private fun reload(invoice: Invoice): InvoiceDetailDto =
        checkNotNull(invoiceReadRepository.findInvoiceDetailDto(invoice.id!!)) { "Invoice ${invoice.id} not found after save" }
}
