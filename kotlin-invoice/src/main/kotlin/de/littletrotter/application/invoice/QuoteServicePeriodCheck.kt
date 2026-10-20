package de.littletrotter.application.invoice

import de.littletrotter.domain.compliance.InvoicePeriodInput
import de.littletrotter.domain.compliance.PeriodConsistencyRules

/**
 * Checks the delivery date and the service period an invoice order carries before any invoice is
 * written, with the same rules the invoice itself is later checked against, so an order that would
 * produce an unfinishable draft is refused with a reason the clerk can act on.
 */
object QuoteServicePeriodCheck {

    /** The German refusal reason, or `null` when the dates are consistent. */
    fun reject(command: CreateInvoiceFromQuoteCommand): String? {
        val from = command.servicePeriodFrom
        val to = command.servicePeriodTo
        if ((from == null) != (to == null)) {
            return "Der Leistungszeitraum braucht Beginn und Ende oder keines von beiden."
        }
        val findings = PeriodConsistencyRules.evaluate(
            InvoicePeriodInput(
                invoiceDate = null,
                deliveryDate = command.deliveryDate,
                periodFrom = from,
                periodTo = to,
            ),
        )
        return findings.firstOrNull { it.isBlocking }?.let { finding ->
            when (finding.ruleId) {
                PeriodConsistencyRules.BR_29 -> "Das Ende des Leistungszeitraums liegt vor dem Beginn."
                else -> "Das Leistungsdatum liegt außerhalb des Leistungszeitraums."
            }
        }
    }
}
