package de.littletrotter.ui.view.journal

import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.Currency
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.view.invoice.InvoiceUnitLabels
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Renders one `invoice_audit_journal.detail` value the way a reader expects it, not the way
 * Postgres serialised it. `to_jsonb(NEW)` (`V1__baseline_schema.sql`, section 13) writes what the
 * column holds: an ordinal for every enum this project persists as SMALLINT (project convention —
 * never a varchar code), a JSON boolean, an ISO-8601 timestamp. Read raw, an invoice status change
 * shows as `0 → 1`, which says nothing.
 *
 * Enum columns are resolved **per event**, never by column name alone: `status` means
 * [InvoiceStatus] on an `INVOICE_*` event and nothing at all on an `INVOICE_ITEM_*` or
 * `INVOICE_DOCUMENT_*` one, so a same-named column on a sibling table can never be decoded against
 * the wrong enum.
 *
 * Every conversion is best-effort by design: an ordinal outside its enum, an unparseable
 * timestamp, a JSON object where a scalar was expected all fall back to the raw text. A display
 * quirk in one field must never blank out the rest of an entry — the same reasoning
 * [JournalDetailRenderer] parses its payload defensively for.
 *
 * Pure and Vaadin-free, like the rest of this package.
 */
object JournalValueFormatter {

    /** `null` in, `null` out — the caller decides what an absent value looks like (`—`), the same
     *  split [JournalDetailRenderer.render] already uses. */
    fun format(event: InvoiceAuditEvent, column: String, raw: String?, tr: (String) -> String): String? {
        val value = raw?.takeIf { it.isNotBlank() } ?: return raw
        return enumLabel(event, column, value, tr)
            ?: booleanLabel(value, tr)
            ?: temporalLabel(value)
            ?: value
    }

    /** Only the three invoice-header events carry the header's own enum columns — see this
     *  object's KDoc on why the event, not the column name, decides. */
    private val INVOICE_HEADER_EVENTS = setOf(
        InvoiceAuditEvent.INVOICE_CREATED,
        InvoiceAuditEvent.INVOICE_UPDATED,
        InvoiceAuditEvent.INVOICE_DELETED,
    )

    private val INVOICE_ITEM_EVENTS = setOf(
        InvoiceAuditEvent.INVOICE_ITEM_CREATED,
        InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
        InvoiceAuditEvent.INVOICE_ITEM_DELETED,
    )

    private fun enumLabel(event: InvoiceAuditEvent, column: String, value: String, tr: (String) -> String): String? {
        if (event in INVOICE_ITEM_EVENTS && column == "unit") return invoiceItemUnitLabel(value, tr)
        if (event !in INVOICE_HEADER_EVENTS) return null
        val ordinal = value.toIntOrNull() ?: return null
        return when (column) {
            "status" -> InvoiceStatus.entries.getOrNull(ordinal)?.let { tr(it.labelKey) }
            "validation_status" -> ValidationStatus.entries.getOrNull(ordinal)?.let { tr(validationLabelKey(it)) }
            "currency" -> Currency.entries.getOrNull(ordinal)?.code
            "type_code" -> InvoiceTypeCode.entries.getOrNull(ordinal)?.let { "${it.code} · ${tr("journal.value.typeCode.${it.code}")}" }
            else -> null
        }
    }

    /** Invoice-item audit rows store this enum as a SMALLINT ordinal. Name-form values are also
     * decoded defensively; unknown values never become raw user-facing unit labels. */
    private fun invoiceItemUnitLabel(value: String, tr: (String) -> String): String {
        val unit = value.toIntOrNull()?.let { InvoiceItemUnit.entries.getOrNull(it) }
            ?: InvoiceItemUnit.entries.firstOrNull { it.name == value }
        return unit?.let {
            InvoiceUnitLabels.label(it) { key ->
                tr(key).takeUnless { translated -> translated == "!{$key}!" }
            }
        } ?: tr("invoiceDetail.unit.unknown").takeUnless { it == "!{invoiceDetail.unit.unknown}!" }
            ?: "Unbekannte Einheit"
    }

    /** Same five keys de.littletrotter.ui.view.invoice.InvoiceListView.validationLabel renders
     *  the invoice list's own validation column from — one wording for one status across the app. */
    private fun validationLabelKey(status: ValidationStatus): String = when (status) {
        ValidationStatus.VALID -> "invoice.validation.valid"
        ValidationStatus.INVALID -> "invoice.validation.invalid"
        ValidationStatus.UNAVAILABLE -> "invoice.validation.unavailable"
        ValidationStatus.NOT_VALIDATED -> "invoice.validation.notValidated"
        ValidationStatus.PENDING -> "invoice.validation.pending"
    }

    private fun booleanLabel(value: String, tr: (String) -> String): String? = when (value) {
        "true" -> tr("app.yes")
        "false" -> tr("app.no")
        else -> null
    }

    /** Timestamps render in [de.littletrotter.shared.GermanTime.ZONE] (via [DateFormatter]); a
     *  DATE column carries no zone and is formatted as written, so a date near midnight is never
     *  shifted by one day. */
    private fun temporalLabel(value: String): String? =
        runCatching { OffsetDateTime.parse(value) }.getOrNull()?.let { DateFormatter.format(it) }
            ?: runCatching { LocalDate.parse(value) }.getOrNull()?.let { DateFormatter.format(it) }
}
