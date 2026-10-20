package de.littletrotter.application.export

/**
 * Column indices of the raw scalar projection
 * [de.littletrotter.infrastructure.persistence.InvoiceExportReadRepositoryImpl] reads, named so no
 * literal column number appears in any of the per-group row mappers
 * ([InvoiceHeaderCoreRowMapper], [InvoicePartyRowMapper], [InvoiceTaxRowMapper],
 * [InvoiceTotalsRowMapper], [InvoiceTextRowMapper]) that [InvoiceHeaderRowMapper.fromRow] composes.
 *
 * The 0-indexed column order of `row` is the declaration order of the constants below.
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
    const val INVOICE_DATE = 8
    const val DELIVERY_DATE = 9
    const val SERVICE_PERIOD_FROM = 10
    const val SERVICE_PERIOD_TO = 11
    const val DUE_DATE = 12
    const val PAYMENT_TERMS_DAYS = 13
    const val PAYMENT_TERM_SNAPSHOT_NAME = 14
    const val PAYMENT_TERM_SNAPSHOT_DISCOUNT_PERCENT = 15
    const val PAYMENT_TERM_SNAPSHOT_DISCOUNT_DAYS = 16
    const val SELLER_NAME_LIVE = 17
    const val SELLER_VAT_ID_LIVE = 18
    const val SELLER_TAX_NUMBER_LIVE = 19
    const val SELLER_IDENTIFIER_VALUE_LIVE = 20
    const val SELLER_IDENTIFIER_SCHEME_LIVE = 21
    const val SELLER_REGISTER_NUMBER_LIVE = 22
    const val SELLER_REGISTER_SCHEME_LIVE = 23
    const val SELLER_EA_VALUE_LIVE = 24
    const val SELLER_EA_SCHEME_LIVE = 25
    const val SELLER_VAT_REGISTRATION_COUNTRY = 26
    const val SELLER_SNAPSHOT_ID = 27
    const val SELLER_SNAPSHOT_NAME = 28
    const val SELLER_SNAPSHOT_VAT_ID = 29
    const val SELLER_SNAPSHOT_TAX_NUMBER = 30
    const val SELLER_SNAPSHOT_ID_VALUE = 31
    const val SELLER_SNAPSHOT_ID_SCHEME = 32
    const val SELLER_SNAPSHOT_REG_VALUE = 33
    const val SELLER_SNAPSHOT_REG_SCHEME = 34
    const val SELLER_SNAPSHOT_EA_VALUE = 35
    const val SELLER_SNAPSHOT_EA_SCHEME = 36
    const val BUYER_NAME_LIVE = 37
    const val BUYER_VAT_ID_LIVE = 38
    const val BUYER_IDENTIFIER_VALUE_LIVE = 39
    const val BUYER_IDENTIFIER_SCHEME_LIVE = 40
    const val BUYER_EA_VALUE_LIVE = 41
    const val BUYER_EA_SCHEME_LIVE = 42
    const val BUYER_SNAPSHOT_ID = 43
    const val BUYER_SNAPSHOT_NAME = 44
    const val BUYER_SNAPSHOT_VAT_ID = 45
    const val BUYER_SNAPSHOT_ID_VALUE = 46
    const val BUYER_SNAPSHOT_ID_SCHEME = 47
    const val BUYER_SNAPSHOT_EA_VALUE = 48
    const val BUYER_SNAPSHOT_EA_SCHEME = 49
    const val BUYER_REFERENCE = 50
    const val PURCHASE_ORDER_REFERENCE = 51
    const val ORDER_REFERENCE = 52
    const val ORDER_REFERENCE_DATE = 53
    const val OFFER_REFERENCE = 54
    const val OFFER_REFERENCE_DATE = 55
    const val PROJECT_NUMBER = 56
    const val COST_CENTER = 57
    const val ORIGINAL_INVOICE_NUMBER = 58
    const val ORIGINAL_INVOICE_DATE = 59
    const val CURRENCY = 60
    const val REVERSE_CHARGE = 61
    const val NET_AMOUNT = 62
    const val VAT_AMOUNT = 63
    const val GROSS_AMOUNT = 64
    const val ITEMS_COUNT = 65
    const val RECEIPTS_COUNT = 66
    const val NEEDS_RECALCULATION = 67
    const val CREATED_AT = 68
    const val CREATED_BY = 69
    const val UPDATED_AT = 70
    const val UPDATED_BY = 71
    const val VERSION = 72
    const val FREE_TEXT = 73
}
