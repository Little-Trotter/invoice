package de.littletrotter.ui.view.journal.composer

import de.littletrotter.application.invoice.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import java.time.OffsetDateTime

/** Shared fixture builders for every composer test — a plain `(key, args) -> "key:arg,arg"`
 *  translator that makes assertions readable without loading the real i18n bundle (that
 *  completeness is [de.littletrotter.ui.view.journal.JournalActionLabelsTest]'s own job), plus a
 *  `trOrNull` that always resolves (so [de.littletrotter.ui.view.journal.JournalFieldLabels]'s
 *  humanize fallback is what a composer test actually exercises for an uncurated column). */
internal fun testTr(key: String, args: List<Any>): String =
    if (args.isEmpty()) key else "$key(${args.joinToString(",")})"

internal fun testCtx(
    recipientNames: Map<Long, String> = emptyMap(),
    receiptFileNames: Map<Long, String> = emptyMap(),
    itemPositions: Map<Long, String> = emptyMap(),
    trOrNull: (String) -> String? = { null },
): JournalComposerContext = JournalComposerContext(
    tr = ::testTr,
    trOrNull = trOrNull,
    recipientNames = recipientNames,
    receiptFileNames = receiptFileNames,
    itemPositions = itemPositions,
)

internal fun testRow(
    seq: Long = 1L,
    businessTx: Long = 1L,
    invoiceId: Long = 1L,
    invoiceNumber: String? = "RE-2026-0001",
    event: InvoiceAuditEvent = InvoiceAuditEvent.INVOICE_UPDATED,
    action: InvoiceAuditAction = InvoiceAuditAction.HEADER_CHANGED,
    detail: String = "{}",
): InvoiceAuditJournalEntry = InvoiceAuditJournalEntry(
    seq = seq, businessTx = businessTx, invoiceId = invoiceId, invoiceNumber = invoiceNumber,
    actor = "oidc-sub-1", actorName = "alice",
    occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
    event = event, action = action, detail = detail,
    documentSha256 = null, prevHash = "prev-$seq", entryHash = "hash-$seq",
)

internal fun testLine(
    businessTx: Long = 1L,
    action: InvoiceAuditAction = InvoiceAuditAction.HEADER_CHANGED,
    actor: String = "oidc-sub-1",
    rows: List<InvoiceAuditJournalEntry> = listOf(testRow(businessTx = businessTx, action = action)),
): InvoiceAuditActionLine = InvoiceAuditActionLine(
    businessTx = businessTx, action = action, actor = actor, actorName = "alice",
    occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
    invoiceIds = rows.map { it.invoiceId }.distinct(),
    invoiceNumbers = rows.distinctBy { it.invoiceId }.map { it.invoiceNumber },
    rows = rows,
)
