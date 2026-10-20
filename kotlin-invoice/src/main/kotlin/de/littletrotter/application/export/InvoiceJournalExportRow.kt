package de.littletrotter.application.export

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** [OffsetDateTime.MIN] would overflow POI's Excel-serial-date conversion (its epoch-day range is
 *  nowhere near astronomical year -999999999), so [InvoiceJournalExportRow.occurredAt]'s default is
 *  an arbitrary, ordinary date instead — never business-meaningful, just safe to write. */
private val PLACEHOLDER_OCCURRED_AT: OffsetDateTime = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

/**
 * One fully-mapped row of the `Journal` sheet, one per [de.littletrotter.domain.compliance
 * .InvoiceAuditJournalEntry] of the exported invoice. Mirrors [InvoiceHeaderExportRow]/
 * [InvoicePositionExportRow]'s own shape: the event stays a real [InvoiceAuditEvent] (resolved to
 * an i18n label only by [de.littletrotter.infrastructure.export.workbook.InvoiceJournalSheetWriter],
 * never printed as an ordinal), and every field but [invoiceId] carries a default so the
 * constructor stays within this module's `LongParameterList` gate the same way the sibling rows do.
 *
 * [detail] already carries the flattened, human-readable text
 * [InvoiceJournalRowMapper]/[InvoiceJournalDetailFlattener] produce from the entry's raw JSONB —
 * this row never carries raw JSON. The 32 767-characters-per-cell truncation itself is not this
 * row's concern: every text cell gets it from [de.littletrotter.infrastructure.export.workbook
 * .TextGuard.sanitize] when the writer writes it, exactly like every other text column on every
 * other sheet.
 */
data class InvoiceJournalExportRow(
    val invoiceId: Long,
    /** The exported invoice's own number — blank for a draft, same convention as
     *  [InvoicePositionExportRow.invoiceNumber]'s sibling field. */
    val invoiceNumber: String? = null,
    /** The chain order (`invoice_audit_journal.seq`) — the only honest ordering for this sheet. */
    val seq: Long = 0L,
    val occurredAt: OffsetDateTime = PLACEHOLDER_OCCURRED_AT,
    val event: InvoiceAuditEvent = InvoiceAuditEvent.INVOICE_CREATED,
    /** The human-readable actor name, when the trigger captured one; `null` for a row written
     *  before that GUC was set, or for a direct-SQL write. The writer falls back to [actor]. */
    val actorName: String? = null,
    /** The OIDC `sub` (or the connecting DB role, for a write that bypassed the application) —
     *  `invoice_audit_journal.actor` is `NOT NULL`, so this is never blank in practice. */
    val actor: String = "",
    val detail: String = "",
    /** `null` for every event but the three `INVOICE_DOCUMENT_*` ones. */
    val documentSha256: String? = null,
    val entryHash: String = "",
    val prevHash: String = "",
    /** The business action this row's transaction was performing, resolved to a label by
     *  the writer through [de.littletrotter.infrastructure.export.workbook.InvoiceJournalSheetLabels
     *  .actionLabels] — never printed as a raw ordinal, same convention as [event]. */
    val action: InvoiceAuditAction = InvoiceAuditAction.UNSPECIFIED,
    /** `invoice_audit_journal.business_tx`: rows of one business action share this value,
     *  so an auditor working in Excel can group/filter the flat row list back into one action. */
    val businessTx: Long = 0L,
)
