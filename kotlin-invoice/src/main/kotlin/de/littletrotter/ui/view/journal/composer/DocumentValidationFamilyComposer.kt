package de.littletrotter.ui.view.journal.composer

import de.littletrotter.application.invoice.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.ui.view.invoice.validationStatusLabel

/**
 * The "documents/validation" family:
 * [DOCUMENT_GENERATED][de.littletrotter.domain.compliance.InvoiceAuditAction.DOCUMENT_GENERATED]
 * and [VALIDATION_RECORDED][de.littletrotter.domain.compliance.InvoiceAuditAction
 * .VALIDATION_RECORDED].
 *
 * A generated document's own row ([InvoiceAuditEvent.INVOICE_DOCUMENT_CREATED]) carries
 * `document_type` and `sha256_checksum` — the sentence names both, the hash shortened to the same
 * length JournalEntryDialog's own hash preview uses. A validation verdict may land on either the
 * invoice header's own `validation_status` (`INVOICE_UPDATED`) or the generated document's own
 * (`INVOICE_DOCUMENT_UPDATED`) — JournalValueFormatter deliberately never decodes the latter (a
 * same-named column on a sibling table is not assumed to share the header's enum, see that
 * object's own pinned test), so THIS composer decodes `ValidationStatus` itself: unlike the
 * general-purpose row dialog, it already knows from the row's own event which table the
 * column belongs to, and `invoice_document.validation_status` uses the identical enum (see that
 * column's own baseline comment: "same truthful-default treatment as invoice.validation_status").
 */
object DocumentValidationFamilyComposer : JournalWhatComposer {

    private const val DOCUMENT_TYPE_COLUMN = "document_type"
    private const val SHA256_COLUMN = "sha256_checksum"
    private const val VALIDATION_STATUS_COLUMN = "validation_status"
    private const val HASH_PREVIEW = 12

    override fun compose(line: InvoiceAuditActionLine, ctx: JournalComposerContext): String? =
        composeDocumentGenerated(line, ctx) ?: composeValidationRecorded(line, ctx)

    private fun composeDocumentGenerated(line: InvoiceAuditActionLine, ctx: JournalComposerContext): String? {
        line.rows.firstOrNull { it.event == InvoiceAuditEvent.INVOICE_DOCUMENT_CREATED } ?: return null
        val documentType = JournalComposerSupport.firstSnapshot(line.rows, DOCUMENT_TYPE_COLUMN).orEmpty()
        val hash = JournalComposerSupport.firstSnapshot(line.rows, SHA256_COLUMN)?.take(HASH_PREVIEW)
        return ctx.tr("journal.what.documentGenerated", listOf(documentType, hash.orEmpty()))
    }

    private fun composeValidationRecorded(line: InvoiceAuditActionLine, ctx: JournalComposerContext): String? {
        val hasValidationRow = line.rows.any {
            it.event == InvoiceAuditEvent.INVOICE_UPDATED || it.event == InvoiceAuditEvent.INVOICE_DOCUMENT_UPDATED
        }
        if (!hasValidationRow) return null
        val (old, new) = JournalComposerSupport.firstDiff(line.rows, VALIDATION_STATUS_COLUMN) ?: return null
        val oldLabel = validationStatusLabel(old, ctx.trOne) ?: old.orEmpty()
        val newLabel = validationStatusLabel(new, ctx.trOne) ?: new.orEmpty()
        return ctx.tr("journal.what.validationRecorded", listOf(oldLabel, newLabel))
    }

    /** Decodes the raw ordinal itself: the composer knows from the row's event which table the
     *  column belongs to, so the column-name ambiguity a generic formatter has does not apply. */
    private fun validationStatusLabel(raw: String?, tr: (String) -> String): String? {
        val status = raw?.toIntOrNull()?.let { ValidationStatus.entries.getOrNull(it) } ?: return null
        return validationStatusLabel(status, tr)
    }
}
