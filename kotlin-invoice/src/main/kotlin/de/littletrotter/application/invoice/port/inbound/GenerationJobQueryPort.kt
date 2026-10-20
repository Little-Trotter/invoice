package de.littletrotter.application.invoice.port.inbound

/**
 * Input port for reading generation jobs as display models: the jobs of one invoice, and a single
 * job together with its invoice's id and number (download route).
 */
interface GenerationJobQueryPort {
    fun findJobViewsByInvoiceId(invoiceId: Long): List<GenerationJobView>

    /** Loads the job with its invoice in one transaction; `null` for an unknown id. */
    fun findJobViewWithInvoice(id: Long): GenerationJobView?
}
