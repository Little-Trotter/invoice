package de.littletrotter.shared.b2b.codes

/**
 * Which Factur-X profile a [B2bCodeList] value set is read for. `EN16931` is the default
 * everywhere (it is the export profile this product produces); `EXTENDED` is an optional add-on
 * layer loaded on demand, never merged into the base (PO decision 29.08.2026).
 */
enum class FacturXProfile { EN16931, EXTENDED }

/**
 * The Factur-X code-list ("Wertebereich") subdomain, hand-written on top of the generated
 * raw value objects ([B2bCodeListValuesEn16931], [B2bCodeListValuesExtended]) in the same package.
 *
 * Each constant is one *semantically* identified code list — keyed by the Schematron context that
 * binds it (element/attribute + business-term neighbourhood), never by the codedb's raw `cl` id:
 * `cl` ids are **not stable across profiles** (e.g. units are `cl 8` in EN16931 but `cl 15` in
 * EXTENDED) — see `scripts/codegen/facturx_codedb_gen.py` for the full derivation. The
 * per-constant KDoc records the standard/BT reference and both profiles' `cl` ids for traceability.
 *
 * [values]/[contains] default to [FacturXProfile.EN16931] — the profile this product exports.
 * EXTENDED-only lists (no EN16931 counterpart) return an empty set / `false` for EN16931 — never a
 * silent fallback to the EXTENDED values. The two constructor lambdas keep the EXTENDED raw object
 * un-referenced (and therefore un-classloaded, per JVM `object` init semantics) until a caller
 * actually requests `FacturXProfile.EXTENDED` — EN16931-only operation never touches it.
 *
 * Consumers must go through this enum; nothing outside this package may import
 * [B2bCodeListValuesEn16931] or [B2bCodeListValuesExtended] directly (enforced by an ArchUnit rule
 * in kotlin-shared's test sources).
 */
enum class B2bCodeList(
    private val en16931Values: () -> Set<String>,
    private val extendedValues: () -> Set<String>,
) {
    /**
     * Guideline specification identifier URN (BT-24). NOT a superset relation across profiles —
     * EXTENDED uses distinct '#conformant#...' suffixed URNs, not the plain EN16931 URN
     * (documented exception). `cl` id — EN16931: 1, EXTENDED: 1.
     */
    SPECIFICATION_IDENTIFIER(
        { B2bCodeListValuesEn16931.SPECIFICATION_IDENTIFIER },
        { B2bCodeListValuesExtended.SPECIFICATION_IDENTIFIER },
    ),

    /**
     * UNTDID 1001 document name code (BT-3). `cl` id — EN16931: 2, EXTENDED: 2.
     */
    DOCUMENT_TYPE_1001(
        { B2bCodeListValuesEn16931.DOCUMENT_TYPE_1001 },
        { B2bCodeListValuesExtended.DOCUMENT_TYPE_1001 },
    ),

    /**
     * Date format qualifier for udt:DateTimeString@format (BT-2 and others); fixed to '102'. `cl`
     * id — EN16931: 3, EXTENDED: 3.
     */
    DATE_FORMAT_QUALIFIER(
        { B2bCodeListValuesEn16931.DATE_FORMAT_QUALIFIER },
        { B2bCodeListValuesExtended.DATE_FORMAT_QUALIFIER },
    ),

    /**
     * UNTDID 4451 text subject code qualifier (BT-21). `cl` id — EN16931: 4, EXTENDED: 5.
     */
    NOTE_SUBJECT_CODE_4451(
        { B2bCodeListValuesEn16931.NOTE_SUBJECT_CODE_4451 },
        { B2bCodeListValuesExtended.NOTE_SUBJECT_CODE_4451 },
    ),

    /**
     * ISO 6523 ICD, item standard identifier scheme (BT-157). `cl` id — EN16931: 5, EXTENDED: 8.
     */
    ITEM_STANDARD_ID_SCHEME_ICD(
        { B2bCodeListValuesEn16931.ITEM_STANDARD_ID_SCHEME_ICD },
        { B2bCodeListValuesExtended.ITEM_STANDARD_ID_SCHEME_ICD },
    ),

    /**
     * UNTDID 7143 item classification scheme identifier (BT-158). `cl` id — EN16931: 6, EXTENDED:
     * 9.
     */
    ITEM_CLASSIFICATION_SCHEME_7143(
        { B2bCodeListValuesEn16931.ITEM_CLASSIFICATION_SCHEME_7143 },
        { B2bCodeListValuesExtended.ITEM_CLASSIFICATION_SCHEME_7143 },
    ),

    /**
     * ISO 3166-1 country code (BT-40/55/69/80 and others). `cl` id — EN16931: 7, EXTENDED: 10.
     */
    COUNTRY_ISO_3166(
        { B2bCodeListValuesEn16931.COUNTRY_ISO_3166 },
        { B2bCodeListValuesExtended.COUNTRY_ISO_3166 },
    ),

    /**
     * UN/ECE Recommendation 20+21 unit of measure code (BT-129/130/150). `cl` id — EN16931: 8,
     * EXTENDED: 15.
     */
    UNIT_UNECE_REC20(
        { B2bCodeListValuesEn16931.UNIT_UNECE_REC20 },
        { B2bCodeListValuesExtended.UNIT_UNECE_REC20 },
    ),

    /**
     * UNTDID 5153 duty/tax/fee type code; EN16931 restricts this to 'VAT' only. `cl` id — EN16931:
     * 9, EXTENDED: 26.
     */
    TAX_TYPE_CODE_5153(
        { B2bCodeListValuesEn16931.TAX_TYPE_CODE_5153 },
        { B2bCodeListValuesExtended.TAX_TYPE_CODE_5153 },
    ),

    /**
     * UNTDID 5305 duty/tax/fee category code (BT-118/95/102/151). `cl` id — EN16931: 10, EXTENDED:
     * 24.
     */
    VAT_CATEGORY_5305(
        { B2bCodeListValuesEn16931.VAT_CATEGORY_5305 },
        { B2bCodeListValuesExtended.VAT_CATEGORY_5305 },
    ),

    /**
     * UNTDID 5189 allowance reason code, line level (BT-140). `cl` id — EN16931: 11, EXTENDED: 28.
     */
    ALLOWANCE_REASON_5189_LINE(
        { B2bCodeListValuesEn16931.ALLOWANCE_REASON_5189_LINE },
        { B2bCodeListValuesExtended.ALLOWANCE_REASON_5189_LINE },
    ),

    /**
     * UNTDID 7161 charge reason code, line level (BT-144). `cl` id — EN16931: 12, EXTENDED: 29.
     */
    CHARGE_REASON_7161_LINE(
        { B2bCodeListValuesEn16931.CHARGE_REASON_7161_LINE },
        { B2bCodeListValuesExtended.CHARGE_REASON_7161_LINE },
    ),

    /**
     * UNTDID 1001 subset {50,130,916}, line-level AdditionalReferencedDocument/TypeCode. `cl` id —
     * EN16931: 13, EXTENDED: 18.
     */
    REFERENCED_DOC_TYPE_LINE(
        { B2bCodeListValuesEn16931.REFERENCED_DOC_TYPE_LINE },
        { B2bCodeListValuesExtended.REFERENCED_DOC_TYPE_LINE },
    ),

    /**
     * UNTDID 1153 reference qualifier code (BT-18/BT-128 ReferenceTypeCode). `cl` id — EN16931:
     * 14, EXTENDED: 20.
     */
    REFERENCE_QUALIFIER_1153(
        { B2bCodeListValuesEn16931.REFERENCE_QUALIFIER_1153 },
        { B2bCodeListValuesExtended.REFERENCE_QUALIFIER_1153 },
    ),

    /**
     * ISO 6523 ICD, party identifier scheme (BT-46/29/71/60). `cl` id — EN16931: 15, EXTENDED: 11.
     */
    PARTY_ID_SCHEME_ICD(
        { B2bCodeListValuesEn16931.PARTY_ID_SCHEME_ICD },
        { B2bCodeListValuesExtended.PARTY_ID_SCHEME_ICD },
    ),

    /**
     * ISO 6523 ICD, legal registration identifier scheme (BT-47/30/61). `cl` id — EN16931: 16,
     * EXTENDED: 12.
     */
    LEGAL_REG_ID_SCHEME_ICD(
        { B2bCodeListValuesEn16931.LEGAL_REG_ID_SCHEME_ICD },
        { B2bCodeListValuesExtended.LEGAL_REG_ID_SCHEME_ICD },
    ),

    /**
     * EAS electronic address scheme identifier (BT-49/34). `cl` id — EN16931: 17, EXTENDED: 13.
     */
    ELECTRONIC_ADDRESS_SCHEME_EAS(
        { B2bCodeListValuesEn16931.ELECTRONIC_ADDRESS_SCHEME_EAS },
        { B2bCodeListValuesExtended.ELECTRONIC_ADDRESS_SCHEME_EAS },
    ),

    /**
     * Fixed 'VA', buyer VAT tax registration scheme identifier (BT-48). `cl` id — EN16931: 18,
     * EXTENDED: 34.
     */
    TAX_REGISTRATION_SCHEME_BUYER(
        { B2bCodeListValuesEn16931.TAX_REGISTRATION_SCHEME_BUYER },
        { B2bCodeListValuesExtended.TAX_REGISTRATION_SCHEME_BUYER },
    ),

    /**
     * Fixed 'VA', seller tax representative VAT tax registration scheme identifier. `cl` id —
     * EN16931: 19, EXTENDED: 35.
     */
    TAX_REGISTRATION_SCHEME_SELLER_TAX_REP(
        { B2bCodeListValuesEn16931.TAX_REGISTRATION_SCHEME_SELLER_TAX_REP },
        { B2bCodeListValuesExtended.TAX_REGISTRATION_SCHEME_SELLER_TAX_REP },
    ),

    /**
     * UNTDID 1001 subset {916}, supporting document type code (BT-122 group). `cl` id — EN16931:
     * 20, EXTENDED: 38.
     */
    SUPPORTING_DOC_TYPE(
        { B2bCodeListValuesEn16931.SUPPORTING_DOC_TYPE },
        { B2bCodeListValuesExtended.SUPPORTING_DOC_TYPE },
    ),

    /**
     * MIME type code, attached supporting document (BT-125-1). `cl` id — EN16931: 21, EXTENDED:
     * 19.
     */
    MIME_TYPE(
        { B2bCodeListValuesEn16931.MIME_TYPE },
        { B2bCodeListValuesExtended.MIME_TYPE },
    ),

    /**
     * UNTDID 1001 subset {50}, tender/lot reference document type (BT-17). `cl` id — EN16931: 22,
     * EXTENDED: 39.
     */
    TENDER_LOT_DOC_TYPE(
        { B2bCodeListValuesEn16931.TENDER_LOT_DOC_TYPE },
        { B2bCodeListValuesExtended.TENDER_LOT_DOC_TYPE },
    ),

    /**
     * UNTDID 1001 subset {130}, invoiced object reference document type (BT-18). `cl` id —
     * EN16931: 23, EXTENDED: 40.
     */
    INVOICE_DATA_SHEET_DOC_TYPE(
        { B2bCodeListValuesEn16931.INVOICE_DATA_SHEET_DOC_TYPE },
        { B2bCodeListValuesExtended.INVOICE_DATA_SHEET_DOC_TYPE },
    ),

    /**
     * ISO 4217 currency code (BT-5 invoice currency, BT-6 tax currency). `cl` id — EN16931: 24,
     * EXTENDED: 42.
     */
    CURRENCY_ISO_4217(
        { B2bCodeListValuesEn16931.CURRENCY_ISO_4217 },
        { B2bCodeListValuesExtended.CURRENCY_ISO_4217 },
    ),

    /**
     * UNTDID 4461 payment means type code (BT-81). `cl` id — EN16931: 25, EXTENDED: 43.
     */
    PAYMENT_MEANS_4461(
        { B2bCodeListValuesEn16931.PAYMENT_MEANS_4461 },
        { B2bCodeListValuesExtended.PAYMENT_MEANS_4461 },
    ),

    /**
     * VATEX VAT exemption reason code (BT-121). `cl` id — EN16931: 26, EXTENDED: 25.
     */
    VATEX_EXEMPTION_REASON(
        { B2bCodeListValuesEn16931.VATEX_EXEMPTION_REASON },
        { B2bCodeListValuesExtended.VATEX_EXEMPTION_REASON },
    ),

    /**
     * Date format qualifier for udt:DateString@format, tax point date (BT-7). `cl` id — EN16931:
     * 27, EXTENDED: 44.
     */
    TAX_POINT_DATE_FORMAT(
        { B2bCodeListValuesEn16931.TAX_POINT_DATE_FORMAT },
        { B2bCodeListValuesExtended.TAX_POINT_DATE_FORMAT },
    ),

    /**
     * Due date type code (BT-9 group), fixed small set {5,29,72}. `cl` id — EN16931: 28, EXTENDED:
     * 27.
     */
    PAYMENT_TERMS_DUE_DATE_TYPE(
        { B2bCodeListValuesEn16931.PAYMENT_TERMS_DUE_DATE_TYPE },
        { B2bCodeListValuesExtended.PAYMENT_TERMS_DUE_DATE_TYPE },
    ),

    /**
     * UNTDID 5189 allowance reason code, document level (BT-98 group). `cl` id — EN16931: 29,
     * EXTENDED: 45.
     */
    ALLOWANCE_REASON_5189_HEADER(
        { B2bCodeListValuesEn16931.ALLOWANCE_REASON_5189_HEADER },
        { B2bCodeListValuesExtended.ALLOWANCE_REASON_5189_HEADER },
    ),

    /**
     * UNTDID 7161 charge reason code, document level (BT-105 group). `cl` id — EN16931: 30,
     * EXTENDED: 46.
     */
    CHARGE_REASON_7161_HEADER(
        { B2bCodeListValuesEn16931.CHARGE_REASON_7161_HEADER },
        { B2bCodeListValuesExtended.CHARGE_REASON_7161_HEADER },
    ),

    /**
     * ISO 4217, VAT total amount in invoice currency (BT-110). `cl` id — EN16931: 31, EXTENDED:
     * 49.
     */
    VAT_TOTAL_CURRENCY_INVOICE(
        { B2bCodeListValuesEn16931.VAT_TOTAL_CURRENCY_INVOICE },
        { B2bCodeListValuesExtended.VAT_TOTAL_CURRENCY_INVOICE },
    ),

    /**
     * ISO 4217, VAT total amount in tax accounting currency (BT-111). `cl` id — EN16931: 32,
     * EXTENDED: 50.
     */
    VAT_TOTAL_CURRENCY_TAX(
        { B2bCodeListValuesEn16931.VAT_TOTAL_CURRENCY_TAX },
        { B2bCodeListValuesExtended.VAT_TOTAL_CURRENCY_TAX },
    ),

    /**
     * Date format qualifier for qdt:DateTimeString@format,
     * InvoiceReferencedDocument/FormattedIssueDateTime; EXTENDED additionally allows '205'
     * (superset). `cl` id — EN16931: 33, EXTENDED: 17.
     */
    PRECEDING_INVOICE_DOC_DATE_FORMAT(
        { B2bCodeListValuesEn16931.PRECEDING_INVOICE_DOC_DATE_FORMAT },
        { B2bCodeListValuesExtended.PRECEDING_INVOICE_DOC_DATE_FORMAT },
    ),

    /**
     * ISO 639 language code, invoice language (EXTENDED-only). `cl` id — EN16931: n/a, EXTENDED:
     * 4.
     */
    LANGUAGE_ISO_639(
        { emptySet() },
        { B2bCodeListValuesExtended.LANGUAGE_ISO_639 },
    ),

    /**
     * Invoice line status code (EXTENDED-only). `cl` id — EN16931: n/a, EXTENDED: 6.
     */
    LINE_STATUS_CODE(
        { emptySet() },
        { B2bCodeListValuesExtended.LINE_STATUS_CODE },
    ),

    /**
     * Invoice line status reason code {DETAIL,GROUP,INFORMATION} (EXTENDED-only). `cl` id —
     * EN16931: n/a, EXTENDED: 7.
     */
    LINE_STATUS_REASON_CODE(
        { emptySet() },
        { B2bCodeListValuesExtended.LINE_STATUS_REASON_CODE },
    ),

    /**
     * ISO 6523 ICD, included/component product identifier scheme (EXTENDED-only
     * IncludedReferencedProduct). `cl` id — EN16931: n/a, EXTENDED: 14.
     */
    ITEM_COMPONENT_ID_SCHEME_ICD(
        { emptySet() },
        { B2bCodeListValuesExtended.ITEM_COMPONENT_ID_SCHEME_ICD },
    ),

    /**
     * Delivery/Incoterms code, line level (EXTENDED-only). `cl` id — EN16931: n/a, EXTENDED: 16.
     */
    DELIVERY_TERMS_CODE_LINE(
        { emptySet() },
        { B2bCodeListValuesExtended.DELIVERY_TERMS_CODE_LINE },
    ),

    /**
     * UNTDID 5189 allowance reason code, item gross price level (EXTENDED-only). `cl` id —
     * EN16931: n/a, EXTENDED: 21.
     */
    PRICE_ALLOWANCE_REASON_5189(
        { emptySet() },
        { B2bCodeListValuesExtended.PRICE_ALLOWANCE_REASON_5189 },
    ),

    /**
     * Charge reason code, item gross price level, alternate list (@listID='5153') (EXTENDED-only).
     * `cl` id — EN16931: n/a, EXTENDED: 22.
     */
    PRICE_CHARGE_REASON_ALT_5153(
        { emptySet() },
        { B2bCodeListValuesExtended.PRICE_CHARGE_REASON_ALT_5153 },
    ),

    /**
     * UNTDID 5153 fixed to 'VAT', item net price level (EXTENDED-only). `cl` id — EN16931: n/a,
     * EXTENDED: 23.
     */
    PRICE_TAX_TYPE_CODE(
        { emptySet() },
        { B2bCodeListValuesExtended.PRICE_TAX_TYPE_CODE },
    ),

    /**
     * Charge reason code, line level, alternate list (@listID='5153') (EXTENDED-only). `cl` id —
     * EN16931: n/a, EXTENDED: 30.
     */
    LINE_CHARGE_REASON_ALT_5153(
        { emptySet() },
        { B2bCodeListValuesExtended.LINE_CHARGE_REASON_ALT_5153 },
    ),

    /**
     * ISO 4217, line-level VAT total amount in invoice currency (EXTENDED-only). `cl` id —
     * EN16931: n/a, EXTENDED: 31.
     */
    LINE_TAX_TOTAL_CURRENCY_INVOICE(
        { emptySet() },
        { B2bCodeListValuesExtended.LINE_TAX_TOTAL_CURRENCY_INVOICE },
    ),

    /**
     * ISO 4217, line-level VAT total amount in tax accounting currency (EXTENDED-only). `cl` id —
     * EN16931: n/a, EXTENDED: 32.
     */
    LINE_TAX_TOTAL_CURRENCY_TAX(
        { emptySet() },
        { B2bCodeListValuesExtended.LINE_TAX_TOTAL_CURRENCY_TAX },
    ),

    /**
     * UNTDID 1001 subset, line-level preceding invoice reference document type (EXTENDED-only).
     * `cl` id — EN16931: n/a, EXTENDED: 33.
     */
    PRECEDING_INVOICE_LINE_DOC_TYPE(
        { emptySet() },
        { B2bCodeListValuesExtended.PRECEDING_INVOICE_LINE_DOC_TYPE },
    ),

    /**
     * Delivery/Incoterms code, header level (EXTENDED-only). `cl` id — EN16931: n/a, EXTENDED: 36.
     */
    DELIVERY_TERMS_CODE_HEADER(
        { emptySet() },
        { B2bCodeListValuesExtended.DELIVERY_TERMS_CODE_HEADER },
    ),

    /**
     * UNTDID 1153 reference qualifier code, ContractReferencedDocument (EXTENDED-only, fuller list
     * than BT-18/128's). `cl` id — EN16931: n/a, EXTENDED: 37.
     */
    CONTRACT_REFERENCE_QUALIFIER_1153(
        { emptySet() },
        { B2bCodeListValuesExtended.CONTRACT_REFERENCE_QUALIFIER_1153 },
    ),

    /**
     * UNTDID 8067 mode of transport code (EXTENDED-only). `cl` id — EN16931: n/a, EXTENDED: 41.
     */
    TRANSPORT_MODE_CODE(
        { emptySet() },
        { B2bCodeListValuesExtended.TRANSPORT_MODE_CODE },
    ),

    /**
     * Charge reason code, document level, alternate list (@listID='5153') (EXTENDED-only). `cl` id
     * — EN16931: n/a, EXTENDED: 47.
     */
    HEADER_CHARGE_REASON_ALT_5153(
        { emptySet() },
        { B2bCodeListValuesExtended.HEADER_CHARGE_REASON_ALT_5153 },
    ),

    /**
     * VATEX VAT exemption reason code, logistics service charge (EXTENDED-only). `cl` id —
     * EN16931: n/a, EXTENDED: 48.
     */
    LOGISTICS_CHARGE_VAT_EXEMPTION_REASON(
        { emptySet() },
        { B2bCodeListValuesExtended.LOGISTICS_CHARGE_VAT_EXEMPTION_REASON },
    ),

    /**
     * UNTDID 1001 subset, header-level preceding invoice reference document type (EXTENDED-only).
     * `cl` id — EN16931: n/a, EXTENDED: 51.
     */
    PRECEDING_INVOICE_HEADER_DOC_TYPE(
        { emptySet() },
        { B2bCodeListValuesExtended.PRECEDING_INVOICE_HEADER_DOC_TYPE },
    ),

    /**
     * Accounting account type code (EXTENDED-only). `cl` id — EN16931: n/a, EXTENDED: 52.
     */
    ACCOUNTING_ACCOUNT_TYPE_CODE(
        { emptySet() },
        { B2bCodeListValuesExtended.ACCOUNTING_ACCOUNT_TYPE_CODE },
    ),

    /**
     * UNTDID 1001 subset, advance payment reference document type (EXTENDED-only). `cl` id —
     * EN16931: n/a, EXTENDED: 53.
     */
    ADVANCE_PAYMENT_DOC_TYPE(
        { emptySet() },
        { B2bCodeListValuesExtended.ADVANCE_PAYMENT_DOC_TYPE },
    ),
    ;

    /**
     * The list's values for [profile] — [FacturXProfile.EN16931] unless requested otherwise.
     * EXTENDED-only lists return an empty set for [FacturXProfile.EN16931].
     */
    fun values(profile: FacturXProfile = FacturXProfile.EN16931): Set<String> =
        if (profile == FacturXProfile.EXTENDED) extendedValues() else en16931Values()

    /** Whether [value] is a member of this list under [profile]. */
    fun contains(value: String, profile: FacturXProfile = FacturXProfile.EN16931): Boolean =
        value in values(profile)
}
