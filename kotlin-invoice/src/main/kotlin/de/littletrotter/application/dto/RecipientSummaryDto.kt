package de.littletrotter.application.dto

import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.tax.VatIdVerificationStatus

/**
 * Read model for selecting/displaying a recipient: the filter combos
 * ([de.littletrotter.ui.component.LazyComboSupport], [de.littletrotter.ui.view.invoice
 * .InvoiceListFilterBar], [de.littletrotter.ui.view.invoice.PrintJobListView]), the "Neue
 * Rechnung" dialog ([de.littletrotter.ui.view.invoice.InvoiceCreateDialog]), the recipient grid
 * ([de.littletrotter.ui.view.masterdata.RecipientGridComposer]/[de.littletrotter.ui.view
 * .masterdata.RecipientView]) and the article/recipient reference search
 * ([de.littletrotter.application.masterdata.ArticleRecipientRefsModule]). Value objects
 * ([de.littletrotter.domain.value.CustomerNumber], [de.littletrotter.domain.value.VatId]) are
 * unwrapped to `String`, countries to their ISO-Alpha-2 `String` (same precedent as [AddressDto]).
 *
 * Produced by [de.littletrotter.application.masterdata.port.outbound.RecipientReadRepository] — a read-model
 * projection, never the `Recipient` entity's lazy address collections.
 */
data class RecipientSummaryDto(
    val id: Long,
    val name: String,
    val customerNumber: String?,
    val shortHash: String?,
    val contactPerson: String?,
    val routingId: String?,
    val vatId: String?,
    val customerType: CustomerType?,
    val vatIdVerificationStatus: VatIdVerificationStatus?,
    val reverseChargePreference: ReverseChargePreference,
    val billingCity: String?,
    val billingCountryAlpha2: String?,
    val deliveryCountryAlpha2: String?,
)
