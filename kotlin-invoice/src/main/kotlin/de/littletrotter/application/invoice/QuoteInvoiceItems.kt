package de.littletrotter.application.invoice

import de.littletrotter.application.quote.port.inbound.QuoteItem
import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.repository.TaxRateRepository
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.country.Country
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.LocalDate

/**
 * Takes the positions of an accepted offer over into an invoice, one to one.
 *
 * The offer line already carries the position kind and the unit in the invoice's own vocabulary,
 * so both are taken over unchanged. Its tax is named by category and percentage while the invoice
 * wants a tax rate row; a position without a matching row is refused with a reason instead of
 * being bent into something the clerk did not agree to.
 */
@ApplicationScoped
class QuoteInvoiceItems @Inject constructor(
    private val itemMutation: InvoiceItemMutationService,
    private val taxRates: TaxRateRepository,
    private val b2bValues: B2bFieldValueService,
) {

    /** Appends [item] to the draft [invoiceId]; the article number and the customer's own reference become BT-155/BT-156. */
    fun add(invoiceId: Long, item: QuoteItem, invoiceDate: LocalDate) {
        val rate = taxRateFor(item, invoiceDate)
            ?: throw QuoteInvoiceRejectedException(
                "Für Position ${item.position} gibt es keinen gültigen Steuersatz " +
                    "(${item.vatCategory.code}, ${item.vatRate.stripTrailingZeros().toPlainString()} %).",
            )
        val saved = itemMutation.addItem(
            invoiceId,
            InvoiceItemCommand(
                category = item.category,
                description = item.description,
                buyerItemDescription = item.customerArticleName,
                quantity = item.quantity,
                unit = item.unit,
                unitPriceNet = item.unitPrice,
                taxRateId = rate.id,
            ),
        )
        val identifiers = buildMap {
            item.articleNumber?.takeIf { it.isNotBlank() }?.let { put(B2bTerm.BT_155.officialCode, it) }
            item.customerReference?.takeIf { it.isNotBlank() }?.let { put(B2bTerm.BT_156.officialCode, it) }
        }
        if (identifiers.isNotEmpty()) {
            val line = saved.itemList.maxBy { it.id ?: 0L }
            b2bValues.saveFieldValues(B2bFieldOwnerType.INVOICE_ITEM, line.id!!, identifiers)
        }
    }

    private fun taxRateFor(item: QuoteItem, date: LocalDate): TaxRate? =
        taxRates.findValidAt(date)
            .filter { it.isActive && it.isSelectable && it.category == item.vatCategory.code && it.rate.value.compareTo(item.vatRate) == 0 }
            .sortedWith(compareByDescending<TaxRate> { it.country == Country.DE }.thenByDescending { it.isDefault })
            .firstOrNull()
}
