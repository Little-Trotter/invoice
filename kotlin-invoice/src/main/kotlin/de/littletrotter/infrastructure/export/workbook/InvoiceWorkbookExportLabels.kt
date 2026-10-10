package de.littletrotter.infrastructure.export.workbook

/**
 * Aggregates every label the single-invoice (mode A) four-sheet export needs. Internal to
 * this adapter (see [de.littletrotter.application.export.InvoiceWorkbookExportPort]'s own KDoc on
 * why the port itself never exposes these types) — built by [buildInvoiceWorkbookExportLabels] and
 * consumed only by the implementation of that port in this same package.
 */
data class InvoiceWorkbookExportLabels(
    val exportSheet: ExportSheetLabels,
    val headerSheet: InvoiceHeaderSheetLabels,
    val positionSheet: InvoicePositionSheetLabels,
    val journalSheet: InvoiceJournalSheetLabels,
)
