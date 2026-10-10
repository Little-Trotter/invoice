package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.TripCommand

/**
 * Input port of the "edit an invoice's positions" use case: the item grid's and the item/
 * trip dialogs' write path. [CommandEntryPoint] marks this as the surface
 * `CommandBoundaryArchitectureTest` scans for entity leaks.
 */
interface EditInvoiceItemsUseCase : CommandEntryPoint {
    /** Item dialog input (goods/service). */
    fun addItemToInvoice(invoiceId: Long, command: InvoiceItemCommand): InvoiceDetailDto

    /** Item dialog edits (goods/service/per-diem). */
    fun updateInvoiceItem(invoiceId: Long, command: InvoiceItemCommand): InvoiceDetailDto

    /** WP1 (0.9.10) — [expectedVersion] is the invoice's `InvoiceDetailDto.version` the UI
     *  session saw; `null` (internal/non-UI callers) skips the check, same convention as
     *  [InvoiceItemCommand.expectedVersion]. */
    fun deleteItemFromInvoiceByPosition(invoiceId: Long, positionId: String, expectedVersion: Long? = null): InvoiceDetailDto

    /** The trip dialogs' (travel/per-diem) write path. */
    fun addTripToInvoice(invoiceId: Long, command: TripCommand): InvoiceDetailDto

    fun updateTripInInvoice(invoiceId: Long, positionId: String, command: TripCommand): InvoiceDetailDto
}
