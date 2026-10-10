package de.littletrotter.application.export

import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InvoiceJournalDetailFlattenerTest {

    @Test
    fun `a single changed field renders as field colon old arrow new`() {
        val detail = """{"status": {"old": "0", "new": "1"}}"""
        val result = InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_UPDATED, detail)
        assertThat(result).isEqualTo("status: 0 → 1")
    }

    @Test
    fun `multiple changed fields join with a real line break, in payload order`() {
        val detail = """{"free_text": {"old": "a", "new": "b"}, "due_date": {"old": "2026-01-01", "new": "2026-02-01"}}"""
        val result = InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_UPDATED, detail)
        assertThat(result).isEqualTo("free_text: a → b\ndue_date: 2026-01-01 → 2026-02-01")
    }

    @Test
    fun `missing old or new value renders as the empty-value placeholder`() {
        val detail = """{"free_text": {"old": null, "new": "b"}}"""
        val result = InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_UPDATED, detail)
        assertThat(result).isEqualTo("free_text: - → b")
    }

    @Test
    fun `a missing new key renders the empty-value placeholder for new, independent of old`() {
        val detail = """{"free_text": {"old": "a"}}"""
        val result = InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_UPDATED, detail)
        assertThat(result).isEqualTo("free_text: a → -")
    }

    @Test
    fun `an INSERT snapshot renders as field colon value, never an old-to-new pair`() {
        val detail = """{"description": "Beratung", "quantity": 2}"""
        val result = InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_ITEM_CREATED, detail)
        assertThat(result).isEqualTo("description: Beratung\nquantity: 2")
    }

    @Test
    fun `a DELETE snapshot renders the same way as an INSERT snapshot`() {
        val detail = """{"sha256_checksum": "abc123"}"""
        val result = InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_DOCUMENT_DELETED, detail)
        assertThat(result).isEqualTo("sha256_checksum: abc123")
    }

    @Test
    fun `an empty JSON object renders as an empty string, not a crash and not the raw fallback`() {
        assertThat(InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_UPDATED, "{}")).isEmpty()
        assertThat(InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_CREATED, "{}")).isEmpty()
    }

    @Test
    fun `malformed JSON falls back to the raw string instead of throwing`() {
        val malformed = """{"status": {"old": "0", "new": """
        val result = InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_UPDATED, malformed)
        assertThat(result).isEqualTo(malformed)
    }

    @Test
    fun `an empty string payload parses to a missing node and falls back to the raw string`() {
        // Distinct from the malformed-JSON case: Jackson parses "" to a MissingNode
        // without throwing, exercising root.isMissingNode rather than root == null.
        val result = InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_UPDATED, "")
        assertThat(result).isEqualTo("")
    }

    @Test
    fun `an explicit JSON null unit value on an item event renders the empty-value placeholder`() {
        // invoiceItemUnitLabel's own null/missing/NullNode guard, distinct from a
        // numeric-or-legacy-name unit value: an explicit JSON null for the field.
        val detail = """{"unit": null}"""
        val result = InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_ITEM_CREATED, detail)
        assertThat(result).isEqualTo("unit: -")
    }

    @Test
    fun `a top-level JSON array or scalar falls back to the raw string as unexpected shape`() {
        assertThat(InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_UPDATED, "[1,2,3]")).isEqualTo("[1,2,3]")
        assertThat(InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_UPDATED, "\"just a string\"")).isEqualTo("\"just a string\"")
    }

    @Test
    fun `a null-ish payload never throws and never produces a huge or garbled cell`() {
        val result = InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_CREATED, "null")
        assertThat(result).isEqualTo("null")
    }

    @Test
    fun `a nested object value is embedded as its own raw JSON text, not stringified by hand`() {
        val detail = """{"payload": {"old": {"a": 1}, "new": {"a": 2}}}"""
        val result = InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_UPDATED, detail)
        assertThat(result).isEqualTo("payload: {\"a\":1} → {\"a\":2}")
    }

    @Test
    fun `a value containing an embedded line break is preserved verbatim`() {
        val detail = """{"free_text": {"old": "line1\nline2", "new": "single line"}}"""
        val result = InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_UPDATED, detail)
        assertThat(result).isEqualTo("free_text: line1\nline2 → single line")
    }

    @Test
    fun `a payload far beyond the OOXML cell limit is returned in full - truncation is the writer's job`() {
        val hugeValue = "x".repeat(40_000)
        val detail = """{"free_text": {"old": "a", "new": "$hugeValue"}}"""
        val result = InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_UPDATED, detail)
        assertThat(result.length).isGreaterThan(32_767)
    }

    @Test
    fun `a boolean or numeric value renders via its own JSON text representation`() {
        val detail = """{"reverse_charge": {"old": false, "new": true}, "version": {"old": 3, "new": 4}}"""
        val result = InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_UPDATED, detail)
        assertThat(result).isEqualTo("reverse_charge: false → true\nversion: 3 → 4")
    }

    @Test
    fun `all invoice item unit ordinals render as German labels in snapshots and changes`() {
        val itemEvents = listOf(
            InvoiceAuditEvent.INVOICE_ITEM_CREATED,
            InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
            InvoiceAuditEvent.INVOICE_ITEM_DELETED,
        )

        itemEvents.forEach { event ->
            InvoiceItemUnit.entries.forEach { unit ->
                val detail = if (event == InvoiceAuditEvent.INVOICE_ITEM_UPDATED) {
                    """{"unit": {"old": ${unit.ordinal}, "new": ${unit.ordinal}}}"""
                } else {
                    """{"unit": ${unit.ordinal}}"""
                }
                val expected = if (event == InvoiceAuditEvent.INVOICE_ITEM_UPDATED) {
                    "unit: ${unit.germanLabel(false)} → ${unit.germanLabel(false)}"
                } else {
                    "unit: ${unit.germanLabel(false)}"
                }
                assertThat(InvoiceJournalDetailFlattener.flatten(event, detail)).isEqualTo(expected)
            }
        }
    }

    @Test
    fun `legacy unit names are supported and unknown unit values never leak into export`() {
        assertThat(
            InvoiceJournalDetailFlattener.flatten(
                InvoiceAuditEvent.INVOICE_ITEM_CREATED,
                """{"unit": "HOUR"}""",
            ),
        ).isEqualTo("unit: Stunde")
        assertThat(
            InvoiceJournalDetailFlattener.flatten(
                InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
                """{"unit": {"old": "LEGACY_UNKNOWN", "new": 999}}""",
            ),
        ).isEqualTo("unit: Unbekannte Einheit → Unbekannte Einheit")
    }

    @Test
    fun `unit values on non-item events remain stored values`() {
        val detail = """{"unit": {"old": 0, "new": 1}}"""
        assertThat(InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_UPDATED, detail))
            .isEqualTo("unit: 0 → 1")
    }

    /** The reserved `_row` key is never emitted as a changed column; its position number
     *  becomes its own "Position: n" line instead. */
    @Test
    fun `the reserved _row key never renders as a changed column, its position becomes its own line`() {
        val detail = """{"_row": {"id": 42, "position_id": "3"}, "quantity": {"old": "1", "new": "2"}}"""
        val result = InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_ITEM_UPDATED, detail)
        assertThat(result).isEqualTo("Position: 3\nquantity: 1 → 2")
    }

    @Test
    fun `a _row identity with no position_id (invoice, invoice_document) contributes no line at all`() {
        val detail = """{"_row": {"id": 7}, "free_text": {"old": "a", "new": "b"}}"""
        val result = InvoiceJournalDetailFlattener.flatten(InvoiceAuditEvent.INVOICE_UPDATED, detail)
        assertThat(result).isEqualTo("free_text: a → b")
    }
}
