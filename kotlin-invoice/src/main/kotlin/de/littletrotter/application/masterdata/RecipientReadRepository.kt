package de.littletrotter.application.masterdata

import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.domain.shared.SortDirection

/**
 * Deliberately placed in `application`, not `domain.repository`: this is the CQRS read side, same
 * reasoning as [de.littletrotter.application.invoice.InvoiceReadRepository]. Its implementation
 * projects the billing/delivery address fields directly instead of loading [RecipientQueries]'
 * lazy [de.littletrotter.domain.masterdata.Recipient.addresses] collection per row.
 */
interface RecipientReadRepository {
    fun findRecipients(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String = "name",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<RecipientSummaryDto>

    fun findRecipientById(id: Long): RecipientSummaryDto?
}
