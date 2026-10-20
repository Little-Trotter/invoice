package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.spi.form.InvoiceFormModule

/** Input port through which the invoice form obtains the registered form modules. */
fun interface InvoiceFormModuleQueryPort {
    /** Every registered form module. */
    fun modules(): List<InvoiceFormModule>
}
