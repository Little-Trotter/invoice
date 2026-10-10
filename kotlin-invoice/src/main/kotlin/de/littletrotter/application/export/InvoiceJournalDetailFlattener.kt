package de.littletrotter.application.export

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.shared.catalogue.InvoiceItemUnit

/**
 * Flattens one `invoice_audit_journal.detail` JSONB payload into the text the `Journal`
 * sheet's "Geändert" cell shows: one changed field per line, `field: old → new` for an `_UPDATED`
 * event (the trigger already stores only the columns that actually changed) or `field: value` for
 * an INSERT/DELETE row snapshot.
 *
 * This is the same two-shape split [de.littletrotter.ui.view.journal.JournalDetailRenderer] renders
 * in the UI, reimplemented here rather than imported: `infrastructure.export` and `ui.view.journal`
 * are sibling adapters in this module's hexagonal layering and must never depend on each other (see
 * `ArchitectureRulesTest`/`DddModelArchitectureTest`'s `onionArchitecture()` check), so the two stay
 * two small, independently-tested copies of the same rule instead of one import that would violate
 * it. The export is a German human-readable document, so invoice-item `unit` values are resolved
 * through the domain enum's German label (including legacy enum-name payloads); all other values
 * retain their stored representation. The raw detail JSON remains untouched in the journal and in
 * its chain hashes — only this derived display cell is formatted.
 *
 * Unlike the UI's renderer, a parse failure or an unexpected top-level shape (anything other than a
 * JSON object) never renders as empty here — the export reads history, it does not get to refuse a
 * row over one payload it cannot make sense of, so it falls back to the raw JSONB text unchanged.
 * The 32 767-characters-per-cell truncation is not this class's job: every text cell already gets it
 * from [de.littletrotter.infrastructure.export.workbook.TextGuard.sanitize] when
 * [de.littletrotter.infrastructure.export.workbook.RowWriter.text] writes it.
 */
object InvoiceJournalDetailFlattener {

    private val mapper = ObjectMapper()

    private const val EMPTY_VALUE_PLACEHOLDER = "-"
    private const val FIELD_SEPARATOR = ": "
    private const val CHANGE_ARROW = " → "
    private const val LINE_BREAK = "\n"
    private const val UNKNOWN_UNIT = "Unbekannte Einheit"

    // The reserved `_row` metadata key (V1__baseline_schema.sql section 13's own comment
    // on invoice_audit_journal.detail): never a changed column, so it is never rendered as one; its
    // position number (invoice_item's own business key) is worth a line of its own, since the
    // export reads history and an auditor working in Excel wants to know WHICH position changed
    // just as much as the UI does.
    private const val ROW_IDENTITY_FIELD = "_row"
    private const val POSITION_ID_KEY = "position_id"
    private const val POSITION_LABEL = "Position"

    private val INVOICE_ITEM_EVENTS = setOf(
        InvoiceAuditEvent.INVOICE_ITEM_CREATED,
        InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
        InvoiceAuditEvent.INVOICE_ITEM_DELETED,
    )

    /** [event] decides the shape (see this object's own KDoc); [detailJson] is parsed defensively —
     *  a parse failure or a top-level shape other than a JSON object falls back to [detailJson]
     *  itself, unchanged, rather than throwing or silently discarding the payload. */
    fun flatten(event: InvoiceAuditEvent, detailJson: String): String {
        val root = runCatching { mapper.readTree(detailJson) }.getOrNull()
        if (root == null || root.isMissingNode || !root.isObject) return detailJson
        if (root.properties().isEmpty()) return ""
        val isUpdate = event.name.endsWith("_UPDATED")
        return root.properties().asSequence().mapNotNull { (field, node) ->
            when {
                field == ROW_IDENTITY_FIELD -> rowIdentityLine(event, node)
                isUpdate -> diffLine(event, field, node)
                else -> snapshotLine(event, field, node)
            }
        }.joinToString(LINE_BREAK)
    }

    /** [ROW_IDENTITY_FIELD] is never a changed column (see this object's own KDoc) — its position
     *  number, when the table has one, becomes its own "Position: n" line; a table with no such key
     *  (e.g. plain `{"id": ..}`) contributes no line at all rather than an empty one. */
    private fun rowIdentityLine(event: InvoiceAuditEvent, node: JsonNode): String? {
        val position = displayString(event, POSITION_ID_KEY, node.get(POSITION_ID_KEY)) ?: return null
        return "$POSITION_LABEL$FIELD_SEPARATOR$position"
    }

    private fun diffLine(event: InvoiceAuditEvent, field: String, node: JsonNode): String {
        val old = displayString(event, field, node.get("old")) ?: EMPTY_VALUE_PLACEHOLDER
        val new = displayString(event, field, node.get("new")) ?: EMPTY_VALUE_PLACEHOLDER
        return "$field$FIELD_SEPARATOR$old$CHANGE_ARROW$new"
    }

    private fun snapshotLine(event: InvoiceAuditEvent, field: String, node: JsonNode): String {
        val value = displayString(event, field, node) ?: EMPTY_VALUE_PLACEHOLDER
        return "$field$FIELD_SEPARATOR$value"
    }

    /** `null`/missing renders as `null` (the caller's [EMPTY_VALUE_PLACEHOLDER] fills the gap); a
     *  JSON string renders unquoted; every other node type (number, boolean, object, array) falls
     *  back to its raw JSON text — same rule [de.littletrotter.ui.view.journal.JournalDetailRenderer
     *  .displayString] applies, reimplemented for the reason this object's own KDoc gives. The one
     *  display exception is an invoice-item `unit`, which is a German domain label in this export.
     */
    private fun displayString(event: InvoiceAuditEvent, field: String, node: JsonNode?): String? {
        if (event in INVOICE_ITEM_EVENTS && field == "unit") return invoiceItemUnitLabel(node)
        return when {
            node == null || node.isMissingNode || node is NullNode -> null
            node.isTextual -> node.asText()
            else -> node.toString()
        }
    }

    private fun invoiceItemUnitLabel(node: JsonNode?): String? {
        if (node == null || node.isMissingNode || node is NullNode) return null
        val value = if (node.isTextual) node.asText() else node.toString()
        val unit = value.toIntOrNull()?.let { InvoiceItemUnit.entries.getOrNull(it) }
            ?: InvoiceItemUnit.entries.firstOrNull { it.name == value }
        return unit?.germanLabel(plural = false) ?: UNKNOWN_UNIT
    }
}
