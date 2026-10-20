package de.littletrotter.application.invoice.port.outbound

import de.littletrotter.domain.invoice.InvoiceStatus

/** Output port: tells open screens that an invoice reached a new status. */
fun interface InvoiceStatusPublisher {
    fun publishInvoiceStatusChanged(invoiceId: Long, status: InvoiceStatus)
}
