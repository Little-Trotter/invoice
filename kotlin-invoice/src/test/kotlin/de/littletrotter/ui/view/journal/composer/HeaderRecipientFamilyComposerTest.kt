package de.littletrotter.ui.view.journal.composer

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HeaderRecipientFamilyComposerTest {

    /** `updateHeader` journals `HEADER_CHANGED` even for a recipient change (only
     *  `refreshPartySnapshots` journals the dedicated `RECIPIENT_CHANGED`) — the sentence must come
     *  from the row's own `recipient_id` diff regardless of which of the two actions carries it. */
    @Test
    fun `a recipient_id diff on a HEADER_CHANGED row still renders as a recipient change`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"recipient_id": {"old": "5", "new": "8"}}""")
        val line = testLine(action = InvoiceAuditAction.HEADER_CHANGED, rows = listOf(row))

        val result = HeaderRecipientFamilyComposer.compose(line, testCtx(recipientNames = mapOf(5L to "Alt-Kunde GmbH", 8L to "Neu-Kunde AG")))

        assertEquals("journal.what.recipientChanged(Alt-Kunde GmbH,Neu-Kunde AG)", result)
    }

    @Test
    fun `a recipient_id diff on the dedicated RECIPIENT_CHANGED action renders the same way`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"recipient_id": {"old": "5", "new": "8"}}""")
        val line = testLine(action = InvoiceAuditAction.RECIPIENT_CHANGED, rows = listOf(row))

        val result = HeaderRecipientFamilyComposer.compose(line, testCtx(recipientNames = mapOf(5L to "Alt-Kunde GmbH", 8L to "Neu-Kunde AG")))

        assertEquals("journal.what.recipientChanged(Alt-Kunde GmbH,Neu-Kunde AG)", result)
    }

    @Test
    fun `a recipient id with no resolved name falls back to the unknown placeholder`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"recipient_id": {"old": "5", "new": "8"}}""")
        val line = testLine(action = InvoiceAuditAction.HEADER_CHANGED, rows = listOf(row))

        val result = HeaderRecipientFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.recipientChanged(journal.what.recipientUnknown,journal.what.recipientUnknown)", result)
    }

    /** [de.littletrotter.ui.view.journal.JournalValueFormatter]'s boolean decoding resolves
     *  `app.yes`/`app.no` through the plain `tr` function (never `trOrNull` — those two keys
     *  always exist), so this test's fake [testTr] echoes the key itself back unchanged; the real
     *  bundle values are pinned separately (`app.yes`/`app.no` are core, pre-existing keys). */
    @Test
    fun `a reverse-charge decision names old and new decoded booleans`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"reverse_charge": {"old": "false", "new": "true"}}""")
        val line = testLine(action = InvoiceAuditAction.REVERSE_CHARGE_DECIDED, rows = listOf(row))

        val result = HeaderRecipientFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.reverseChargeChanged(app.no,app.yes)", result)
    }

    /** An unknown/uncurated column falls back to its own raw name, never a crash. */
    @Test
    fun `a generic header change with an unknown column falls back to its humanized raw name`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"some_new_header_field": {"old": "a", "new": "b"}}""")
        val line = testLine(action = InvoiceAuditAction.HEADER_CHANGED, rows = listOf(row))

        val result = HeaderRecipientFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.headerChanged(Some new header field: a → b)", result)
    }

    @Test
    fun `more than three changed header fields collapse into a plus-n-more suffix`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_UPDATED,
            detail = """{"buyer_reference": {"old":"a","new":"b"}, "order_reference": {"old":"c","new":"d"},
                |"free_text": {"old":"e","new":"f"}, "cost_center": {"old":"g","new":"h"}}""".trimMargin(),
        )
        val line = testLine(action = InvoiceAuditAction.HEADER_CHANGED, rows = listOf(row))

        val result = HeaderRecipientFamilyComposer.compose(line, testCtx())

        assertEquals(true, result?.startsWith("journal.what.headerChangedMore("))
        assertEquals(true, result?.endsWith(",1)"))
    }

    /** A brand-new recipient assignment carries no prior id at all — `old` is JSON `null`, not a
     *  parseable id, so the placeholder must show for that side without throwing. */
    @Test
    fun `a null old recipient id falls back to the unknown placeholder on that side only`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"recipient_id": {"old": null, "new": "8"}}""")
        val line = testLine(action = InvoiceAuditAction.HEADER_CHANGED, rows = listOf(row))

        val result = HeaderRecipientFamilyComposer.compose(line, testCtx(recipientNames = mapOf(8L to "Neu-Kunde AG")))

        assertEquals("journal.what.recipientChanged(journal.what.recipientUnknown,Neu-Kunde AG)", result)
    }

    @Test
    fun `a non-numeric recipient id falls back to the unknown placeholder rather than crashing`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"recipient_id": {"old": "not-a-number", "new": "8"}}""")
        val line = testLine(action = InvoiceAuditAction.HEADER_CHANGED, rows = listOf(row))

        val result = HeaderRecipientFamilyComposer.compose(line, testCtx(recipientNames = mapOf(8L to "Neu-Kunde AG")))

        assertEquals("journal.what.recipientChanged(journal.what.recipientUnknown,Neu-Kunde AG)", result)
    }

    /** A reverse-charge value that is neither `"true"` nor `"false"` (never expected from the
     *  trigger, but the formatter must not throw) falls back to its own raw text. */
    @Test
    fun `a malformed reverse-charge value falls back to its own raw text`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"reverse_charge": {"old": "maybe", "new": "true"}}""")
        val line = testLine(action = InvoiceAuditAction.REVERSE_CHARGE_DECIDED, rows = listOf(row))

        val result = HeaderRecipientFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.reverseChargeChanged(maybe,app.yes)", result)
    }

    /** A curated enum column (unlike the "unknown column" case above) resolves through
     *  [de.littletrotter.ui.view.journal.JournalValueFormatter] to its own decoded label, never the
     *  raw ordinal. */
    @Test
    fun `a changed currency names both sides by their currency code, not the raw ordinal`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"currency": {"old": "0", "new": "1"}}""")
        val line = testLine(action = InvoiceAuditAction.HEADER_CHANGED, rows = listOf(row))

        val result = HeaderRecipientFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.headerChanged(Currency: EUR → CHF)", result)
    }

    @Test
    fun `an INVOICE_UPDATED row with no changed columns at all yields null`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = "{}")
        val line = testLine(action = InvoiceAuditAction.HEADER_CHANGED, rows = listOf(row))

        assertNull(HeaderRecipientFamilyComposer.compose(line, testCtx()))
    }

    /** A brand-new reverse-charge decision carries no prior value at all — `old` is JSON `null`. */
    @Test
    fun `a null old reverse-charge value falls back to the unknown-blank placeholder on that side only`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"reverse_charge": {"old": null, "new": "true"}}""")
        val line = testLine(action = InvoiceAuditAction.REVERSE_CHARGE_DECIDED, rows = listOf(row))

        val result = HeaderRecipientFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.reverseChargeChanged(,app.yes)", result)
    }

    /** A field newly populated in this transaction carries no prior value — `old` is JSON `null` —
     *  for a generic (non-recipient, non-reverse-charge) header change too. */
    @Test
    fun `a generic header change with a null old value falls back to a blank on that side only`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"cost_center": {"old": null, "new": "K1"}}""")
        val line = testLine(action = InvoiceAuditAction.HEADER_CHANGED, rows = listOf(row))

        val result = HeaderRecipientFamilyComposer.compose(line, testCtx())

        assertEquals(true, result?.contains(" → K1"))
    }

    /** A field cleared in this transaction carries no new value — `new` is JSON `null`. */
    @Test
    fun `a null new reverse-charge value falls back to a blank on that side only`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"reverse_charge": {"old": "true", "new": null}}""")
        val line = testLine(action = InvoiceAuditAction.REVERSE_CHARGE_DECIDED, rows = listOf(row))

        val result = HeaderRecipientFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.reverseChargeChanged(app.yes,)", result)
    }

    /** A field cleared in this transaction carries no new value for a generic header change too. */
    @Test
    fun `a generic header change with a null new value falls back to a blank on that side only`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"cost_center": {"old": "K1", "new": null}}""")
        val line = testLine(action = InvoiceAuditAction.HEADER_CHANGED, rows = listOf(row))

        val result = HeaderRecipientFamilyComposer.compose(line, testCtx())

        assertEquals(true, result?.contains("K1 → "))
    }

    @Test
    fun `no INVOICE_UPDATED row at all yields null`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_ITEM_UPDATED, detail = """{"quantity": {"old": "1", "new": "2"}}""")
        val line = testLine(action = InvoiceAuditAction.HEADER_CHANGED, rows = listOf(row))

        assertNull(HeaderRecipientFamilyComposer.compose(line, testCtx()))
    }
}
