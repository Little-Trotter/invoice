package de.littletrotter.ui.view.journal

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.button.Button
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * [JournalEntryDialog]: the payload a grid row can only summarise, shown
 * in full. Pins the three things the rework exists for — a real old→new diff, a wide INSERT
 * snapshot that opens filtered down to what it actually carries, and a read-only surface whose
 * only button closes it.
 */
class JournalEntryDialogTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private val trEchoKey: (String, List<Any>) -> String =
        { key, args -> if (args.isEmpty()) key else "$key$args" }

    private fun dialog() = JournalEntryDialog(trEchoKey) { null }

    private fun entry(event: InvoiceAuditEvent, detail: String) = InvoiceAuditJournalEntry(
        seq = 4711L, businessTx = 4711L, invoiceId = 1L, invoiceNumber = "RE-2026-0001",
        actor = "oidc-sub-1", actorName = "alice",
        occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
        event = event, action = InvoiceAuditAction.UNSPECIFIED, detail = detail,
        documentSha256 = null, prevHash = "prev",
        entryHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun dialogParts(dlg: JournalEntryDialog): List<Component> =
        flatten(dlg.dialog) + flatten(dlg.headerContent) + flatten(dlg.footerContent)

    private fun dialogTexts(dlg: JournalEntryDialog): List<String> =
        dialogParts(dlg).filterIsInstance<HasText>().mapNotNull { it.text }.filter { it.isNotBlank() }

    @Test
    fun `an UPDATE opens as an old-new diff carrying both sides of every changed field`() {
        val dlg = dialog()

        dlg.open(entry(InvoiceAuditEvent.INVOICE_UPDATED, """{"free_text": {"old": "vorher", "new": "nachher"}}"""))

        assertTrue(dlg.dialog.isOpened)
        val line = dlg.visibleLines().single()
        assertEquals("vorher", line.oldValue)
        assertEquals("nachher", line.newValue)
        assertTrue(line.isChange)
    }

    @Test
    fun `a wide INSERT snapshot opens showing only the columns that carry something`() {
        val dlg = dialog()

        dlg.open(
            entry(
                InvoiceAuditEvent.INVOICE_CREATED,
                """{"invoice_number": "RE-2026-0001", "free_text": null, "cost_center": null}""",
            ),
        )

        assertEquals(listOf("invoice_number"), dlg.visibleLines().map { it.field })
        assertTrue(dlg.showEmptyFields.isVisible, "the hidden columns are offered, not silently dropped")
    }

    @Test
    fun `ticking show-empty-fields brings the empty columns back`() {
        val dlg = dialog()
        dlg.open(entry(InvoiceAuditEvent.INVOICE_CREATED, """{"invoice_number": "RE-1", "free_text": null}"""))

        dlg.showEmptyFields.value = true

        assertEquals(listOf("invoice_number", "free_text"), dlg.visibleLines().map { it.field })
    }

    @Test
    fun `the field search narrows to the matching field by label or by column name`() {
        val dlg = dialog()
        dlg.open(
            entry(
                InvoiceAuditEvent.INVOICE_UPDATED,
                """{"free_text": {"old": "a", "new": "b"}, "cost_center": {"old": null, "new": "K1"}}""",
            ),
        )

        dlg.fieldSearch.value = "cost"

        assertEquals(listOf("cost_center"), dlg.visibleLines().map { it.field })
    }

    @Test
    fun `re-opening resets the toggle and the search left over from the previous entry`() {
        val dlg = dialog()
        dlg.open(entry(InvoiceAuditEvent.INVOICE_CREATED, """{"invoice_number": "RE-1", "free_text": null}"""))
        dlg.showEmptyFields.value = true
        dlg.fieldSearch.value = "free"

        dlg.open(entry(InvoiceAuditEvent.INVOICE_UPDATED, """{"cost_center": {"old": null, "new": "K1"}}"""))

        assertFalse(dlg.showEmptyFields.value)
        assertEquals("", dlg.fieldSearch.value)
        assertEquals(listOf("cost_center"), dlg.visibleLines().map { it.field })
    }

    @Test
    fun `the header names the event and the invoice, and the footer carries the sealing hash`() {
        val dlg = dialog()

        dlg.open(entry(InvoiceAuditEvent.INVOICE_UPDATED, """{"free_text": {"old": "a", "new": "b"}}"""))

        val texts = dialogTexts(dlg)
        assertTrue(texts.contains("journal.event.INVOICE_UPDATED"), "expected the event label, got: $texts")
        assertTrue(texts.contains("RE-2026-0001"))
        assertTrue(texts.any { it.startsWith("0123456789ab") }, "expected the truncated entry hash, got: $texts")
        assertTrue(texts.contains("4711"), "expected the journal sequence number, got: $texts")
    }

    @Test
    fun `an entry with no payload says so instead of rendering an empty panel`() {
        val dlg = dialog()

        dlg.open(entry(InvoiceAuditEvent.INVOICE_UPDATED, "{}"))

        assertTrue(dialogTexts(dlg).contains("journal.detail.empty"))
    }

    @Test
    fun `the only button in the dialog closes it — the journal is append-only`() {
        val dlg = dialog()
        dlg.open(entry(InvoiceAuditEvent.INVOICE_DELETED, """{"invoice_number": "RE-1"}"""))

        val buttons = dialogParts(dlg).filterIsInstance<Button>()

        assertEquals(listOf("app.close"), buttons.map { it.text })
        buttons.single().click()
        assertFalse(dlg.dialog.isOpened)
    }

    @Test
    fun `the field search stays hidden for a payload at exactly the no-search threshold`() {
        val dlg = dialog()
        val columns = (1..SEARCH_THRESHOLD_FIELD_COUNT).joinToString(",") { "\"f$it\": \"v$it\"" }

        dlg.open(entry(InvoiceAuditEvent.INVOICE_CREATED, "{$columns}"))

        assertFalse(dlg.fieldSearch.isVisible, "twelve fields still fit on one screen")
    }

    @Test
    fun `the field search appears once a payload carries one field more than the threshold`() {
        val dlg = dialog()
        val columns = (1..SEARCH_THRESHOLD_FIELD_COUNT + 1).joinToString(",") { "\"f$it\": \"v$it\"" }

        dlg.open(entry(InvoiceAuditEvent.INVOICE_CREATED, "{$columns}"))

        assertTrue(dlg.fieldSearch.isVisible, "thirteen fields is past the one-screen threshold")
    }

    @Test
    fun `the show-empty-fields toggle stays hidden when an INSERT snapshot has no empty column`() {
        val dlg = dialog()

        dlg.open(entry(InvoiceAuditEvent.INVOICE_CREATED, """{"invoice_number": "RE-1", "free_text": "note"}"""))

        assertFalse(dlg.showEmptyFields.isVisible, "nothing is hidden, so the toggle would have nothing to reveal")
    }

    private companion object {
        const val SEARCH_THRESHOLD_FIELD_COUNT = 12
    }
}
