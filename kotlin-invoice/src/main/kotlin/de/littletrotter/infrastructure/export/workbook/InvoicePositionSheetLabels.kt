package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.catalogue.InvoiceItemCategory

/**
 * Every enum → label mapping [InvoicePositionSheetWriter] needs, mirroring
 * [InvoiceHeaderEnumLabels]'s own role and the same fallback-to-[Enum.name] behaviour ([labelFor]).
 */
data class InvoicePositionEnumLabels(
    val category: Map<InvoiceItemCategory, String>,
    /** Resolves the "Steuerkategorie Bezeichnung" column — looked up via
     *  [VatCategory.fromCodeOrNull] on the row's raw, as-stored `taxCategory` code; an
     *  unrecognised or absent code writes a blank cell rather than falling back to a name. */
    val vatCategory: Map<VatCategory, String>,
    /** The "Art" column's label for a trip expense detail row
     *  ([de.littletrotter.application.export.InvoicePositionExportRow.tripKind]); a per-diem-day
     *  detail row uses [InvoicePositionSheetLabels.perDiemKindLabel] instead, never this map. */
    val tripExpenseKind: Map<TripExpenseKind, String>,
)

/**
 * Every user-facing label the `Positionen` sheet needs. [columnHeaders] must carry exactly
 * [InvoicePositionSheetWriter.COLUMN_COUNT] entries, in the sheet's own fixed column order —
 * [InvoicePositionSheetWriter.write] fails fast with a clear message otherwise, mirroring
 * [InvoiceHeaderSheetWriter.write]'s own guard.
 */
data class InvoicePositionSheetLabels(
    val columnHeaders: List<String>,
    val enumLabels: InvoicePositionEnumLabels,
    /** The `Prüfung` column's text verdict when every Δ column is within tolerance. */
    val verdictOkLabel: String,
    /** The `Prüfung` column's text verdict when any Δ column exceeds the tolerance. */
    val verdictDeviationLabel: String,
    /** The `Prüfung` column's text verdict for a row whose `Steuersatz %` cell is empty — a
     *  "border case": an item with neither a frozen tax-rate snapshot nor a live rate reference
     *  must not fail the export, it writes this instead of a genuine deviation verdict. */
    val verdictTaxRateMissingLabel: String,
    /** The "Art" column's label for a per-diem-day detail row (reuses
     *  `invoiceDetail.perDiem.title`, the same label the trip dialog's own per-diem section
     *  already shows — no second "Verpflegungspauschale" string). */
    val perDiemKindLabel: String,
)
