package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException

/**
 * The consistency checks an invoice must pass before it is saved or issued. Reads the invoice
 * through its public state only, so the aggregate keeps just the entry point
 * ([Invoice.validate]) and the rules live in one place.
 */
internal object InvoiceValidation {

    fun validate(invoice: Invoice, requireItems: Boolean) {
        validateInvoiceNumber(invoice)
        validatePartiesAndAddresses(invoice)
        validateDatesAndPeriods(invoice)
        validateTotalsSign(invoice)
        validateItems(invoice, requireItems)
        invoice.paymentTerm?.validate()
    }

    private fun validateInvoiceNumber(invoice: Invoice) {
        if (invoice.status.isIssued && !invoice.invoiceNumber.isAssigned()) {
            throw InvoiceValidationException(InvoiceValidationError.INVOICE_NUMBER_MISSING)
        }
    }

    private fun validatePartiesAndAddresses(invoice: Invoice) {
        val sender = invoice.senderProfile ?: throw InvoiceValidationException(InvoiceValidationError.SENDER_PROFILE_MISSING)
        sender.validate()

        val rcpt = invoice.recipient ?: throw InvoiceValidationException(InvoiceValidationError.RECIPIENT_MISSING)
        rcpt.validate()

        invoice.effectiveSenderBillingAddress()?.validate()
        invoice.effectiveRecipientBillingAddress()?.validate()
        invoice.effectiveRecipientDeliveryAddress()?.validate()
    }

    private fun validateDatesAndPeriods(invoice: Invoice) {
        val periodFrom = invoice.servicePeriodFrom
        val periodTo = invoice.servicePeriodTo
        if (periodFrom != null && periodTo != null && periodTo.isBefore(periodFrom)) {
            throw InvoiceValidationException(InvoiceValidationError.SERVICE_PERIOD_END_BEFORE_START)
        }
        val dueDate = invoice.dueDate
        if (dueDate != null && dueDate.isBefore(invoice.invoiceDate)) {
            throw InvoiceValidationException(InvoiceValidationError.DUE_DATE_BEFORE_INVOICE_DATE)
        }
        if (invoice.paymentTermsDays < 0) {
            throw InvoiceValidationException(InvoiceValidationError.PAYMENT_TERM_DAYS_NEGATIVE)
        }
    }

    private fun validateTotalsSign(invoice: Invoice) {
        if (invoice.typeCode != InvoiceTypeCode.CORRECTED_INVOICE) {
            val totals = invoice.totals
            if (totals.netAmount.isNegative()) throw InvoiceValidationException(InvoiceValidationError.NET_AMOUNT_NEGATIVE)
            if (totals.vatAmount.isNegative()) throw InvoiceValidationException(InvoiceValidationError.VAT_AMOUNT_NEGATIVE)
            if (totals.grossAmount.isNegative()) throw InvoiceValidationException(InvoiceValidationError.GROSS_AMOUNT_NEGATIVE)
        }
    }

    private fun validateItems(invoice: Invoice, requireItems: Boolean) {
        val items = invoice.itemList
        if (requireItems && items.isEmpty()) throw InvoiceValidationException(InvoiceValidationError.NO_ITEMS)
        items.forEachIndexed { index, item ->
            try {
                item.validate()
            } catch (ex: InvoiceValidationException) {
                throw InvoiceValidationException(
                    InvoiceValidationError.ITEM_POSITION,
                    listOf(index + 1, ex.error.name),
                    cause = ex,
                )
            }
        }
    }
}
