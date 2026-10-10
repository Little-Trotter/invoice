package de.littletrotter.ui.specimen

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem

/**
 * Negative specimen for [de.littletrotter.arch.CommandBoundaryArchitectureTest] — a
 * `..ui..`-package class that reaches past the command boundary and calls a mutating [Invoice]
 * domain method directly, exactly what every UI write must instead do through an input port such
 * as [de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase]. Test-only, no
 * business data.
 */
class ViolatingUiCallsInvoiceDomainMethod {
    fun mutateDirectly(invoice: Invoice, item: InvoiceItem) {
        invoice.addItem(item)
    }
}
