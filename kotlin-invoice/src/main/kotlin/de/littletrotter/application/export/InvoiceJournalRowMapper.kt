package de.littletrotter.application.export

import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry

/**
 * Pure mapping from [InvoiceAuditJournalEntry] (already fully typed by
 * [de.littletrotter.infrastructure.persistence.PostgresInvoiceAuditJournalRepository]) into a
 * [InvoiceJournalExportRow]. The only real transformation is [InvoiceJournalDetailFlattener]
 * flattening [InvoiceAuditJournalEntry.detail]'s raw JSONB text; every other field is copied
 * straight across — same "pure function, no database access, no i18n resolution" shape as
 * [InvoiceHeaderRowMapper]/[InvoicePositionRowMapper], deliberately unit-testable without a
 * Testcontainers Postgres.
 */
object InvoiceJournalRowMapper {

    fun fromEntry(entry: InvoiceAuditJournalEntry): InvoiceJournalExportRow = InvoiceJournalExportRow(
        invoiceId = entry.invoiceId,
        invoiceNumber = entry.invoiceNumber,
        seq = entry.seq,
        occurredAt = entry.occurredAt,
        event = entry.event,
        actorName = entry.actorName,
        actor = entry.actor,
        detail = InvoiceJournalDetailFlattener.flatten(entry.event, entry.detail),
        documentSha256 = entry.documentSha256,
        entryHash = entry.entryHash,
        prevHash = entry.prevHash,
        action = entry.action,
        businessTx = entry.businessTx,
    )
}
