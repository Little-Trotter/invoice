package de.littletrotter.ui.view.journal

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.button.Button
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** [JournalGridColumns]' shared cell builders: the event badge never shows a raw ordinal,
 *  the summary cell counts and names what changed instead of dumping the payload, and the only
 *  affordance any of them produces is the read-only detail button. Runs without Karibu/MockVaadin:
 *  every builder here returns a plain, unattached [Component], which Vaadin allows constructing and
 *  inspecting outside a UI. */
class JournalGridColumnsTest {

    // Same (key, args) -> text stand-in every other extracted-cell-builder test in this codebase
    // uses instead of a real component-aware tr(). Args are appended so a test can assert a count
    // reached the message without depending on the German wording.
    private val trEchoKey: (String, List<Any>) -> String =
        { key, args -> if (args.isEmpty()) key else "$key$args" }

    // No `journal.field.*` key resolves here, so every label falls back to
    // JournalFieldLabels.humanize — which is exactly what makes these assertions wording-free.
    private val trNone: (String) -> String? = { null }

    private fun entry(event: InvoiceAuditEvent, detail: String = "{}", actorName: String? = "alice", invoiceNumber: String? = "RE-2026-0001") =
        InvoiceAuditJournalEntry(
            seq = 1L, businessTx = 1L, invoiceId = 1L, invoiceNumber = invoiceNumber,
            actor = "oidc-sub-1", actorName = actorName,
            occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
            event = event, action = InvoiceAuditAction.UNSPECIFIED, detail = detail,
            documentSha256 = null, prevHash = "prev", entryHash = "hash",
        )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun texts(component: Component): List<String> =
        flatten(component).filterIsInstance<HasText>().mapNotNull { it.text }.filter { it.isNotBlank() }

    @Test
    fun `occurredAtCell renders the shared journal date-time cell with its own tabular-nums class`() {
        val cell = JournalGridColumns.occurredAtCell(java.time.OffsetDateTime.parse("2026-09-04T10:00:00Z"))

        assertTrue(cell.element.classList.contains("journal-occurred-at"))
    }

    @Test
    fun `eventBadge shows the translated label key, never the raw ordinal`() {
        val badge = JournalGridColumns.eventBadge(entry(InvoiceAuditEvent.INVOICE_CREATED), trEchoKey)

        assertTrue(texts(badge).contains("journal.event.INVOICE_CREATED"))
        assertFalse(texts(badge).any { it.toIntOrNull() != null }, "must never render the raw ordinal")
    }

    @Test
    fun `eventBadge carries the status-chip and its verb-specific modifier class`() {
        val created = JournalGridColumns.eventBadge(entry(InvoiceAuditEvent.INVOICE_CREATED), trEchoKey)
        val updated = JournalGridColumns.eventBadge(entry(InvoiceAuditEvent.INVOICE_UPDATED), trEchoKey)
        val deleted = JournalGridColumns.eventBadge(entry(InvoiceAuditEvent.INVOICE_DELETED), trEchoKey)

        assertTrue(created.classNames.containsAll(listOf("status-chip", "status-chip--ok")))
        assertTrue(updated.classNames.containsAll(listOf("status-chip", "status-chip--warn")))
        assertTrue(deleted.classNames.containsAll(listOf("status-chip", "status-chip--storno")))
    }

    @Test
    fun `entityCell renders the affected object as a tinted tile carrying its own title`() {
        val tile = JournalGridColumns.entityCell(entry(InvoiceAuditEvent.INVOICE_ITEM_UPDATED), trEchoKey)

        assertTrue(tile.element.classList.containsAll(listOf("journal-tile", "journal-tile--warning")))
        assertEquals("journal.entity.ITEM", tile.element.getAttribute("title"))
    }

    @Test
    fun `actorText prefers actorName, falls back to the raw actor id`() {
        assertEquals("alice", JournalGridColumns.actorText(entry(InvoiceAuditEvent.INVOICE_CREATED, actorName = "alice")))
        assertEquals("oidc-sub-1", JournalGridColumns.actorText(entry(InvoiceAuditEvent.INVOICE_CREATED, actorName = null)))
    }

    @Test
    fun `invoiceCell names the invoice and the affected object, and says so when there is no number yet`() {
        val numbered = JournalGridColumns.invoiceCell(entry(InvoiceAuditEvent.INVOICE_DOCUMENT_CREATED), trEchoKey)
        assertTrue(texts(numbered).contains("RE-2026-0001"))
        assertTrue(texts(numbered).contains("journal.entity.DOCUMENT"))

        val draft = JournalGridColumns.invoiceCell(entry(InvoiceAuditEvent.INVOICE_CREATED, invoiceNumber = null), trEchoKey)
        assertTrue(texts(draft).contains("journal.invoice.unnumbered"), "an unnumbered draft says so, it does not render blank")
    }

    @Test
    fun `summaryCell spells out a single change instead of only counting it`() {
        val cell = JournalGridColumns.summaryCell(
            entry(InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"free_text": {"old": "vorher", "new": "nachher"}}"""),
            trEchoKey,
            trNone,
        )

        val texts = texts(cell)
        assertTrue(texts.any { it.contains("vorher") && it.contains("nachher") }, "expected the old→new pair, got: $texts")
        assertTrue(texts.any { it.startsWith("journal.summary.changed") && it.contains("1") })
    }

    @Test
    fun `summaryCell counts a multi-field change and names only the first few`() {
        val cell = JournalGridColumns.summaryCell(
            entry(
                InvoiceAuditEvent.INVOICE_UPDATED,
                detail = """
                    {"free_text": {"old": "a", "new": "b"},
                     "cost_center": {"old": null, "new": "K1"},
                     "project_number": {"old": null, "new": "P1"},
                     "due_date": {"old": null, "new": "2026-10-01"}}
                """.trimIndent(),
            ),
            trEchoKey,
            trNone,
        )

        val texts = texts(cell)
        assertTrue(texts.any { it.startsWith("journal.summary.changed") && it.contains("4") }, "expected a count of 4, got: $texts")
        assertTrue(texts.any { it.contains("Free text") && it.contains("journal.summary.more") }, "expected named fields plus an overflow marker, got: $texts")
        assertFalse(texts.any { it.contains("Due date") }, "the fourth field is counted, not named")
    }

    @Test
    fun `summaryCell counts only the populated columns of an INSERT snapshot`() {
        val cell = JournalGridColumns.summaryCell(
            entry(
                InvoiceAuditEvent.INVOICE_CREATED,
                detail = """{"invoice_number": "RE-2026-0001", "free_text": null, "cost_center": null}""",
            ),
            trEchoKey,
            trNone,
        )

        val texts = texts(cell)
        assertTrue(texts.any { it.startsWith("journal.summary.captured") && it.contains("1") }, "expected 1 of 3 columns counted, got: $texts")
    }

    @Test
    fun `summaryCell shows the empty-details placeholder for a payload with no fields`() {
        val cell = JournalGridColumns.summaryCell(entry(InvoiceAuditEvent.INVOICE_UPDATED, detail = "{}"), trEchoKey, trNone)

        assertTrue(texts(cell).contains("journal.detail.empty"))
    }

    @Test
    fun `the only button any cell builder produces is the read-only detail button`() {
        val builders = listOf(
            JournalGridColumns.eventBadge(entry(InvoiceAuditEvent.INVOICE_DELETED), trEchoKey),
            JournalGridColumns.entityCell(entry(InvoiceAuditEvent.INVOICE_DELETED), trEchoKey),
            JournalGridColumns.invoiceCell(entry(InvoiceAuditEvent.INVOICE_DELETED), trEchoKey),
            JournalGridColumns.summaryCell(entry(InvoiceAuditEvent.INVOICE_ITEM_DELETED, detail = """{"id": 7}"""), trEchoKey, trNone),
        )

        assertTrue(builders.flatMap { flatten(it) }.filterIsInstance<Button>().isEmpty())
    }

    @Test
    fun `detailButton opens the entry it belongs to and carries an accessible name`() {
        val target = entry(InvoiceAuditEvent.INVOICE_UPDATED)
        val opened = mutableListOf<InvoiceAuditJournalEntry>()

        val button = JournalGridColumns.detailButton(target, trEchoKey) { opened += it }
        button.click()

        assertEquals(listOf(target), opened)
        assertEquals("journal.action.details", button.element.getAttribute("aria-label"))
        assertTrue(button.text.isNullOrEmpty(), "icon-only by design — the aria-label carries the name")
    }
}
