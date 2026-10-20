package de.littletrotter.ui.view.journal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import de.littletrotter.domain.compliance.InvoiceAuditEvent

/** One changed field of an UPDATE event — `journal.detail.field.old`/`.new` render around these
 *  in the UI, see [JournalDetailRendering.Diff]. */
data class JournalFieldChange(val field: String, val oldValue: String?, val newValue: String?)

/** One field of an INSERT/DELETE row snapshot — see [JournalDetailRendering.Snapshot]. */
data class JournalFieldValue(val field: String, val value: String?)

/** The row-identity metadata an UPDATE's reserved `_row` key carries (see the
 *  `invoice_audit_journal.detail` column comment, `V1__baseline_schema.sql` section 13): the row's
 *  own primary key plus, where the table has one, a human-meaningful business key an auditor needs
 *  but the diff itself would omit whenever that column did not change (an item's position number,
 *  a trip line's owning item). `null` when a table names no such key. */
data class JournalRowIdentity(val id: String?, val positionId: String?)

/**
 * The two shapes `invoice_audit_journal.detail` can take, per the column's own
 * comment in `V1__baseline_schema.sql` (section 13): an UPDATE stores `{"column": {"old":..,
 * "new":..}}` pairs for only the columns that actually changed, plus the reserved `_row` metadata
 * key ([JournalRowIdentity], never itself a changed-column entry in [Diff.changes]); an
 * INSERT/DELETE stores the affected row's own columns as a flat snapshot (its id/position number
 * already among them, no `_row` needed). [JournalDetailRenderer.render] tells the two apart from
 * the triggering [InvoiceAuditEvent] itself (never by guessing at the JSON shape), matching how the
 * SQL trigger already decides which shape to write.
 */
sealed class JournalDetailRendering {
    data class Diff(val changes: List<JournalFieldChange>, val rowIdentity: JournalRowIdentity? = null) : JournalDetailRendering()
    data class Snapshot(val fields: List<JournalFieldValue>) : JournalDetailRendering()
}

/**
 * Pure, Vaadin-free rendering of [de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
 * .detail] — the raw JSONB text the repository hands back unparsed (see that class's own KDoc on
 * why). Both journal views (per-invoice tab, global system view) call this instead of parsing
 * JSON themselves.
 */
object JournalDetailRenderer {

    private val mapper = ObjectMapper()

    /** The reserved metadata key an UPDATE's `_row` identity lives under; see
     *  [JournalRowIdentity]'s own KDoc. Any key sharing this `_` prefix is metadata, never a
     *  changed field — the same rule every `detail` consumer follows. */
    private const val ROW_IDENTITY_FIELD = "_row"
    private const val METADATA_PREFIX = "_"
    private const val ID_KEY = "id"
    private const val POSITION_ID_KEY = "position_id"

    /** [event] decides the shape (see [JournalDetailRendering]'s own KDoc); [detailJson] is
     *  parsed defensively — a malformed or empty payload renders as an empty rendering rather than
     *  throwing, since a display bug in one row must never break the rest of the journal view. */
    fun render(event: InvoiceAuditEvent, detailJson: String): JournalDetailRendering {
        val root = runCatching { mapper.readTree(detailJson) }.getOrNull()
        if (root == null || root.properties().isEmpty()) {
            return emptyRenderingFor(event)
        }
        val entries = root.properties().toList()
        return if (isUpdateEvent(event)) {
            JournalDetailRendering.Diff(
                entries.filterNot { (field, _) -> field.startsWith(METADATA_PREFIX) }
                    .map { (field, node) -> JournalFieldChange(field, displayString(node.get("old")), displayString(node.get("new"))) },
                rowIdentity = entries.firstOrNull { (field, _) -> field == ROW_IDENTITY_FIELD }?.let { (_, node) -> rowIdentity(node) },
            )
        } else {
            JournalDetailRendering.Snapshot(
                entries.filterNot { (field, _) -> field.startsWith(METADATA_PREFIX) }
                    .map { (field, node) -> JournalFieldValue(field, displayString(node)) },
            )
        }
    }

    private fun rowIdentity(node: JsonNode): JournalRowIdentity =
        JournalRowIdentity(id = displayString(node.get(ID_KEY)), positionId = displayString(node.get(POSITION_ID_KEY)))

    private fun emptyRenderingFor(event: InvoiceAuditEvent): JournalDetailRendering =
        if (isUpdateEvent(event)) JournalDetailRendering.Diff(emptyList()) else JournalDetailRendering.Snapshot(emptyList())

    private fun isUpdateEvent(event: InvoiceAuditEvent): Boolean = event.name.endsWith("_UPDATED")

    /** `null`/missing renders as `null` (the caller shows a placeholder, e.g. "—"); a JSON string
     *  renders unquoted; every other node type (number, boolean, object, array) falls back to its
     *  raw JSON text — good enough for display, and never throws on a shape this domain doesn't
     *  otherwise expect. */
    private fun displayString(node: JsonNode?): String? = when {
        node == null || node is NullNode -> null
        node.isTextual -> node.asText()
        else -> node.toString()
    }
}
