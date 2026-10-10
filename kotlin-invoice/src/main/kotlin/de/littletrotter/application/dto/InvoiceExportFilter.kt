package de.littletrotter.application.dto

import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import java.time.LocalDate

/**
 * The exact filter set the invoice list already uses (`InvoiceOverviewQueryService`'s
 * overload: `filterText, status, reverseCharge, validationStatus, recipientId,
 * senderProfileId, dateFrom, dateTo`), so the export and the grid can never disagree about what
 * "the current selection" means. `null` on any field means "no filter on this dimension".
 */
data class InvoiceExportFilter(
    val filterText: String? = null,
    val status: InvoiceStatus? = null,
    val reverseCharge: Boolean? = null,
    val validationStatus: ValidationStatus? = null,
    val recipientId: Long? = null,
    val senderProfileId: Long? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
)
