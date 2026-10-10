package de.littletrotter.application.export

/**
 * Column indices of the raw scalar projection
 * [de.littletrotter.infrastructure.persistence.InvoiceExportReadRepositoryImpl] reads, named so no
 * literal column number appears in any of the per-group row mappers
 * ([InvoiceHeaderCoreRowMapper], [InvoicePartyRowMapper], [InvoiceTaxRowMapper],
 * [InvoiceTotalsRowMapper], [InvoiceTextRowMapper]) that [InvoiceHeaderRowMapper.fromRow] composes.
 *
 * Column layout of `row` (0-indexed):
 * ```
 *  0 id                                       17 paymentTermSnapshot.discountDays
 *  1 invoiceNumberPrefix                      18 seller.name (live, coalesced)
 *  2 invoiceNumberSequence                    19 seller.vatId (live)
 *  3 typeCode                                 20 seller.taxNumber (live)
 *  4 status                                   21 seller.identifier.value (live)
 *  5 validationStatus                         22 seller.identifier.schemeId (live)
 *  6 invoiceFormat                            23 seller.registerNumber (live)
 *  7 documentLanguage                         24 seller.registerSchemeId (live)
 *  8 documentEngine                           25 seller.electronicAddress.value (live)
 *  9 invoiceDate                              26 seller.electronicAddress.schemeId (live)
 * 10 deliveryDate                             27 sellerVatRegistrationCountry
 * 11 servicePeriodFrom                        28 sellerSnapshot.id (presence flag)
 * 12 servicePeriodTo                          29 sellerSnapshot.name
 * 13 dueDate                                  30 sellerSnapshot.vatId
 * 14 paymentTermsDays                         31 sellerSnapshot.taxNumber
 * 15 paymentTermSnapshot.name                 32 sellerSnapshot.identifier.value
 * 16 paymentTermSnapshot.discountPercent      33 sellerSnapshot.identifier.schemeId
 * 34 sellerSnapshot.legalRegistration.value   52 purchaseOrderReference
 * 35 sellerSnapshot.legalRegistration.schemeId 53 orderReference
 * 36 sellerSnapshot.electronicAddress.value   54 orderReferenceDate
 * 37 sellerSnapshot.electronicAddress.schemeId 55 offerReference
 * 38 buyer.name (live, recipient)             56 offerReferenceDate
 * 39 buyer.vatId (live)                       57 projectNumber
 * 40 buyer.identifier.value (live)            58 costCenter
 * 41 buyer.identifier.schemeId (live)         59 originalInvoiceNumber
 * 42 buyer.electronicAddress.value (live)     60 originalInvoiceDate
 * 43 buyer.electronicAddress.schemeId (live)  61 currency
 * 44 buyerSnapshot.id (presence flag)         62 reverseCharge
 * 45 buyerSnapshot.name                       63 netAmount
 * 46 buyerSnapshot.vatId                      64 vatAmount
 * 47 buyerSnapshot.identifier.value           65 grossAmount
 * 48 buyerSnapshot.identifier.schemeId        66 itemsCount
 * 49 buyerSnapshot.electronicAddress.value    67 receiptsCount
 * 50 buyerSnapshot.electronicAddress.schemeId 68 needsRecalculation
 * 51 buyerReference                           69 createdAt
 *                                             70 createdBy
 *                                             71 updatedAt
 *                                             72 updatedBy
 *                                             73 version
 *                                             74 freeText
 * ```
 */
internal object InvoiceHeaderRowIndex {
    const val ID = 0
    const val INVOICE_NUMBER_PREFIX = 1
    const val INVOICE_NUMBER_SEQUENCE = 2
    const val TYPE_CODE = 3
    const val STATUS = 4
    const val VALIDATION_STATUS = 5
    const val INVOICE_FORMAT = 6
    const val DOCUMENT_LANGUAGE = 7
    const val DOCUMENT_ENGINE = 8
    const val INVOICE_DATE = 9
    const val DELIVERY_DATE = 10
    const val SERVICE_PERIOD_FROM = 11
    const val SERVICE_PERIOD_TO = 12
    const val DUE_DATE = 13
    const val PAYMENT_TERMS_DAYS = 14
    const val PAYMENT_TERM_SNAPSHOT_NAME = 15
    const val PAYMENT_TERM_SNAPSHOT_DISCOUNT_PERCENT = 16
    const val PAYMENT_TERM_SNAPSHOT_DISCOUNT_DAYS = 17
    const val SELLER_NAME_LIVE = 18
    const val SELLER_VAT_ID_LIVE = 19
    const val SELLER_TAX_NUMBER_LIVE = 20
    const val SELLER_IDENTIFIER_VALUE_LIVE = 21
    const val SELLER_IDENTIFIER_SCHEME_LIVE = 22
    const val SELLER_REGISTER_NUMBER_LIVE = 23
    const val SELLER_REGISTER_SCHEME_LIVE = 24
    const val SELLER_EA_VALUE_LIVE = 25
    const val SELLER_EA_SCHEME_LIVE = 26
    const val SELLER_VAT_REGISTRATION_COUNTRY = 27
    const val SELLER_SNAPSHOT_ID = 28
    const val SELLER_SNAPSHOT_NAME = 29
    const val SELLER_SNAPSHOT_VAT_ID = 30
    const val SELLER_SNAPSHOT_TAX_NUMBER = 31
    const val SELLER_SNAPSHOT_ID_VALUE = 32
    const val SELLER_SNAPSHOT_ID_SCHEME = 33
    const val SELLER_SNAPSHOT_REG_VALUE = 34
    const val SELLER_SNAPSHOT_REG_SCHEME = 35
    const val SELLER_SNAPSHOT_EA_VALUE = 36
    const val SELLER_SNAPSHOT_EA_SCHEME = 37
    const val BUYER_NAME_LIVE = 38
    const val BUYER_VAT_ID_LIVE = 39
    const val BUYER_IDENTIFIER_VALUE_LIVE = 40
    const val BUYER_IDENTIFIER_SCHEME_LIVE = 41
    const val BUYER_EA_VALUE_LIVE = 42
    const val BUYER_EA_SCHEME_LIVE = 43
    const val BUYER_SNAPSHOT_ID = 44
    const val BUYER_SNAPSHOT_NAME = 45
    const val BUYER_SNAPSHOT_VAT_ID = 46
    const val BUYER_SNAPSHOT_ID_VALUE = 47
    const val BUYER_SNAPSHOT_ID_SCHEME = 48
    const val BUYER_SNAPSHOT_EA_VALUE = 49
    const val BUYER_SNAPSHOT_EA_SCHEME = 50
    const val BUYER_REFERENCE = 51
    const val PURCHASE_ORDER_REFERENCE = 52
    const val ORDER_REFERENCE = 53
    const val ORDER_REFERENCE_DATE = 54
    const val OFFER_REFERENCE = 55
    const val OFFER_REFERENCE_DATE = 56
    const val PROJECT_NUMBER = 57
    const val COST_CENTER = 58
    const val ORIGINAL_INVOICE_NUMBER = 59
    const val ORIGINAL_INVOICE_DATE = 60
    const val CURRENCY = 61
    const val REVERSE_CHARGE = 62
    const val NET_AMOUNT = 63
    const val VAT_AMOUNT = 64
    const val GROSS_AMOUNT = 65
    const val ITEMS_COUNT = 66
    const val RECEIPTS_COUNT = 67
    const val NEEDS_RECALCULATION = 68
    const val CREATED_AT = 69
    const val CREATED_BY = 70
    const val UPDATED_AT = 71
    const val UPDATED_BY = 72
    const val VERSION = 73
    const val FREE_TEXT = 74
}
