package de.littletrotter.application.export

/**
 * The i18n bundle keys behind every `Positionen` sheet column header, English and German
 * both complete (`kotlin-invoice/src/main/resources/i18n/messages.properties`/`messages_de
 * .properties`). Mirrors [InvoiceHeaderColumnKeys]'s own role: one place pins the key **order**,
 * matching [de.littletrotter.infrastructure.export.workbook.InvoicePositionSheetWriter]'s own
 * fixed column order exactly.
 */
object InvoicePositionColumnKeys {

    private const val PREFIX = "invoiceExport.position.column."

    /** Exactly [de.littletrotter.infrastructure.export.workbook.InvoicePositionSheetWriter
     *  .COLUMN_COUNT] entries, in column order. */
    val ORDERED_KEYS: List<String> = listOf(
        "invoiceId", "invoiceNumber", "positionId", "sortOrder",
        "category", "description", "itemDescription",
        "sellerItemIdentifier", "buyerItemIdentifier", "articleNumber",
        "quantity", "unitSymbol", "uneceCode", "unitPriceNet",
        "taxCategoryCode", "taxCategoryLabel", "taxRatePercent", "taxRuleCode",
        "exemptionReasonCode", "exemptionReasonText", "taxLegalBasis",
        "netAmount", "vatAmount", "grossAmount",
        "servicePeriodFrom", "servicePeriodTo", "costCenter", "deliveryNoteReference",
        "receiptsCount", "receiptFileNames",
        // Trip position/detail rows: level, parent link, kind, travel date, per-diem block.
        "level", "parentPositionId", "tripKind", "travelDate",
        "perDiemArrivalDeparture", "perDiemBreakfastDeduction", "perDiemLunchDeduction", "perDiemDinnerDeduction",
        "perDiemEmployerContribution", "perDiemClaimableAmount",
        "controlNet", "deltaNet", "controlVat", "deltaVat", "controlGross", "deltaGross", "verdict",
    ).map { PREFIX + it }

    const val VERDICT_OK_KEY = "invoiceExport.position.verdict.ok"
    const val VERDICT_DEVIATION_KEY = "invoiceExport.position.verdict.deviation"
    const val VERDICT_TAX_RATE_MISSING_KEY = "invoiceExport.position.verdict.taxRateMissing"
}
