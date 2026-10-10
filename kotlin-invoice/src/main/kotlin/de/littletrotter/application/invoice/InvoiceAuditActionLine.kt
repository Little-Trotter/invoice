package de.littletrotter.application.invoice

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.domain.repository.InvoiceAuditActionGroup
import java.time.OffsetDateTime

/**
 * One auditor-facing journal line: an [InvoiceAuditActionGroup] (the business_tx's own
 * aggregate columns) plus the [rows] it actually wrote, ascending by seq. [AuditJournalQueryService]
 * is the only place that assembles this — one grouped query for a page of actions, one further
 * query for every one of those actions' technical rows ([rows] is never fetched lazily/per-line).
 *
 * Read by [de.littletrotter.ui.view.journal.JournalActionContent] to build the "Was genau" sentence
 * and by [de.littletrotter.ui.view.journal.JournalGrossDelta] for the "Δ Brutto" column; the grid's
 * item-details expansion renders [rows] directly through the existing
 * [de.littletrotter.ui.view.journal.JournalGridColumns] cell builders.
 */
data class InvoiceAuditActionLine(
    val businessTx: Long,
    val action: InvoiceAuditAction,
    val actor: String,
    val actorName: String?,
    val occurredAt: OffsetDateTime,
    val invoiceIds: List<Long>,
    val invoiceNumbers: List<String?>,
    val rows: List<InvoiceAuditJournalEntry>,
) {
    companion object {
        /** [invoiceIds]/[invoiceNumbers] are derived from [rows] themselves (each already carries
         *  its own correctly paired `invoiceId`/`invoiceNumber`) rather than from a second,
         *  independently aggregated column on [group] — see [InvoiceAuditActionGroup]'s own KDoc
         *  for why. `distinctBy { it.invoiceId }` (not a bare `distinct()` on the number) keeps
         *  exactly one entry per invoice even for the rare row whose `invoice_number` differs from
         *  another row of the same invoice within one business_tx (a number assigned mid-transaction). */
        fun of(group: InvoiceAuditActionGroup, rows: List<InvoiceAuditJournalEntry>): InvoiceAuditActionLine =
            InvoiceAuditActionLine(
                businessTx = group.businessTx,
                action = group.action,
                actor = group.actor,
                actorName = group.actorName,
                occurredAt = group.occurredAt,
                invoiceIds = rows.map { it.invoiceId }.distinct(),
                invoiceNumbers = rows.distinctBy { it.invoiceId }.map { it.invoiceNumber },
                rows = rows,
            )
    }
}
