package de.littletrotter.application.dto

import de.littletrotter.domain.compliance.InvoiceAuditAction
import java.time.LocalDate
import de.littletrotter.application.invoice.AuditJournalQueryService

/**
 * The global journal view's filter set (invoice number, actor, business action,
 * date range), as the UI collects it: free text (possibly blank/untrimmed, straight out of a
 * `TextField`) and plain [LocalDate]s (straight out of a `DatePicker`, no time component).
 * [AuditJournalQueryService] is the one place that turns this into
 * [de.littletrotter.domain.repository.InvoiceAuditJournalRepository]'s `OffsetDateTime`/
 * exact-match parameters — see [AuditJournalQueryService.findGlobalActions]'s own KDoc.
 *
 * [actions] replaces the single [de.littletrotter.domain.compliance
 * .InvoiceAuditEvent] filter: an auditor filters by the business action ("Statuswechsel",
 * "Position hinzugefügt"), the row-level event is an implementation detail the grid no longer
 * even lists on its own (PO decision 2, 2026-09-17 — the row-event filter is removed, not hidden).
 * Empty = no filter, the same "no restriction" meaning every other optional field here already
 * has.
 */
data class JournalGlobalFilter(
    val invoiceNumber: String? = null,
    val actor: String? = null,
    val actions: Set<InvoiceAuditAction> = emptySet(),
    val occurredFrom: LocalDate? = null,
    val occurredTo: LocalDate? = null,
) {
    companion object {
        val EMPTY = JournalGlobalFilter()
    }
}
