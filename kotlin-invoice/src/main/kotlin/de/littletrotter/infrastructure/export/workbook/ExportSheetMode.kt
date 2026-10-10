package de.littletrotter.infrastructure.export.workbook

/**
 * Which of the two export operating modes (see the programme's own README, "A — je
 * Rechnung" vs. "B — über die Rechnungstabelle") a workbook was built for. [ExportSheetWriter]
 * itself stays mode-agnostic (it only ever renders the [ExportSheetLabels] it is handed); this
 * enum exists solely so [buildInvoiceWorkbookExportLabels] knows which sheet-list wording the
 * "Export" cover sheet's first note ("Hinweise zum Export", note 1) needs: mode A's four
 * sheets ([SINGLE_INVOICE]) versus mode B's three ([FILTERED_LIST], no `Journal`).
 */
enum class ExportSheetMode {
    /** Mode A — one invoice, four sheets (`Export`, `Rechnungen`, `Positionen`, `Journal`). */
    SINGLE_INVOICE,

    /** Mode B — a filtered list, three sheets (`Export`, `Rechnungen`, `Positionen`), no
     *  `Journal`. */
    FILTERED_LIST,
}
