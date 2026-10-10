package de.littletrotter.ui.view.journal.composer

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DocumentValidationFamilyComposerTest {

    @Test
    fun `a generated document names its type and a shortened hash`() {
        val row = testRow(
            event = InvoiceAuditEvent.INVOICE_DOCUMENT_CREATED,
            detail = """{"document_type": "XRECHNUNG", "sha256_checksum": "abcdef0123456789abcdef0123456789"}""",
        )
        val line = testLine(action = InvoiceAuditAction.DOCUMENT_GENERATED, rows = listOf(row))

        val result = DocumentValidationFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.documentGenerated(XRECHNUNG,abcdef012345)", result)
    }

    /** [de.littletrotter.ui.view.journal.JournalValueFormatter]'s enum decoding resolves through
     *  the plain `tr` function (never `trOrNull`), so this test's fake [testTr] echoes each
     *  resolved key back unchanged — the real bundle values are pinned by
     *  [de.littletrotter.ui.i18n.InvoiceDetailTranslationsTest]/existing validation-status tests,
     *  not re-asserted here. */
    @Test
    fun `a header validation verdict names old and new decoded status`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"validation_status": {"old": "4", "new": "0"}}""")
        val line = testLine(action = InvoiceAuditAction.VALIDATION_RECORDED, rows = listOf(row))

        val result = DocumentValidationFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.validationRecorded(invoice.validation.notValidated,invoice.validation.pending)", result)
    }

    @Test
    fun `a document-level validation verdict decodes through the same enum`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_DOCUMENT_UPDATED, detail = """{"validation_status": {"old": "0", "new": "1"}}""")
        val line = testLine(action = InvoiceAuditAction.VALIDATION_RECORDED, rows = listOf(row))

        val result = DocumentValidationFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.validationRecorded(invoice.validation.pending,invoice.validation.valid)", result)
    }

    @Test
    fun `an INVOICE_UPDATED row with no validation diff yields null`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"free_text": {"old": "a", "new": "b"}}""")
        val line = testLine(action = InvoiceAuditAction.DOCUMENT_GENERATED, rows = listOf(row))

        assertNull(DocumentValidationFamilyComposer.compose(line, testCtx()))
    }

    /** No row is even shaped like a possible validation carrier (neither an invoice header nor a
     *  document update) — the short-circuit `!hasValidationRow` path, distinct from a validation-
     *  shaped row with no actual diff. */
    @Test
    fun `no row shaped like a validation carrier at all yields null without inspecting any diff`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_ITEM_UPDATED, detail = """{"quantity": {"old": "1", "new": "2"}}""")
        val line = testLine(action = InvoiceAuditAction.VALIDATION_RECORDED, rows = listOf(row))

        assertNull(DocumentValidationFamilyComposer.compose(line, testCtx()))
    }

    @Test
    fun `a generated document with no recorded hash falls back to a blank hash, not a crash`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_DOCUMENT_CREATED, detail = """{"document_type": "ZUGFERD"}""")
        val line = testLine(action = InvoiceAuditAction.DOCUMENT_GENERATED, rows = listOf(row))

        val result = DocumentValidationFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.documentGenerated(ZUGFERD,)", result)
    }

    @Test
    fun `a generated document with no document_type snapshot falls back to a blank type`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_DOCUMENT_CREATED, detail = """{"sha256_checksum": "abcdef0123456789"}""")
        val line = testLine(action = InvoiceAuditAction.DOCUMENT_GENERATED, rows = listOf(row))

        val result = DocumentValidationFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.documentGenerated(,abcdef012345)", result)
    }

    @Test
    fun `a null old validation status falls back to blank, the new status still decodes`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"validation_status": {"old": null, "new": "0"}}""")
        val line = testLine(action = InvoiceAuditAction.VALIDATION_RECORDED, rows = listOf(row))

        val result = DocumentValidationFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.validationRecorded(,invoice.validation.pending)", result)
    }

    @Test
    fun `an out-of-range validation status ordinal falls back to its own raw text, never a crash`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"validation_status": {"old": "99", "new": "0"}}""")
        val line = testLine(action = InvoiceAuditAction.VALIDATION_RECORDED, rows = listOf(row))

        val result = DocumentValidationFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.validationRecorded(99,invoice.validation.pending)", result)
    }

    /** Distinct from the out-of-range-ordinal case above: here `raw.toIntOrNull()` itself returns
     *  `null` (not a number at all), a separate null-safety branch from a merely out-of-range index. */
    @Test
    fun `a non-numeric validation status falls back to its own raw text, never a crash`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"validation_status": {"old": "n_a", "new": "0"}}""")
        val line = testLine(action = InvoiceAuditAction.VALIDATION_RECORDED, rows = listOf(row))

        val result = DocumentValidationFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.validationRecorded(n_a,invoice.validation.pending)", result)
    }

    /** Every prior boundary case above (null, out-of-range, non-numeric) only ever exercised the
     *  OLD side — `new` was always a cleanly decoding value ("0"/"1"), so `newLabel`'s own elvis
     *  fallback (`validationStatusLabel(new, ...) ?: new.orEmpty()`) never took its null branch.
     *  Symmetric to "a null old validation status falls back to blank, the new status still
     *  decodes" above, but for the new side. */
    @Test
    fun `a null new validation status falls back to blank, the old status still decodes`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"validation_status": {"old": "4", "new": null}}""")
        val line = testLine(action = InvoiceAuditAction.VALIDATION_RECORDED, rows = listOf(row))

        val result = DocumentValidationFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.validationRecorded(invoice.validation.notValidated,)", result)
    }

    /** Symmetric to "an out-of-range validation status ordinal falls back to its own raw text"
     *  above, but for the new side. */
    @Test
    fun `an out-of-range new validation status ordinal falls back to its own raw text, never a crash`() {
        val row = testRow(event = InvoiceAuditEvent.INVOICE_UPDATED, detail = """{"validation_status": {"old": "0", "new": "99"}}""")
        val line = testLine(action = InvoiceAuditAction.VALIDATION_RECORDED, rows = listOf(row))

        val result = DocumentValidationFamilyComposer.compose(line, testCtx())

        assertEquals("journal.what.validationRecorded(invoice.validation.pending,99)", result)
    }
}
