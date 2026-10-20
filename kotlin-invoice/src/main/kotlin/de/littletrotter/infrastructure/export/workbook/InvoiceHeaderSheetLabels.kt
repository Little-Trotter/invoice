package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.application.export.PartySource
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.shared.ValidationStatus

/**
 * Every enum → label mapping [InvoiceHeaderSheetWriter] needs, one map per enum used on
 * the `Rechnungen` sheet. This package writes OOXML and carries no i18n bundle of its own (same
 * boundary as [ExportSheetLabels]) — the caller (eventually the export dialog) resolves each
 * key through the i18n bundle in whichever language the export is generated in and hands the
 * already-resolved text in here. A map missing an entry falls back to the enum's own [Enum.name]
 * at write time rather than failing the whole export — a caller bug in an otherwise-working label
 * set should not be worse than an unmapped-but-visible placeholder.
 */
data class InvoiceHeaderEnumLabels(
    val typeCode: Map<InvoiceTypeCode, String>,
    val status: Map<InvoiceStatus, String>,
    val validationStatus: Map<ValidationStatus, String>,
    val invoiceFormat: Map<InvoiceFormat, String>,
    val partySource: Map<PartySource, String>,
)

/**
 * Every user-facing label the `Rechnungen` sheet needs. [columnHeaders] must carry exactly
 * [InvoiceHeaderSheetWriter.COLUMN_COUNT] entries, in the sheet's own fixed column order —
 * [InvoiceHeaderSheetWriter.write] fails fast with a clear message otherwise rather than
 * silently writing a header row shorter or longer than the data rows below it.
 */
data class InvoiceHeaderSheetLabels(
    val columnHeaders: List<String>,
    val enumLabels: InvoiceHeaderEnumLabels,
    /** The `Prüfung` column's text verdict when every Δ column is within tolerance. */
    val verdictOkLabel: String,
    /** The `Prüfung` column's text verdict when any Δ column exceeds the tolerance. */
    val verdictDeviationLabel: String,
    /** [java.text.MessageFormat] pattern with exactly one `{0}` — the raw stored invoice number —
     *  applied to the `Rechnungsnummer` cell when [de.littletrotter.application.export
     *  .InvoiceHeaderExportRow.invoiceNumberMalformed] is `true`. */
    val malformedNumberMarkerPattern: String,
)
