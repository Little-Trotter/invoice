package de.littletrotter.ui.view.journal

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.shared.Tooltip
import de.littletrotter.application.dto.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.ui.view.journal.composer.testCtx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** [JournalActionGridColumns]' cell builders for the grouped, action-level grid. Same
 *  no-Karibu shape as [JournalGridColumnsTest]: every builder returns a plain, unattached
 *  [Component]. */
class JournalActionGridColumnsTest {

    private val trEchoKey: (String, List<Any>) -> String =
        { key, args -> if (args.isEmpty()) key else "$key$args" }

    private fun row(
        seq: Long = 1L,
        invoiceId: Long = 1L,
        invoiceNumber: String? = "RE-2026-0001",
        event: InvoiceAuditEvent = InvoiceAuditEvent.INVOICE_UPDATED,
        action: InvoiceAuditAction = InvoiceAuditAction.HEADER_CHANGED,
        detail: String = "{}",
    ) = InvoiceAuditJournalEntry(
        seq = seq, businessTx = 1L, invoiceId = invoiceId, invoiceNumber = invoiceNumber,
        actor = "oidc-sub-1", actorName = "alice",
        occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
        event = event, action = action, detail = detail,
        documentSha256 = null, prevHash = "prev-$seq", entryHash = "hash-$seq-0123456789abcdef",
    )

    private fun line(
        action: InvoiceAuditAction = InvoiceAuditAction.HEADER_CHANGED,
        actor: String = "oidc-sub-1",
        actorName: String? = "alice",
        rows: List<InvoiceAuditJournalEntry> = listOf(row(action = action)),
        invoiceIds: List<Long> = rows.map { it.invoiceId }.distinct(),
        invoiceNumbers: List<String?> = rows.distinctBy { it.invoiceId }.map { it.invoiceNumber },
    ) = InvoiceAuditActionLine(
        businessTx = 1L, action = action, actor = actor, actorName = actorName,
        occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
        invoiceIds = invoiceIds, invoiceNumbers = invoiceNumbers, rows = rows,
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun texts(component: Component): List<String> =
        flatten(component).filterIsInstance<HasText>().mapNotNull { it.text }.filter { it.isNotBlank() }

    // ---- occurredAtCell -----------------------------------------------------------------------

    @Test
    fun `occurredAtCell delegates to the shared JournalGridColumns date-time cell`() {
        val theLine = line()

        val cell = JournalActionGridColumns.occurredAtCell(theLine)

        assertTrue(cell.element.classList.contains("journal-occurred-at"))
    }

    // ---- invoiceNumbersCell with no invoices at all --------------------------------------------

    @Test
    fun `invoiceNumbersCell renders blank when the action carries no invoice at all`() {
        val theLine = InvoiceAuditActionLine(
            businessTx = 1L, action = InvoiceAuditAction.HEADER_CHANGED, actor = "system", actorName = null,
            occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
            invoiceIds = emptyList(), invoiceNumbers = emptyList(), rows = emptyList(),
        )

        val cell = JournalActionGridColumns.invoiceNumbersCell(theLine, trEchoKey) as HasText

        assertEquals("", cell.text)
    }

    // ---- actionTileCell / actionLabelCell / isExternalWrite -----------------------------------

    @Test
    fun `actionTileCell carries the plain action label as title for a non-external action`() {
        val theLine = line(action = InvoiceAuditAction.STATUS_CHANGED)

        val tile = JournalActionGridColumns.actionTileCell(theLine, trEchoKey) as Icon

        assertEquals("journal.action.STATUS_CHANGED", tile.element.getAttribute("title"))
    }

    @Test
    fun `actionTileCell and actionLabelCell use the external-write label for an UNSPECIFIED action with a non-system actor`() {
        val theLine = line(action = InvoiceAuditAction.UNSPECIFIED, actor = "lt_owner")

        val tile = JournalActionGridColumns.actionTileCell(theLine, trEchoKey) as Icon
        val label = JournalActionGridColumns.actionLabelCell(theLine, trEchoKey)

        assertEquals("journal.action.UNSPECIFIED.external", tile.element.getAttribute("title"))
        assertTrue(texts(label).contains("journal.action.UNSPECIFIED.external"))
    }

    @Test
    fun `an UNSPECIFIED action attributed to the system actor is NOT flagged as an external write`() {
        val theLine = line(action = InvoiceAuditAction.UNSPECIFIED, actor = "system")

        assertFalse(JournalActionGridColumns.isExternalWrite(theLine))
        val label = JournalActionGridColumns.actionLabelCell(theLine, trEchoKey)
        assertTrue(texts(label).contains("journal.action.UNSPECIFIED"))
        assertFalse(label.classNames.contains("journal-action-label--warning"))
    }

    @Test
    fun `a non-UNSPECIFIED action is never flagged as an external write, whoever the actor is`() {
        val theLine = line(action = InvoiceAuditAction.HEADER_CHANGED, actor = "lt_owner")

        assertFalse(JournalActionGridColumns.isExternalWrite(theLine))
    }

    @Test
    fun `actionLabelCell adds the warning modifier only for an external write`() {
        val warning = JournalActionGridColumns.actionLabelCell(line(action = InvoiceAuditAction.UNSPECIFIED, actor = "lt_owner"), trEchoKey)
        val plain = JournalActionGridColumns.actionLabelCell(line(action = InvoiceAuditAction.HEADER_CHANGED), trEchoKey)

        assertTrue(warning.classNames.containsAll(listOf("journal-action-label", "journal-action-label--warning")))
        assertTrue(plain.classNames.contains("journal-action-label"))
        assertFalse(plain.classNames.contains("journal-action-label--warning"))
    }

    // ---- actorText ------------------------------------------------------------------------------

    @Test
    fun `actorText prefers actorName, falls back to the raw actor id`() {
        assertEquals("alice", JournalActionGridColumns.actorText(line(actorName = "alice")))
        assertEquals("oidc-sub-1", JournalActionGridColumns.actorText(line(actorName = null)))
    }

    // ---- grossDeltaCell / formatSignedDelta ------------------------------------------------------

    @Test
    fun `grossDeltaCell is empty when the action never touched gross_amount`() {
        val theLine = line(rows = listOf(row(detail = """{"due_date": {"old": "2026-01-01", "new": "2026-02-01"}}""")))

        val cell = JournalActionGridColumns.grossDeltaCell(theLine)

        assertTrue((cell as HasText).text.isNullOrEmpty())
        assertTrue(cell.classNames.contains("journal-gross-delta"))
    }

    @Test
    fun `grossDeltaCell shows a leading plus for a positive delta`() {
        val theLine = line(rows = listOf(row(detail = """{"gross_amount": {"old": "100.00", "new": "142.80"}}""")))

        val cell = JournalActionGridColumns.grossDeltaCell(theLine) as HasText

        assertEquals("+42,80 EUR", cell.text)
    }

    @Test
    fun `grossDeltaCell shows a single leading minus for a negative delta, never a double sign`() {
        val theLine = line(rows = listOf(row(detail = """{"gross_amount": {"old": "142.80", "new": "100.00"}}""")))

        val cell = JournalActionGridColumns.grossDeltaCell(theLine) as HasText

        assertEquals("-42,80 EUR", cell.text)
    }

    // ---- invoiceNumbersCell -----------------------------------------------------------------------

    @Test
    fun `invoiceNumbersCell renders a single invoice's number`() {
        val theLine = line(rows = listOf(row(invoiceId = 1L, invoiceNumber = "RE-2026-0007")))

        val cell = JournalActionGridColumns.invoiceNumbersCell(theLine, trEchoKey) as HasText

        assertEquals("RE-2026-0007", cell.text)
    }

    @Test
    fun `invoiceNumbersCell joins a CANCELLATION's two invoices with an arrow`() {
        val theLine = line(
            action = InvoiceAuditAction.CANCELLATION,
            rows = listOf(
                row(seq = 1L, invoiceId = 1L, invoiceNumber = "RE-2026-0007", action = InvoiceAuditAction.CANCELLATION),
                row(seq = 2L, invoiceId = 2L, invoiceNumber = "RE-2026-0008", action = InvoiceAuditAction.CANCELLATION),
            ),
        )

        val cell = JournalActionGridColumns.invoiceNumbersCell(theLine, trEchoKey) as HasText

        assertEquals("RE-2026-0007 → RE-2026-0008", cell.text)
    }

    @Test
    fun `invoiceNumbersCell names an unnumbered draft instead of rendering blank`() {
        val theLine = line(rows = listOf(row(invoiceId = 1L, invoiceNumber = null)))

        val cell = JournalActionGridColumns.invoiceNumbersCell(theLine, trEchoKey) as HasText

        assertEquals("journal.invoice.unnumbered", cell.text)
    }

    // ---- expandToggleButton -----------------------------------------------------------------------

    @Test
    fun `expandToggleButton shows the collapse chevron and label when already expanded, and toggles on click`() {
        val theLine = line()
        val toggled = mutableListOf<InvoiceAuditActionLine>()

        val button = JournalActionGridColumns.expandToggleButton(theLine, expanded = true, tr = trEchoKey) { toggled += it }
        button.click()

        assertEquals("journal.action.collapse", button.element.getAttribute("aria-label"))
        assertEquals("journal.action.collapse", Tooltip.forComponent(button).text)
        assertEquals(listOf(theLine), toggled)
    }

    @Test
    fun `expandToggleButton shows the expand chevron and label when collapsed`() {
        val button = JournalActionGridColumns.expandToggleButton(line(), expanded = false, tr = trEchoKey) {}

        assertEquals("journal.action.expand", button.element.getAttribute("aria-label"))
    }

    // ---- whatCell -------------------------------------------------------------------------------

    @Test
    fun `whatCell renders JournalActionContent's composed sentence`() {
        val theLine = line(
            action = InvoiceAuditAction.RECIPIENT_CHANGED,
            rows = listOf(row(action = InvoiceAuditAction.RECIPIENT_CHANGED, detail = "{}")),
        )

        val cell = JournalActionGridColumns.whatCell(theLine, testCtx()) as HasText

        assertEquals(JournalActionContent.whatHappened(theLine, testCtx()), cell.text)
        assertTrue((cell as Component).element.classList.contains("journal-what"))
    }

    // ---- technicalRowsDetails ---------------------------------------------------------------------

    @Test
    fun `technicalRowsDetails renders one row per technical entry, ordered by seq`() {
        val opened = mutableListOf<InvoiceAuditJournalEntry>()
        val first = row(seq = 2L)
        val second = row(seq = 1L)

        val details = JournalActionGridColumns.technicalRowsDetails(
            line(rows = listOf(first, second)),
            trEchoKey,
            { null },
        ) { opened += it }

        val rows = details.children.toList()
        assertEquals(2, rows.size)
        assertTrue(texts(rows[0]).contains("1"), "first rendered row must be seq 1")
        assertTrue(texts(rows[1]).contains("2"), "second rendered row must be seq 2")
    }

    @Test
    fun `technicalRowsDetails renders no rows for an action with none, without throwing`() {
        val details = JournalActionGridColumns.technicalRowsDetails(line(rows = emptyList()), trEchoKey, { null }) {}

        assertTrue(details.children.toList().isEmpty())
    }
}
