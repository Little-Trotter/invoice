package de.littletrotter.application.masterdata.port.inbound

import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.RecipientDetailDto
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.domain.shared.SortDirection

/**
 * Input port of the "look up a recipient" use case: the recipient reads the invoice list
 * (filter combo, stale-row lookup), the print-job list, the recipient grid and the invoice detail
 * components need, and nothing else. Writes go through [ManageRecipientUseCase].
 *
 * Also carries the billing/delivery address lookups and the customer-number proposal (peek only,
 * never reserves) for `InvoiceCockpitHero`, `InvoiceOverviewPanel`, `InvoiceEntryPartyCard`,
 * `InvoicePrintControls`, the `TripDialog` chain, `RecipientView` and `ArticleRecipientRefsModule`.
 *
 * [findRecipientById]/[findRecipients] return [RecipientSummaryDto], a read-model projection
 * ([de.littletrotter.application.masterdata.RecipientReadRepository]) — selecting/displaying a
 * recipient never needs to load the entity or its lazy address collections.
 */
interface RecipientQueryPort {
    fun findRecipientById(id: Long): RecipientSummaryDto?

    // Paged & filtered accessor for UI DataProviders.
    fun findRecipients(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String = "name",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<RecipientSummaryDto>

    fun countRecipients(filterText: String? = null): Long

    fun findBillingAddress(recipientId: Long): AddressDto?

    fun findDeliveryAddress(recipientId: Long): AddressDto?

    fun proposeCustomerNumber(name: String?): String?

    /** The "Empfänger bearbeiten" dialog's whole read model in one call
     *  (see [RecipientDetailDto]): entity fields, the buyer's SPI extension pairs and both
     *  addresses, so [de.littletrotter.ui.view.masterdata.RecipientView] no longer reads
     *  `Recipient` properties off this port directly. `null` when no such recipient exists. */
    fun findRecipientDetail(id: Long): RecipientDetailDto?
}
