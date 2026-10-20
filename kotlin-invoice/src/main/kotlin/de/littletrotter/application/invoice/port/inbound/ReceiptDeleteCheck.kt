package de.littletrotter.application.invoice.port.inbound

/** V4/receipt-delete-guard — outcome of [DeleteReceiptUseCase.checkDeleteEligibility]: the total
 *  number of links a receipt carries (item- AND invoice-level) and the invoice numbers among them
 *  that are already assigned (a draft has none yet). Any link at all makes the receipt undeletable,
 *  so the UI hides its delete affordance on [deletable] and never on the number list. */
data class ReceiptDeleteCheck(
    val assignedInvoiceNumbers: List<String>,
    val usageCount: Int,
) {
    val deletable: Boolean get() = usageCount == 0
}
