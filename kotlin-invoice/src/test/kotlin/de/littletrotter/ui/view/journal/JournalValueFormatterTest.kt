package de.littletrotter.ui.view.journal

import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.ResourceBundle

/** [JournalValueFormatter]: the payload's raw SMALLINT ordinals, JSON
 *  booleans and ISO timestamps become what a reader recognises, and anything it cannot decode
 *  falls through unchanged rather than throwing. */
class JournalValueFormatterTest {

    // Echoes the key so an assertion names the key, not the German wording behind it.
    private val trEcho: (String) -> String = { it }

    private val german = ResourceBundle.getBundle("i18n.messages", Locale.GERMAN)
    private val english = ResourceBundle.getBundle(
        "i18n.messages", Locale.ENGLISH,
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
    )

    private fun format(event: InvoiceAuditEvent, column: String, raw: String?) =
        JournalValueFormatter.format(event, column, raw, trEcho)

    @Test
    fun `an invoice status ordinal renders as its own status label, never as the number`() {
        assertEquals("invoice.status.draft", format(InvoiceAuditEvent.INVOICE_UPDATED, "status", "0"))
        assertEquals("invoice.status.issued", format(InvoiceAuditEvent.INVOICE_UPDATED, "status", "2"))
    }

    @Test
    fun `a validation status ordinal renders as the same label the invoice list uses`() {
        assertEquals("invoice.validation.valid", format(InvoiceAuditEvent.INVOICE_UPDATED, "validation_status", "1"))
    }

    @Test
    fun `boundary - every ValidationStatus ordinal renders its own dedicated label, not just VALID`() {
        assertEquals("invoice.validation.pending", format(InvoiceAuditEvent.INVOICE_UPDATED, "validation_status", "0"))
        assertEquals("invoice.validation.invalid", format(InvoiceAuditEvent.INVOICE_UPDATED, "validation_status", "2"))
        assertEquals("invoice.validation.unavailable", format(InvoiceAuditEvent.INVOICE_UPDATED, "validation_status", "3"))
        assertEquals("invoice.validation.notValidated", format(InvoiceAuditEvent.INVOICE_UPDATED, "validation_status", "4"))
    }

    @Test
    fun `a currency ordinal renders as its ISO code`() {
        assertEquals("EUR", format(InvoiceAuditEvent.INVOICE_CREATED, "currency", "0"))
        assertEquals("CHF", format(InvoiceAuditEvent.INVOICE_CREATED, "currency", "1"))
    }

    @Test
    fun `a document type ordinal renders as code plus label`() {
        assertEquals("380 · journal.value.typeCode.380", format(InvoiceAuditEvent.INVOICE_CREATED, "type_code", "0"))
    }

    @Test
    fun `a same-named column on a sibling table is never decoded against the invoice enum`() {
        // invoice_document has its own validation_status with a different meaning — decoding it
        // as the invoice header's enum would state a wrong fact, so it stays raw.
        assertEquals("1", format(InvoiceAuditEvent.INVOICE_DOCUMENT_UPDATED, "validation_status", "1"))
        assertEquals("0", format(InvoiceAuditEvent.INVOICE_ITEM_UPDATED, "status", "0"))
    }

    @Test
    fun `all invoice item unit ordinals render translated German labels`() {
        val tr = bundleResolver(german)
        InvoiceAuditEvent.entries.filter { it.name.startsWith("INVOICE_ITEM_") }.forEach { event ->
            InvoiceItemUnit.entries.forEach { unit ->
                assertEquals(expectedUnitLabel(german, unit), JournalValueFormatter.format(event, "unit", unit.ordinal.toString(), tr))
            }
        }
    }

    @Test
    fun `all invoice item unit ordinals render translated English labels`() {
        val tr = bundleResolver(english)
        InvoiceAuditEvent.entries.filter { it.name.startsWith("INVOICE_ITEM_") }.forEach { event ->
            InvoiceItemUnit.entries.forEach { unit ->
                assertEquals(expectedUnitLabel(english, unit), JournalValueFormatter.format(event, "unit", unit.ordinal.toString(), tr))
            }
        }
    }

    @Test
    fun `legacy unit names resolve and unknown units use the localized placeholder`() {
        val tr = bundleResolver(german)
        assertEquals("Stunde (h)", formatWith(InvoiceAuditEvent.INVOICE_ITEM_UPDATED, "unit", InvoiceItemUnit.HOUR.name, tr))
        assertEquals("Unbekannte Einheit", formatWith(InvoiceAuditEvent.INVOICE_ITEM_UPDATED, "unit", "99", tr))
        assertEquals("Unbekannte Einheit", formatWith(InvoiceAuditEvent.INVOICE_ITEM_UPDATED, "unit", "LEGACY_UNKNOWN", tr))
    }

    @Test
    fun `boundary - if even the unit-unknown placeholder key itself does not resolve, a hardcoded German fallback is used`() {
        val alwaysPlaceholder: (String) -> String = { "!{$it}!" }
        assertEquals(
            "Unbekannte Einheit",
            JournalValueFormatter.format(InvoiceAuditEvent.INVOICE_ITEM_UPDATED, "unit", "999", alwaysPlaceholder),
        )
    }

    @Test
    fun `unit column on non-item events remains unchanged`() {
        assertEquals("0", format(InvoiceAuditEvent.INVOICE_UPDATED, "unit", "0"))
    }

    @Test
    fun `an ordinal outside its enum falls back to the raw value instead of throwing`() {
        assertEquals("99", format(InvoiceAuditEvent.INVOICE_UPDATED, "status", "99"))
    }

    @Test
    fun `boundary - an out-of-range validation_status, currency or type_code ordinal falls back to the raw value`() {
        assertEquals("99", format(InvoiceAuditEvent.INVOICE_UPDATED, "validation_status", "99"))
        assertEquals("99", format(InvoiceAuditEvent.INVOICE_CREATED, "currency", "99"))
        assertEquals("99", format(InvoiceAuditEvent.INVOICE_CREATED, "type_code", "99"))
    }

    @Test
    fun `boundary - a non-numeric ordinal on a header event column falls back to the raw value`() {
        assertEquals("not-a-number", format(InvoiceAuditEvent.INVOICE_UPDATED, "status", "not-a-number"))
    }

    @Test
    fun `boundary - a column not in the header enum vocabulary is never decoded, even on a header event`() {
        assertEquals("plain-value", format(InvoiceAuditEvent.INVOICE_UPDATED, "notes", "plain-value"))
    }

    @Test
    fun `json booleans render as yes and no`() {
        assertEquals("app.yes", format(InvoiceAuditEvent.INVOICE_UPDATED, "reverse_charge", "true"))
        assertEquals("app.no", format(InvoiceAuditEvent.INVOICE_UPDATED, "reverse_charge", "false"))
    }

    @Test
    fun `timestamps and dates render in the German format`() {
        assertEquals("04.09.2026 12:00", format(InvoiceAuditEvent.INVOICE_UPDATED, "created_at", "2026-09-04T10:00:00Z"))
        assertEquals("01.10.2026", format(InvoiceAuditEvent.INVOICE_UPDATED, "due_date", "2026-10-01"))
    }

    @Test
    fun `plain text, numbers and unparseable values pass through unchanged`() {
        assertEquals("Notiz", format(InvoiceAuditEvent.INVOICE_UPDATED, "free_text", "Notiz"))
        assertEquals("42.50", format(InvoiceAuditEvent.INVOICE_UPDATED, "net_amount", "42.50"))
        assertEquals("{\"a\":1}", format(InvoiceAuditEvent.INVOICE_UPDATED, "free_text", "{\"a\":1}"))
    }

    @Test
    fun `an absent or blank value stays absent so the caller can show its own placeholder`() {
        assertNull(format(InvoiceAuditEvent.INVOICE_UPDATED, "free_text", null))
        assertEquals("", format(InvoiceAuditEvent.INVOICE_UPDATED, "free_text", ""))
    }

    private fun formatWith(event: InvoiceAuditEvent, column: String, raw: String?, tr: (String) -> String) =
        JournalValueFormatter.format(event, column, raw, tr)

    private fun bundleResolver(bundle: ResourceBundle): (String) -> String = { key ->
        if (bundle.containsKey(key)) bundle.getString(key) else "!{$key}!"
    }

    private fun expectedUnitLabel(bundle: ResourceBundle, unit: InvoiceItemUnit): String =
        bundle.getStringOrNull("invoiceDetail.${unit.labelKey}")
            ?: bundle.getString(unit.labelKey)

    private fun ResourceBundle.getStringOrNull(key: String): String? =
        if (containsKey(key)) getString(key) else null
}
