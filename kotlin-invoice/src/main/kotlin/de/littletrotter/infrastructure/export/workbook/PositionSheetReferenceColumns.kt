package de.littletrotter.infrastructure.export.workbook

/**
 * The `Rechnungen` sheet's control-formula block (R4) references `Positionen` sheet
 * columns by letter for its `SUMIFS`/`COUNTIFS` ranges, even though this package does
 * not write that sheet itself — [de.littletrotter.infrastructure.export.workbook
 * .InvoicePositionSheetWriter] does. These defaults are the real, wired-in column letters
 * of that sheet's own layout: [invoiceIdColumn] `A` (link column both sheets share, R1),
 * [netAmountColumn] `V` ("Netto", the amount as stored), [vatAmountColumn] `W`
 * ("Steuerbetrag") and [levelColumn] `AE` ("Ebene") — not the earlier placeholder guess this
 * class carried while that writer had not yet been built.
 */
data class PositionSheetReferenceColumns(
    val invoiceIdColumn: String = "A",
    val netAmountColumn: String = "V",
    val vatAmountColumn: String = "W",
    /** `0` for a real position row, `1` for a trip's own synthetic detail row; every
     *  `SUMIFS`/`COUNTIFS` range below adds `levelColumn = 0` as a second criterion so a trip's
     *  detail rows (which share its own `invoiceId`) are never counted twice. */
    val levelColumn: String = "AE",
)
