package de.littletrotter.infrastructure.persistence

import de.littletrotter.shared.Money
import jakarta.persistence.Tuple

/** The "totals" cohesive group: the header's own net/vat/gross amounts and currency. */
internal data class InvoiceDetailTotalsFields(
    val netAmount: Money?,
    val vatAmount: Money?,
    val grossAmount: Money?,
    // Task O: ISO 4217 currency ordinal; null/out-of-range reads as EUR by readers.
    val currency: Int?,
)

internal object InvoiceDetailTotalsFieldMapper {

    fun map(header: Tuple): InvoiceDetailTotalsFields = InvoiceDetailTotalsFields(
        netAmount = InvoiceDetailRowSupport.parseMoney(header.get("netAmount")),
        vatAmount = InvoiceDetailRowSupport.parseMoney(header.get("vatAmount")),
        grossAmount = InvoiceDetailRowSupport.parseMoney(header.get("grossAmount")),
        currency = InvoiceDetailRowSupport.parseCurrencyOrdinal(header.get("currency")),
    )
}
