package de.littletrotter.application.export

/**
 * The i18n bundle keys behind every `Rechnungen` sheet column header, English and German
 * both complete (`kotlin-invoice/src/main/resources/i18n/messages.properties`/`messages_de
 * .properties`). One place pins the key **order**, matching [de.littletrotter.infrastructure.export
 * .workbook.InvoiceHeaderSheetWriter]'s own fixed column order exactly, so a caller
 * builds [de.littletrotter.infrastructure.export.workbook.InvoiceHeaderSheetLabels.columnHeaders]
 * with `InvoiceHeaderColumnKeys.ORDERED_KEYS.map { tr(it) }` instead of re-deriving the order by
 * hand. This package writes no OOXML itself and resolves no bundle — see that class's own KDoc for
 * why the actual translation lookup is deliberately left to the caller.
 */
object InvoiceHeaderColumnKeys {

    private const val PREFIX = "invoiceExport.header.column."

    /** Exactly [de.littletrotter.infrastructure.export.workbook.InvoiceHeaderSheetWriter
     *  .COLUMN_COUNT] entries, in column order. */
    val ORDERED_KEYS: List<String> = listOf(
        "invoiceId", "invoiceNumber", "invoiceNumberPrefix", "invoiceNumberSequence",
        "typeCodeValue", "typeCodeLabel", "status", "validationStatus", "invoiceFormat",
        "documentLanguage",
        "invoiceDate", "deliveryDate", "servicePeriodFrom", "servicePeriodTo", "dueDate", "paymentTermsDays",
        "paymentTermName", "paymentTermDiscountPercent", "paymentTermDiscountDays",
        "sellerName", "sellerVatId", "sellerTaxNumber", "sellerIdentifierValue", "sellerIdentifierSchemeId",
        "sellerRegisterNumber", "sellerRegisterSchemeId", "sellerElectronicAddressValue",
        "sellerElectronicAddressSchemeId", "sellerVatRegistrationCountry",
        "buyerName", "buyerVatId", "buyerIdentifierValue", "buyerIdentifierSchemeId",
        "buyerElectronicAddressValue", "buyerElectronicAddressSchemeId", "partySource",
        "buyerReference", "purchaseOrderReference", "orderReference", "orderReferenceDate",
        "offerReference", "offerReferenceDate", "projectNumber", "costCenter",
        "originalInvoiceNumber", "originalInvoiceDate",
        "currency", "reverseCharge", "netAmount", "vatAmount", "grossAmount",
        "itemsCount", "receiptsCount", "needsRecalculation",
        "createdAt", "createdBy", "updatedAt", "updatedBy", "version", "freeText",
        "controlNet", "deltaNet", "controlVat", "deltaVat", "controlGross", "deltaGross",
        "controlItems", "deltaItems", "verdict",
    ).map { PREFIX + it }

    const val PARTY_SOURCE_SNAPSHOT_KEY = "invoiceExport.header.partySource.snapshot"
    const val PARTY_SOURCE_MASTER_DATA_KEY = "invoiceExport.header.partySource.masterData"
    const val VERDICT_OK_KEY = "invoiceExport.header.verdict.ok"
    const val VERDICT_DEVIATION_KEY = "invoiceExport.header.verdict.deviation"
    const val INVOICE_NUMBER_MALFORMED_MARKER_KEY = "invoiceExport.header.invoiceNumberMalformedMarker"
}
