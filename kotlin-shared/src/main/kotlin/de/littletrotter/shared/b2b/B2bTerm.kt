package de.littletrotter.shared.b2b

import de.littletrotter.shared.b2b.B2bFieldKind.DATE
import de.littletrotter.shared.b2b.B2bFieldKind.ENUM
import de.littletrotter.shared.b2b.B2bFieldKind.NUMBER
import de.littletrotter.shared.b2b.B2bFieldKind.TEXT
import de.littletrotter.shared.b2b.B2bTermBinding.BOUND
import de.littletrotter.shared.b2b.B2bTermBinding.FREE
import de.littletrotter.shared.b2b.B2bTermLevel.BUYER_PARTY
import de.littletrotter.shared.b2b.B2bTermLevel.HEADER
import de.littletrotter.shared.b2b.B2bTermLevel.LINE
import de.littletrotter.shared.b2b.B2bTermLevel.SELLER_PARTY
import de.littletrotter.shared.b2b.codes.B2bCodeList

private const val HEADER_TRADE_AGREEMENT_XPATH = "/rsm:CrossIndustryInvoice/rsm:SupplyChainTradeTransaction/ram:ApplicableHeaderTradeAgreement"

/**
 * The binding-target invariant [B2bTerm]'s `init` block enforces per constant, replacing
 * the DB CHECK `chk_b2b_field_catalog_binding_target`. A top-level function rather than inline
 * `init` logic so it stays unit-testable with deliberately invalid arguments — a fixed, correct
 * enum body alone can never exercise the failing branch of its own invariant check (every real
 * [B2bTerm] constant is valid by construction, the same reachability gap as `Money.allocate`'s
 * proven-dead rounding branch).
 *
 * Kept as its own function (rather than folding [assertB2bTermValueDomainInvariant] into this
 * one) so neither exceeds detekt's `LongParameterList` function threshold — two focused checks,
 * not one with seven parameters. [metadata] bundles the three catalog-metadata fields
 * ([B2bCatalogMetadata.xpath]/[B2bCatalogMetadata.boundProperty]/[B2bCatalogMetadata.maxLength])
 * that this invariant always checks together.
 *
 * @throws IllegalArgumentException if [binding]/[metadata] violate the rule (FREE requires
 *   xpath, BOUND requires boundProperty, null/reference requires neither plus no maxLength).
 */
internal fun assertB2bTermBindingInvariant(
    officialCode: String,
    binding: B2bTermBinding?,
    metadata: B2bCatalogMetadata,
) {
    when (binding) {
        FREE -> require(metadata.xpath != null) {
            "Catalog term $officialCode is FREE and therefore requires an xpath."
        }
        BOUND -> require(metadata.boundProperty != null) {
            "Catalog term $officialCode is BOUND and therefore requires a boundProperty."
        }
        null -> require(metadata.xpath == null && metadata.boundProperty == null && metadata.maxLength == null) {
            "Reference term $officialCode (binding = null) must not carry catalog metadata " +
                "(xpath/boundProperty/maxLength)."
        }
    }
}

/**
 * The CII syntax-binding metadata a catalog term carries together — [B2bTerm.xpath],
 * [B2bTerm.boundProperty] and [B2bTerm.maxLength] — checked as one unit by
 * [assertB2bTermBindingInvariant]. A real domain bundle (the "catalog metadata" the
 * invariant's own error messages already name), not a parameter-count workaround.
 */
internal data class B2bCatalogMetadata(
    val xpath: String?,
    val boundProperty: String?,
    val maxLength: Int?,
)

/**
 * The [B2bFieldKind.ENUM] / [B2bCodeList] value-domain invariant, the same "no dead
 * metadata, no missing metadata" shape [assertB2bTermBindingInvariant] already enforces for
 * `binding`, now covering the value-domain wiring that used to leave `ENUM` without a value
 * source: [valueDomain] is non-null if and only if [fieldKind] is [B2bFieldKind.ENUM].
 *
 * @throws IllegalArgumentException if [fieldKind]/[valueDomain] violate that rule.
 */
internal fun assertB2bTermValueDomainInvariant(
    officialCode: String,
    fieldKind: B2bFieldKind,
    valueDomain: B2bCodeList?,
) {
    if (fieldKind == ENUM) {
        require(valueDomain != null) {
            "Term $officialCode has fieldKind ENUM and therefore requires a valueDomain."
        }
    } else {
        require(valueDomain == null) {
            "Term $officialCode has fieldKind $fieldKind and must not carry a valueDomain " +
                "(only ENUM terms do)."
        }
    }
}

/**
 * The EN 16931 / ZUGFeRD business-term taxonomy as a tested Kotlin subdomain, replacing
 * the Flyway-seeded `b2b_field_catalog` table.
 *
 * The capture-oriented catalog covers the original terms formerly seeded into
 * `b2b_field_catalog` (transcribed verbatim from the V2 baseline seed's INSERT,
 * `kotlin-invoice/src/main/resources/db/migration/seed/V2__baseline_seed.sql`, section 7), plus
 * additional `BOUND` terms — former reference terms upgraded once a genuinely modeled
 * property was found for them.
 *
 * The scope widens to *every* business term of EN 16931,
 * Factur-X EXTENDED (`BT-X-nn`) and XRechnung (XRechnung's own CIUS defines no BTs beyond EN
 * 16931 — its coverage *is* the EN 16931 set). [catalogEligible] is therefore no longer simply
 * `binding != null`: almost every term is catalog-eligible now, whether or not it has a
 * [binding] —
 * - [B2bTermBinding.BOUND] / [B2bTermBinding.FREE] terms behave exactly as before:
 *   full capture/validation.
 * - A **pure reference term** ([binding] `null`, [catalogEligible] `true`) is still selectable —
 *   purely as documentation of the buyer's requirement, `OPTIONAL` only:
 *   `B2bProfileService.saveField` (kotlin-invoice) rejects `MANDATORY`/
 *   `CONDITIONAL`/`CONDITIONAL_IF_EMPTY` on one (a hard requirement the app can neither capture
 *   nor read would always fail — "kein unerfüllbares Pflichtfeld").
 * - [catalogExcluded] is the single deliberate carve-out from that otherwise-universal rule:
 *   [BT_151] must never be catalog-eligible regardless of [binding] — tax determination stays
 *   exclusively bound to `Article.defaultTaxRateRef`/`TaxRuleResolver`, never
 *   profile-influenceable (`B2bProfileMigrationIT`'s architectural guard).
 *
 * Replaces the DB CHECK `chk_b2b_field_catalog_binding_target` with the `init` block below —
 * enforcement moves from the schema into the domain model.
 *
 * **No display text lives here**: short/definition texts stay in the shared
 * `i18n.b2b-terms` resource bundles, resolved per locale via [B2bTermTexts] and addressed by
 * [nameKey]/[specDefinitionKey] — an enum-embedded string per language would sit on the heap
 * permanently instead of being loaded lazily per locale.
 */
enum class B2bTerm(
    /**
     * Official EN 16931 BT number — also the persisted smallint code, e.g. `19` for
     * `BT-19`. A future ZUGFeRD extension term (`BT-X-nn`) would reserve the block `10000 + nn`;
     * not yet allocated (README "Konventionen" — no extension term added yet).
     */
    val code: Int,
    val level: B2bTermLevel,
    val fieldKind: B2bFieldKind,
    /**
     * Non-null exactly for catalog terms — how a `B2bProfile` may pick this term. `null` = a
     * reference term: the code names it (compliance, export, SPI) but a profile can never select
     * it.
     */
    val binding: B2bTermBinding? = null,
    /**
     * CII syntax-binding path a FREE term is evidenced at. Documentational only — the actual
     * export mapping is typed per term in the Mustang mapping (`kotlin-xpdf/.../impl/mustang/`),
     * because element order in CII is schema-relevant and hanging a value in per XPath afterwards
     * would produce schema-invalid XML. Required for FREE, null for BOUND and for reference terms.
     */
    val xpath: String? = null,
    /**
     * JavaBean property of the existing field a BOUND term is already carried by. Required for
     * BOUND, null for FREE and for reference terms.
     */
    val boundProperty: String? = null,
    /** Value-length ceiling for FREE terms; null = unbounded / not applicable. */
    val maxLength: Int? = null,
    /**
     * The Factur-X code list this term's value must be a member of. Non-null if and only
     * if [fieldKind] is [B2bFieldKind.ENUM] (invariant enforced below); completes the previously
     * dead `ENUM` field kind with an actual value source for kotlin-invoice's
     * `B2bFieldWidgetFactory` searchable ComboBox.
     */
    val valueDomain: B2bCodeList? = null,
    /**
     * True for a term the application always populates on every generated invoice
     * (invoice number, issue date, type code, currency, seller master data guaranteed by
     * `SenderProfile.validate`, kotlin-invoice). Derived from the actual
     * generation path (`InvoiceDtoMapper`), never force-guessed: only set where the value is
     * demonstrably always present. Purely informational for an OPTIONAL-requirement field (the
     * picker's "vorbelegt" badge) — for a [B2bTermBinding.BOUND] term used as MANDATORY it also
     * explains *why* `B2bProfileRuleSet`'s presence check always passes without the user entering
     * anything, with no special-casing needed there (the term's own [binding]/[boundProperty]
     * already make it pass).
     */
    val autoFilled: Boolean = false,
    /**
     * True for a term that must never become [catalogEligible] regardless of [binding]:
     * today only [BT_151] (tax determination — `B2bProfileMigrationIT`'s architectural guard).
     * Every other term, bound or not, is catalog-eligible under this "cover every
     * standard term" scope; this is the single deliberate carve-out, not a general opt-out
     * mechanism.
     */
    val catalogExcluded: Boolean = false,
    /** Generated profile-availability metadata: false only for the EXTENDED-only plain
     *  `BT-nn` terms (173-216 range) and every `BT-X-nn` term; true for the 164 EN 16931 terms and
     *  the 35 previously curated ones. */
    val availableInEn16931: Boolean = true,
    /** Every term this database knows about is available in the EXTENDED profile (EN
     *  16931 is always a subset of EXTENDED); kept as an explicit field, not a hardcoded `true`
     *  getter, so a genuinely EXTENDED-absent term (none exist today) has somewhere to record it. */
    val availableInExtended: Boolean = true,
) {
    // ── Catalog terms — transcribed verbatim from the V2__baseline_seed.sql INSERT, ascending
    //    by code. Levels/kinds/bindings/lengths pin the seed values (guarded by
    //    B2bTermTranscriptionTest until the seed block is deleted). ─────────────────────────

    /**
     * Project reference. BOUND — the existing `projectNumber` property already *is*
     * BT-11, no second input is created.
     */
    BT_11(11, HEADER, TEXT, BOUND, boundProperty = "projectNumber"),

    /** Contract reference. */
    BT_12(
        12,
        HEADER,
        TEXT,
        FREE,
        xpath = HEADER_TRADE_AGREEMENT_XPATH +
            "/ram:ContractReferencedDocument/ram:IssuerAssignedID",
        maxLength = 100,
    ),

    /**
     * Purchase order reference. BOUND — already carried by the existing `purchaseOrderReference`
     * property; a profile can only mark it mandatory/optional, not capture a second value.
     */
    BT_13(13, HEADER, TEXT, BOUND, boundProperty = "purchaseOrderReference"),

    /**
     * Invoiced object identifier. Has no own element in CII: it is the
     * `AdditionalReferencedDocument` with `TypeCode` `130`.
     */
    BT_18(
        18,
        HEADER,
        TEXT,
        FREE,
        xpath = HEADER_TRADE_AGREEMENT_XPATH +
            "/ram:AdditionalReferencedDocument[ram:TypeCode='130']/ram:IssuerAssignedID",
        maxLength = 100,
    ),

    /**
     * Buyer accounting reference. The one FREE term Mustang 2.25.0 does not yet write on export:
     * it only knows `ReceivableSpecifiedTradeAccountingAccount` on the line level (BT-133), not on
     * the header — the xpath below still names the correct target, it documents the goal, not the
     * current implementation state.
     */
    BT_19(
        19,
        HEADER,
        TEXT,
        FREE,
        xpath = "/rsm:CrossIndustryInvoice/rsm:SupplyChainTradeTransaction/ram:ApplicableHeaderTradeSettlement" +
            "/ram:ReceivableSpecifiedTradeAccountingAccount/ram:ID",
        maxLength = 50,
    ),

    /** Buyer contact point (name of the contact person or department at the buyer). */
    BT_56(
        56,
        HEADER,
        TEXT,
        FREE,
        xpath = HEADER_TRADE_AGREEMENT_XPATH +
            "/ram:BuyerTradeParty/ram:DefinedTradeContact/ram:PersonName",
        maxLength = 100,
    ),

    /** Buyer contact email address. */
    BT_58(
        58,
        HEADER,
        TEXT,
        FREE,
        xpath = HEADER_TRADE_AGREEMENT_XPATH +
            "/ram:BuyerTradeParty/ram:DefinedTradeContact/ram:EmailURIUniversalCommunication/ram:URIID",
        maxLength = 100,
    ),

    /** Actual delivery date. BOUND — already carried by the existing `deliveryDate` property. */
    BT_72(72, HEADER, DATE, BOUND, boundProperty = "deliveryDate"),

    /**
     * Invoicing period start date. BOUND — already carried by the existing `servicePeriodFrom`
     * property.
     */
    BT_73(73, HEADER, DATE, BOUND, boundProperty = "servicePeriodFrom"),

    /**
     * Invoicing period end date. BOUND — already carried by the existing `servicePeriodTo`
     * property.
     */
    BT_74(74, HEADER, DATE, BOUND, boundProperty = "servicePeriodTo"),

    /** Payment account identifier (IBAN). BOUND — already carried by the existing `iban` property. */
    BT_84(84, SELLER_PARTY, TEXT, BOUND, boundProperty = "iban"),

    /**
     * Invoice line period start date. BOUND — line-level counterpart of BT-73, already carried by
     * the line's `servicePeriodFrom` property.
     */
    BT_134(134, LINE, DATE, BOUND, boundProperty = "servicePeriodFrom"),

    /**
     * Invoice line period end date. BOUND — line-level counterpart of BT-74, already carried by
     * the line's `servicePeriodTo` property.
     */
    BT_135(135, LINE, DATE, BOUND, boundProperty = "servicePeriodTo"),

    /**
     * Item Seller's item identifier. FREE at line level — `invoice_item` has no seller/buyer
     * article number of its own.
     */
    BT_155(
        155,
        LINE,
        TEXT,
        FREE,
        xpath = "/rsm:CrossIndustryInvoice/rsm:SupplyChainTradeTransaction/ram:IncludedSupplyChainTradeLineItem" +
            "/ram:SpecifiedTradeProduct/ram:SellerAssignedID",
        maxLength = 50,
    ),

    /**
     * Item Buyer's item identifier. FREE at line level — `invoice_item` has no seller/buyer
     * article number of its own.
     */
    BT_156(
        156,
        LINE,
        TEXT,
        FREE,
        xpath = "/rsm:CrossIndustryInvoice/rsm:SupplyChainTradeTransaction/ram:IncludedSupplyChainTradeLineItem" +
            "/ram:SpecifiedTradeProduct/ram:BuyerAssignedID",
        maxLength = 50,
    ),

    // ── Additional catalog terms, ascending by code. Former reference terms upgraded to
    //    BOUND once a genuinely modeled property was verified against the actual domain entity at
    //    the term's own [B2bTermLevel] (Invoice/InvoiceReferences for HEADER, the seller entity
    //    behind `invoice.effectiveSender()` for SELLER_PARTY, Recipient for BUYER_PARTY,
    //    InvoiceItem for LINE) — no capture widget added anywhere, [B2bProfileRuleSet] (kotlin-
    //    invoice) already reads a BOUND term's value straight off that property via reflection. ──

    /** Invoice number. BOUND — already carried by the existing `invoiceNumber` property.
     *  `autoFilled`: assigned by the number-range mechanism on every invoice, never blank. */
    BT_1(1, HEADER, TEXT, BOUND, boundProperty = "invoiceNumber", autoFilled = true),

    /** Invoice issue date. BOUND — already carried by the existing `invoiceDate` property.
     *  `autoFilled`: set on every generated/finalized invoice. */
    BT_2(2, HEADER, DATE, BOUND, boundProperty = "invoiceDate", autoFilled = true),

    /**
     * Invoice type code. BOUND — already carried by the existing `typeCode` property
     * (`InvoiceTypeCode`, non-nullable, defaults to `COMMERCIAL_INVOICE`) — never a new binding
     * decision, `InvoiceDtoMapper.mapToDto` already reads `inv.typeCode.code` onto the wire today.
     * `autoFilled` — always set, never blank.
     */
    BT_3(3, HEADER, ENUM, BOUND, boundProperty = "typeCode", valueDomain = B2bCodeList.DOCUMENT_TYPE_1001, autoFilled = true),

    /**
     * Invoice currency code. BOUND — already carried by the existing `currency` property
     * (`Currency`, non-nullable, defaults to `EUR`); `InvoiceDtoMapper.mapToDto` already reads
     * `inv.currency.ordinal` onto the wire today. `autoFilled` — always set, never blank.
     */
    BT_5(5, HEADER, ENUM, BOUND, boundProperty = "currency", valueDomain = B2bCodeList.CURRENCY_ISO_4217, autoFilled = true),

    /**
     * Buyer reference. BOUND — already carried by the existing `buyerReference` property.
     * Lives on `Invoice.references` (`InvoiceReferences`), resolved through the same embeddable
     * fallback as [BT_11]/[BT_13] (`B2bProfileRuleSet.readBoundProperty`).
     */
    BT_10(10, HEADER, TEXT, BOUND, boundProperty = "buyerReference"),

    /**
     * Preceding Invoice reference. BOUND — already carried by the existing
     * `originalInvoiceNumber` property (set only on a credit note, `Invoice.adoptPartiesAndTax
     * ContextFrom`'s sibling assignment).
     */
    BT_25(25, HEADER, TEXT, BOUND, boundProperty = "originalInvoiceNumber"),

    /** Seller name. BOUND — already carried by the existing `SenderProfile.name` property.
     *  `autoFilled`: `SenderProfile.validate()` rejects a blank name unconditionally. */
    BT_27(27, SELLER_PARTY, TEXT, BOUND, boundProperty = "name", autoFilled = true),

    /**
     * Seller identifier. BOUND — already carried by the existing
     * `SenderProfile.sellerIdentifier` property.
     */
    BT_29(29, SELLER_PARTY, TEXT, BOUND, boundProperty = "sellerIdentifier"),

    /**
     * Seller VAT identifier. BOUND — already carried by the existing
     * `SenderProfile.vatId` property. `autoFilled`: `SenderProfile.validate()` rejects a
     * blank VAT id unconditionally.
     */
    BT_31(31, SELLER_PARTY, TEXT, BOUND, boundProperty = "vatId", autoFilled = true),

    /**
     * Seller tax registration identifier. BOUND — already carried by the existing
     * `SenderProfile.taxNumber` property (the German Steuernummer, distinct from [BT_31]'s VAT
     * identifier). `autoFilled`: `SenderProfile.validate()` rejects a blank tax number
     * unconditionally.
     */
    BT_32(32, SELLER_PARTY, TEXT, BOUND, boundProperty = "taxNumber", autoFilled = true),

    /**
     * Seller electronic address. BOUND — already carried by the existing
     * `SenderProfile.electronicAddress` property. Not [BT_49], the buyer's counterpart.
     */
    BT_34(34, SELLER_PARTY, TEXT, BOUND, boundProperty = "electronicAddress"),

    /**
     * Seller contact email address. BOUND — already carried by the existing
     * `SenderProfile.email` property. `autoFilled`: `SenderProfile.validate()` rejects a
     * blank email unconditionally.
     */
    BT_43(43, SELLER_PARTY, TEXT, BOUND, boundProperty = "email", autoFilled = true),

    /** Buyer name. BOUND — already carried by the existing `Recipient.name` property. */
    BT_44(44, BUYER_PARTY, TEXT, BOUND, boundProperty = "name"),

    /**
     * Buyer identifier. BOUND — already carried by the existing
     * `Recipient.buyerIdentifier` property.
     */
    BT_46(46, BUYER_PARTY, TEXT, BOUND, boundProperty = "buyerIdentifier"),

    /**
     * Buyer VAT identifier. BOUND — already carried by the existing
     * `Recipient.vatId` property.
     */
    BT_48(48, BUYER_PARTY, TEXT, BOUND, boundProperty = "vatId"),

    /**
     * Buyer electronic address. BOUND — already carried by the existing
     * `Recipient.electronicAddress` property — not `Recipient.routingId`/[BT_10], the
     * historical mix-up that property's own KDoc warns against.
     */
    BT_49(49, BUYER_PARTY, TEXT, BOUND, boundProperty = "electronicAddress"),

    /**
     * Invoiced quantity. BOUND — already carried by the existing
     * `InvoiceItem.quantity` property.
     */
    BT_129(129, LINE, NUMBER, BOUND, boundProperty = "quantity"),

    /**
     * Invoiced quantity unit of measure code (UN/ECE Recommendation 20). BOUND — already
     * carried by the existing, derived `InvoiceItem.unitCode` property (never missing — derived
     * from the mandatory `unit` field, see that property's own KDoc).
     */
    BT_130(130, LINE, ENUM, BOUND, boundProperty = "unitCode", valueDomain = B2bCodeList.UNIT_UNECE_REC20),

    /** Item name. BOUND — already carried by the existing `InvoiceItem.description` property. */
    BT_153(153, LINE, TEXT, BOUND, boundProperty = "description"),

    /**
     * Item description. BOUND — already carried by the existing
     * `InvoiceItem.itemDescription` property. Formerly deliberately reference-only over a worry
     * that catalog-eligibility would hide the always-rendered description field behind the B2B
     * feature gate — moot: a BOUND term never gets a capture widget at
     * all, so this only lets a profile *require* the field to be filled, no second input appears.
     */
    BT_154(154, LINE, TEXT, BOUND, boundProperty = "itemDescription"),

    // ── Reference terms — binding = null. The code already names these BTs today (compliance
    //    rules, form SPI, export); consumers move to these constants. BT_16 is
    //    catalogEligible too (pure-reference, OPTIONAL-only) — only BT_151 stays excluded.

    /**
     * Despatch advice reference. Deliberately kept `binding = null` (no modeled property): its
     * overlap with an existing field is an open business clarification point, not yet decided —
     * binding it would pre-empt that decision. The only candidate property,
     * `InvoiceItem.deliveryNoteReference`, sits at [B2bTermLevel.LINE], not this term's
     * [B2bTermLevel.HEADER] — binding it would require moving the term's level, out of
     * scope (levels stay untouched). `catalogEligible` regardless (pure reference,
     * documentation only, `OPTIONAL` requirement only).
     */
    BT_16(16, HEADER, TEXT),

    /**
     * Invoiced item VAT category code. Has a genuinely modeled property
     * (`InvoiceItem.taxCategory`) but deliberately stays `binding = null` *and*
     * [catalogExcluded]: `B2bProfileMigrationIT`'s hard architectural guard against a catalog
     * term ever determining tax keeps
     * tax determination exclusively bound to `Article.defaultTaxRateRef`/`TaxRuleResolver`, never
     * profile-influenceable — catalog-eligibility itself is what that guard forbids, independent
     * of binding kind. The one deliberate carve-out from "every term is catalog-eligible
     * now" (see the class KDoc); [B2bTerm.catalogExcluded] exists for exactly this case.
     */
    BT_151(151, LINE, ENUM, valueDomain = B2bCodeList.VAT_CATEGORY_5305, catalogExcluded = true),

    // GENERATED-TERMS-BEGIN — do not hand-edit; regenerate via scripts/codegen/facturx_termdb_gen.py
    //   746 terms — every EN 16931 / EXTENDED business term not already hand-curated above; pure reference (binding = null), catalogEligible, OPTIONAL-requirement-only (B2bProfileService.saveField).
    /** VAT accounting currency code (BT-6). Generated — BTE-7. */
    BT_6(6, HEADER, TEXT),

    /** Value added tax point date (BT-7). Generated — BTE-7. */
    BT_7(7, HEADER, DATE),

    /** Value added tax point date code (BT-8). Generated — BTE-7. */
    BT_8(8, HEADER, TEXT),

    /** Payment due date (BT-9). Generated — BTE-7. */
    BT_9(9, HEADER, DATE),

    /** Sales order reference (BT-14). Generated — BTE-7. */
    BT_14(14, HEADER, TEXT),

    /** Receiving advice reference (BT-15). Generated — BTE-7. */
    BT_15(15, HEADER, TEXT),

    /** Tender or lot reference (BT-17). Generated — BTE-7. */
    BT_17(17, HEADER, TEXT),

    /** Payment terms (BT-20). Generated — BTE-7. */
    BT_20(20, HEADER, TEXT),

    /** Invoice note subject code (BT-21). Generated — BTE-7. */
    BT_21(21, HEADER, TEXT),

    /** Invoice note (BT-22). Generated — BTE-7. */
    BT_22(22, HEADER, TEXT),

    /** Business process type (BT-23). Generated — BTE-7. */
    BT_23(23, HEADER, TEXT),

    /** Specification identifier (BT-24). Generated — BTE-7. */
    BT_24(24, HEADER, TEXT),

    /** Preceding Invoice issue date (BT-26). Generated — BTE-7. */
    BT_26(26, HEADER, DATE),

    /** Seller trading name (BT-28). Generated — BTE-7. */
    BT_28(28, SELLER_PARTY, TEXT),

    /** Seller legal registration identifier (BT-30). Generated — BTE-7. */
    BT_30(30, SELLER_PARTY, TEXT),

    /** Seller additional legal information (BT-33). Generated — BTE-7. */
    BT_33(33, SELLER_PARTY, TEXT),

    /** Seller address line 1 (BT-35). Generated — BTE-7. */
    BT_35(35, SELLER_PARTY, TEXT),

    /** Seller address line 2 (BT-36). Generated — BTE-7. */
    BT_36(36, SELLER_PARTY, TEXT),

    /** Seller city (BT-37). Generated — BTE-7. */
    BT_37(37, SELLER_PARTY, TEXT),

    /** Seller post code (BT-38). Generated — BTE-7. */
    BT_38(38, SELLER_PARTY, TEXT),

    /** Seller country subdivision (BT-39). Generated — BTE-7. */
    BT_39(39, SELLER_PARTY, TEXT),

    /** Seller country code (BT-40). Generated — BTE-7. */
    BT_40(40, SELLER_PARTY, TEXT),

    /** Seller contact point (BT-41). Generated — BTE-7. */
    BT_41(41, SELLER_PARTY, TEXT),

    /** Seller contact telephone number (BT-42). Generated — BTE-7. */
    BT_42(42, SELLER_PARTY, TEXT),

    /** Buyer trading name (BT-45). Generated — BTE-7. */
    BT_45(45, BUYER_PARTY, TEXT),

    /** Buyer legal registration identifier (BT-47). Generated — BTE-7. */
    BT_47(47, BUYER_PARTY, TEXT),

    /** Buyer address line 1 (BT-50). Generated — BTE-7. */
    BT_50(50, BUYER_PARTY, TEXT),

    /** Buyer address line 2 (BT-51). Generated — BTE-7. */
    BT_51(51, BUYER_PARTY, TEXT),

    /** Buyer city (BT-52). Generated — BTE-7. */
    BT_52(52, BUYER_PARTY, TEXT),

    /** Buyer post code (BT-53). Generated — BTE-7. */
    BT_53(53, BUYER_PARTY, TEXT),

    /** Buyer country subdivision (BT-54). Generated — BTE-7. */
    BT_54(54, BUYER_PARTY, TEXT),

    /** Buyer country code (BT-55). Generated — BTE-7. */
    BT_55(55, BUYER_PARTY, TEXT),

    /** Buyer contact telephone number (BT-57). Generated — BTE-7. */
    BT_57(57, BUYER_PARTY, TEXT),

    /** Payee name (BT-59). Generated — BTE-7. */
    BT_59(59, HEADER, TEXT),

    /** Payee identifier (BT-60). Generated — BTE-7. */
    BT_60(60, HEADER, TEXT),

    /** Payee legal registration identifier (BT-61). Generated — BTE-7. */
    BT_61(61, HEADER, TEXT),

    /** Seller tax representative name (BT-62). Generated — BTE-7. */
    BT_62(62, HEADER, TEXT),

    /** Seller tax representative VAT identifier (BT-63). Generated — BTE-7. */
    BT_63(63, HEADER, TEXT),

    /** Tax representative address line 1 (BT-64). Generated — BTE-7. */
    BT_64(64, HEADER, TEXT),

    /** Tax representative address line 2 (BT-65). Generated — BTE-7. */
    BT_65(65, HEADER, TEXT),

    /** Tax representative city (BT-66). Generated — BTE-7. */
    BT_66(66, HEADER, TEXT),

    /** Tax representative post code (BT-67). Generated — BTE-7. */
    BT_67(67, HEADER, TEXT),

    /** Tax representative country subdivision (BT-68). Generated — BTE-7. */
    BT_68(68, HEADER, TEXT),

    /** Tax representative country code (BT-69). Generated — BTE-7. */
    BT_69(69, HEADER, TEXT),

    /** Deliver to party name (BT-70). Generated — BTE-7. */
    BT_70(70, HEADER, TEXT),

    /** Deliver to location identifier (BT-71). Generated — BTE-7. */
    BT_71(71, HEADER, TEXT),

    /** Deliver to address line 1 (BT-75). Generated — BTE-7. */
    BT_75(75, HEADER, TEXT),

    /** Deliver to address line 2 (BT-76). Generated — BTE-7. */
    BT_76(76, HEADER, TEXT),

    /** Deliver to city (BT-77). Generated — BTE-7. */
    BT_77(77, HEADER, TEXT),

    /** Deliver to post code (BT-78). Generated — BTE-7. */
    BT_78(78, HEADER, TEXT),

    /** Deliver to country subdivision (BT-79). Generated — BTE-7. */
    BT_79(79, HEADER, TEXT),

    /** Deliver to country code (BT-80). Generated — BTE-7. */
    BT_80(80, HEADER, TEXT),

    /** Payment means type code (BT-81). Generated — BTE-7. */
    BT_81(81, HEADER, TEXT),

    /** Payment means text (BT-82). Generated — BTE-7. */
    BT_82(82, HEADER, TEXT),

    /** Remittance information (BT-83). Generated — BTE-7. */
    BT_83(83, HEADER, TEXT),

    /** Payment account name (BT-85). Generated — BTE-7. */
    BT_85(85, HEADER, TEXT),

    /** Payment service provider identifier (BT-86). Generated — BTE-7. */
    BT_86(86, HEADER, TEXT),

    /** Payment card primary account number (BT-87). Generated — BTE-7. */
    BT_87(87, HEADER, TEXT),

    /** Payment card holder name (BT-88). Generated — BTE-7. */
    BT_88(88, HEADER, TEXT),

    /** Mandate reference identifier (BT-89). Generated — BTE-7. */
    BT_89(89, HEADER, TEXT),

    /** Bank assigned creditor identifier (BT-90). Generated — BTE-7. */
    BT_90(90, HEADER, TEXT),

    /** Debited account identifier (BT-91). Generated — BTE-7. */
    BT_91(91, HEADER, TEXT),

    /** Document level allowance amount (BT-92). Generated — BTE-7. */
    BT_92(92, HEADER, NUMBER),

    /** Document level allowance base amount (BT-93). Generated — BTE-7. */
    BT_93(93, HEADER, NUMBER),

    /** Document level allowance percentage (BT-94). Generated — BTE-7. */
    BT_94(94, HEADER, NUMBER),

    /** Document level allowance VAT category code (BT-95). Generated — BTE-7. */
    BT_95(95, HEADER, TEXT),

    /** Document level allowance VAT rate (BT-96). Generated — BTE-7. */
    BT_96(96, HEADER, NUMBER),

    /** Document level allowance reason (BT-97). Generated — BTE-7. */
    BT_97(97, HEADER, TEXT),

    /** Document level allowance reason code (BT-98). Generated — BTE-7. */
    BT_98(98, HEADER, TEXT),

    /** Document level charge amount (BT-99). Generated — BTE-7. */
    BT_99(99, HEADER, NUMBER),

    /** Document level charge base amount (BT-100). Generated — BTE-7. */
    BT_100(100, HEADER, NUMBER),

    /** Document level charge percentage (BT-101). Generated — BTE-7. */
    BT_101(101, HEADER, NUMBER),

    /** Document level charge VAT category code (BT-102). Generated — BTE-7. */
    BT_102(102, HEADER, TEXT),

    /** Document level charge VAT rate (BT-103). Generated — BTE-7. */
    BT_103(103, HEADER, NUMBER),

    /** Document level charge reason (BT-104). Generated — BTE-7. */
    BT_104(104, HEADER, TEXT),

    /** Document level charge reason code (BT-105). Generated — BTE-7. */
    BT_105(105, HEADER, TEXT),

    /** Sum of Invoice line net amount (BT-106). Generated — BTE-7. */
    BT_106(106, HEADER, NUMBER),

    /** Sum of allowances on document level (BT-107). Generated — BTE-7. */
    BT_107(107, HEADER, NUMBER),

    /** Sum of charges on document level (BT-108). Generated — BTE-7. */
    BT_108(108, HEADER, NUMBER),

    /** Invoice total amount without VAT (BT-109). Generated — BTE-7. */
    BT_109(109, HEADER, NUMBER),

    /** Invoice total VAT amount (BT-110). Generated — BTE-7. */
    BT_110(110, HEADER, NUMBER),

    /** Invoice total VAT amount in accounting currency (BT-111). Generated — BTE-7. */
    BT_111(111, HEADER, NUMBER),

    /** Invoice total amount with VAT (BT-112). Generated — BTE-7. */
    BT_112(112, HEADER, NUMBER),

    /** Paid amount (BT-113). Generated — BTE-7. */
    BT_113(113, HEADER, NUMBER),

    /** Rounding amount (BT-114). Generated — BTE-7. */
    BT_114(114, HEADER, NUMBER),

    /** Amount due for payment (BT-115). Generated — BTE-7. */
    BT_115(115, HEADER, NUMBER),

    /** VAT category taxable amount (BT-116). Generated — BTE-7. */
    BT_116(116, HEADER, NUMBER),

    /** VAT category tax amount (BT-117). Generated — BTE-7. */
    BT_117(117, HEADER, NUMBER),

    /** VAT category code (BT-118). Generated — BTE-7. */
    BT_118(118, HEADER, TEXT),

    /** VAT category rate (BT-119). Generated — BTE-7. */
    BT_119(119, HEADER, NUMBER),

    /** VAT exemption reason text (BT-120). Generated — BTE-7. */
    BT_120(120, HEADER, TEXT),

    /** VAT exemption reason code (BT-121). Generated — BTE-7. */
    BT_121(121, HEADER, TEXT),

    /** Supporting document reference (BT-122). Generated — BTE-7. */
    BT_122(122, HEADER, TEXT),

    /** Supporting document description (BT-123). Generated — BTE-7. */
    BT_123(123, HEADER, TEXT),

    /** External document location (BT-124). Generated — BTE-7. */
    BT_124(124, HEADER, TEXT),

    /** Attached document (BT-125). Generated — BTE-7. */
    BT_125(125, HEADER, TEXT),

    /** Invoice line identifier (BT-126). Generated — BTE-7. */
    BT_126(126, LINE, TEXT),

    /** Invoice line note (BT-127). Generated — BTE-7. */
    BT_127(127, LINE, TEXT),

    /** Invoice line object identifier (BT-128). Generated — BTE-7. */
    BT_128(128, LINE, TEXT),

    /** Invoice line net amount (BT-131). Generated — BTE-7. */
    BT_131(131, LINE, NUMBER),

    /** Referenced purchase order line reference (BT-132). Generated — BTE-7. */
    BT_132(132, LINE, TEXT),

    /** Invoice line Buyer accounting reference (BT-133). Generated — BTE-7. */
    BT_133(133, LINE, TEXT),

    /** Invoice line allowance amount (BT-136). Generated — BTE-7. */
    BT_136(136, LINE, NUMBER),

    /** Invoice line allowance base amount (BT-137). Generated — BTE-7. */
    BT_137(137, LINE, NUMBER),

    /** Invoice line allowance percentage (BT-138). Generated — BTE-7. */
    BT_138(138, LINE, NUMBER),

    /** Invoice line allowance reason (BT-139). Generated — BTE-7. */
    BT_139(139, LINE, TEXT),

    /** Invoice line allowance reason code (BT-140). Generated — BTE-7. */
    BT_140(140, LINE, TEXT),

    /** Invoice line charge amount (BT-141). Generated — BTE-7. */
    BT_141(141, LINE, NUMBER),

    /** Invoice line charge base amount (BT-142). Generated — BTE-7. */
    BT_142(142, LINE, NUMBER),

    /** Invoice line charge percentage (BT-143). Generated — BTE-7. */
    BT_143(143, LINE, NUMBER),

    /** Invoice line charge reason (BT-144). Generated — BTE-7. */
    BT_144(144, LINE, TEXT),

    /** Invoice line charge reason code (BT-145). Generated — BTE-7. */
    BT_145(145, LINE, TEXT),

    /** Item net price (BT-146). Generated — BTE-7. */
    BT_146(146, LINE, NUMBER),

    /** Item price discount (BT-147). Generated — BTE-7. */
    BT_147(147, LINE, NUMBER),

    /** Item gross price (BT-148). Generated — BTE-7. */
    BT_148(148, LINE, NUMBER),

    /** Item price base quantity (BT-149). Generated — BTE-7. */
    BT_149(149, LINE, NUMBER),

    /** Item price base quantity unit of measure code (BT-150). Generated — BTE-7. */
    BT_150(150, LINE, TEXT),

    /** Item standard identifier (BT-157). Generated — BTE-7. */
    BT_157(157, LINE, TEXT),

    /** Item classification identifier (BT-158). Generated — BTE-7. */
    BT_158(158, LINE, TEXT),

    /** Item country of origin (BT-159). Generated — BTE-7. */
    BT_159(159, LINE, TEXT),

    /** Item attribute name (BT-160). Generated — BTE-7. */
    BT_160(160, LINE, TEXT),

    /** Item attribute value (BT-161). Generated — BTE-7. */
    BT_161(161, LINE, TEXT),

    /** Seller address line 3 (BT-162). Generated — BTE-7. */
    BT_162(162, SELLER_PARTY, TEXT),

    /** Buyer address line 3 (BT-163). Generated — BTE-7. */
    BT_163(163, BUYER_PARTY, TEXT),

    /** Tax representative address line 3 (BT-164). Generated — BTE-7. */
    BT_164(164, HEADER, TEXT),

    /** Deliver to address line 3 (BT-165). Generated — BTE-7. */
    BT_165(165, HEADER, TEXT),

    /** Document level allowance exemption reason text (BT-173). Generated — BTE-7. */
    BT_173(173, HEADER, TEXT, availableInEn16931 = false),

    /** Document level allowance VAT exemption reason and specification code (BT-174). Generated — BTE-7. */
    BT_174(174, HEADER, TEXT, availableInEn16931 = false),

    /** VAT Exemption Reason  for document level charges (BT-175). Generated — BTE-7. */
    BT_175(175, HEADER, TEXT, availableInEn16931 = false),

    /** VAT Exemption Reason Code for document level charges (BT-176). Generated — BTE-7. */
    BT_176(176, HEADER, TEXT, availableInEn16931 = false),

    /** Document level non-VAT tax code (BT-177). Generated — BTE-7. */
    BT_177(177, HEADER, TEXT, availableInEn16931 = false),

    /** Third party payment amount (BT-179). Generated — BTE-7. */
    BT_179(179, HEADER, TEXT, availableInEn16931 = false),

    /** Third party payment description (BT-180). Generated — BTE-7. */
    BT_180(180, HEADER, TEXT, availableInEn16931 = false),

    /** Goods recipient global identifier (BT-186). Generated — BTE-7. */
    BT_186(186, LINE, TEXT, availableInEn16931 = false),

    /** Invoice line-level non-VAT tax code (BT-193). Generated — BTE-7. */
    BT_193(193, LINE, TEXT, availableInEn16931 = false),

    /** Debited account payment service provider identifier (BT-215). Generated — BTE-7. */
    BT_215(215, HEADER, TEXT, availableInEn16931 = false),

    /** Debited account name (BT-216). Generated — BTE-7. */
    BT_216(216, HEADER, TEXT, availableInEn16931 = false),

    /** Test Indicator, value (BT-X-1). Generated — BTE-7. */
    BT_X_1(10001, HEADER, TEXT, availableInEn16931 = false),

    /** Document name (BT-X-2). Generated — BTE-7. */
    BT_X_2(10002, HEADER, TEXT, availableInEn16931 = false),

    /** Copy indicator, value (BT-X-3). Generated — BTE-7. */
    BT_X_3(10003, HEADER, TEXT, availableInEn16931 = false),

    /** Invoice language code (BT-X-4). Generated — BTE-7. */
    BT_X_4(10004, HEADER, TEXT, availableInEn16931 = false),

    /** Free text on header level (qualifying the content) (BT-X-5). Generated — BTE-7. */
    BT_X_5(10005, HEADER, TEXT, availableInEn16931 = false),

    /** Contractual due date of the invoice, value (BT-X-6). Generated — BTE-7. */
    BT_X_6(10006, HEADER, DATE, availableInEn16931 = false),

    /** Type of the inovice line item (Code) (BT-X-7). Generated — BTE-7. */
    BT_X_7(10007, LINE, TEXT, availableInEn16931 = false),

    /** Subtype of the invoice line item (BT-X-8). Generated — BTE-7. */
    BT_X_8(10008, LINE, TEXT, availableInEn16931 = false),

    /** Free text on line level (code) (BT-X-9). Generated — BTE-7. */
    BT_X_9(10009, LINE, TEXT, availableInEn16931 = false),

    /** Invoice line note subject code (BT-X-10). Generated — BTE-7. */
    BT_X_10(10010, LINE, TEXT, availableInEn16931 = false),

    /** Item Attribute Type (Code) (BT-X-11). Generated — BTE-7. */
    BT_X_11(10011, LINE, TEXT, availableInEn16931 = false),

    /** Item Attribute Value (numerical measurand) (BT-X-12). Generated — BTE-7. */
    BT_X_12(10012, LINE, NUMBER, availableInEn16931 = false),

    /** Classification name (BT-X-13). Generated — BTE-7. */
    BT_X_13(10013, LINE, TEXT, availableInEn16931 = false),

    /** Global ID of Included Referenced Product (BT-X-15). Generated — BTE-7. */
    BT_X_15(10015, LINE, TEXT, availableInEn16931 = false),

    /** SellerAssignedID of Included Referenced Product (BT-X-16). Generated — BTE-7. */
    BT_X_16(10016, LINE, TEXT, availableInEn16931 = false),

    /** BuyerAssignedID of Included Referenced Product (BT-X-17). Generated — BTE-7. */
    BT_X_17(10017, LINE, TEXT, availableInEn16931 = false),

    /** Name of Included Referenced Product (BT-X-18). Generated — BTE-7. */
    BT_X_18(10018, LINE, TEXT, availableInEn16931 = false),

    /** Description of Included Referenced Product (BT-X-19). Generated — BTE-7. */
    BT_X_19(10019, LINE, TEXT, availableInEn16931 = false),

    /** UnitQuantity of Included Referenced Product (BT-X-20). Generated — BTE-7. */
    BT_X_20(10020, LINE, NUMBER, availableInEn16931 = false),

    /** Order Id (BT-X-21). Generated — BTE-7. */
    BT_X_21(10021, LINE, TEXT, availableInEn16931 = false),

    /** Order date, value (BT-X-22). Generated — BTE-7. */
    BT_X_22(10022, LINE, DATE, availableInEn16931 = false),

    /** Contract number (BT-X-24). Generated — BTE-7. */
    BT_X_24(10024, LINE, TEXT, availableInEn16931 = false),

    /** Contract position (BT-X-25). Generated — BTE-7. */
    BT_X_25(10025, LINE, TEXT, availableInEn16931 = false),

    /** Contract Date, value (BT-X-26). Generated — BTE-7. */
    BT_X_26(10026, LINE, DATE, availableInEn16931 = false),

    /** Document number (BT-X-27). Generated — BTE-7. */
    BT_X_27(10027, LINE, TEXT, availableInEn16931 = false),

    /** External document location (BT-X-28). Generated — BTE-7. */
    BT_X_28(10028, LINE, TEXT, availableInEn16931 = false),

    /** Referenced position (BT-X-29). Generated — BTE-7. */
    BT_X_29(10029, LINE, TEXT, availableInEn16931 = false),

    /** Type of the document (code) (BT-X-30). Generated — BTE-7. */
    BT_X_30(10030, LINE, TEXT, availableInEn16931 = false),

    /** Attached document (BT-X-31). Generated — BTE-7. */
    BT_X_31(10031, LINE, TEXT, availableInEn16931 = false),

    /** Type of the document reference (code) (BT-X-32). Generated — BTE-7. */
    BT_X_32(10032, LINE, TEXT, availableInEn16931 = false),

    /** Document date, value (BT-X-33). Generated — BTE-7. */
    BT_X_33(10033, LINE, DATE, availableInEn16931 = false),

    /** Discount in percent (BT-X-34). Generated — BTE-7. */
    BT_X_34(10034, LINE, NUMBER, availableInEn16931 = false),

    /** Discount base amount (BT-X-35). Generated — BTE-7. */
    BT_X_35(10035, LINE, NUMBER, availableInEn16931 = false),

    /** Reason for the discount (free text) (BT-X-36). Generated — BTE-7. */
    BT_X_36(10036, LINE, TEXT, availableInEn16931 = false),

    /** Included tax for B2C (BT-X-37). Generated — BTE-7. */
    BT_X_37(10037, LINE, NUMBER, availableInEn16931 = false),

    /** Type of tax (code) (BT-X-38). Generated — BTE-7. */
    BT_X_38(10038, LINE, TEXT, availableInEn16931 = false),

    /** VAT exemption reason text (BT-X-39). Generated — BTE-7. */
    BT_X_39(10039, LINE, TEXT, availableInEn16931 = false),

    /** VAT category code (BT-X-40). Generated — BTE-7. */
    BT_X_40(10040, LINE, TEXT, availableInEn16931 = false),

    /** VAT exemption reason code (BT-X-41). Generated — BTE-7. */
    BT_X_41(10041, LINE, TEXT, availableInEn16931 = false),

    /** VAT category rate (BT-X-42). Generated — BTE-7. */
    BT_X_42(10042, LINE, NUMBER, availableInEn16931 = false),

    /** Order number of the final customer (BT-X-43). Generated — BTE-7. */
    BT_X_43(10043, LINE, TEXT, availableInEn16931 = false),

    /** Order item (ultimate customer) (BT-X-44). Generated — BTE-7. */
    BT_X_44(10044, LINE, TEXT, availableInEn16931 = false),

    /** Document date, value (BT-X-45). Generated — BTE-7. */
    BT_X_45(10045, LINE, DATE, availableInEn16931 = false),

    /** Quantity, without charge (BT-X-46). Generated — BTE-7. */
    BT_X_46(10046, LINE, NUMBER, availableInEn16931 = false),

    /** Package quantity (BT-X-47). Generated — BTE-7. */
    BT_X_47(10047, LINE, NUMBER, availableInEn16931 = false),

    /** ShipTo name (BT-X-50). Generated — BTE-7. */
    BT_X_50(10050, LINE, TEXT, availableInEn16931 = false),

    /** ShipTo Legal ID (BT-X-51). Generated — BTE-7. */
    BT_X_51(10051, LINE, TEXT, availableInEn16931 = false),

    /** Trading Business Name (BT-X-52). Generated — BTE-7. */
    BT_X_52(10052, LINE, TEXT, availableInEn16931 = false),

    /** Name of the contact (BT-X-54). Generated — BTE-7. */
    BT_X_54(10054, LINE, TEXT, availableInEn16931 = false),

    /** Contact telephone number, value (BT-X-55). Generated — BTE-7. */
    BT_X_55(10055, LINE, TEXT, availableInEn16931 = false),

    /** Contact point fax number, value (BT-X-56). Generated — BTE-7. */
    BT_X_56(10056, LINE, TEXT, availableInEn16931 = false),

    /** Contact email address, value (BT-X-57). Generated — BTE-7. */
    BT_X_57(10057, LINE, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-58). Generated — BTE-7. */
    BT_X_58(10058, LINE, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-59). Generated — BTE-7. */
    BT_X_59(10059, LINE, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-60). Generated — BTE-7. */
    BT_X_60(10060, LINE, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-61). Generated — BTE-7. */
    BT_X_61(10061, LINE, TEXT, availableInEn16931 = false),

    /** City (BT-X-62). Generated — BTE-7. */
    BT_X_62(10062, LINE, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-63). Generated — BTE-7. */
    BT_X_63(10063, LINE, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-64). Generated — BTE-7. */
    BT_X_64(10064, LINE, TEXT, availableInEn16931 = false),

    /** Electronic address (BT-X-65). Generated — BTE-7. */
    BT_X_65(10065, LINE, TEXT, availableInEn16931 = false),

    /** VAT ID (BT-X-66). Generated — BTE-7. */
    BT_X_66(10066, LINE, TEXT, availableInEn16931 = false),

    /** Final recipient identifier (BT-X-67). Generated — BTE-7. */
    BT_X_67(10067, LINE, TEXT, availableInEn16931 = false),

    /** Final recipient global identifier (BT-X-68). Generated — BTE-7. */
    BT_X_68(10068, LINE, TEXT, availableInEn16931 = false),

    /** Final Recipient Name / Company Name (BT-X-69). Generated — BTE-7. */
    BT_X_69(10069, LINE, TEXT, availableInEn16931 = false),

    /** Company Registration Number (BT-X-70). Generated — BTE-7. */
    BT_X_70(10070, LINE, TEXT, availableInEn16931 = false),

    /** Trading Business Name (BT-X-71). Generated — BTE-7. */
    BT_X_71(10071, LINE, TEXT, availableInEn16931 = false),

    /** Name of the contact (BT-X-72). Generated — BTE-7. */
    BT_X_72(10072, LINE, TEXT, availableInEn16931 = false),

    /** Contact telephone number, value (BT-X-73). Generated — BTE-7. */
    BT_X_73(10073, LINE, TEXT, availableInEn16931 = false),

    /** Contact point fax number, value (BT-X-74). Generated — BTE-7. */
    BT_X_74(10074, LINE, TEXT, availableInEn16931 = false),

    /** Contact email address, value (BT-X-75). Generated — BTE-7. */
    BT_X_75(10075, LINE, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-76). Generated — BTE-7. */
    BT_X_76(10076, LINE, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-77). Generated — BTE-7. */
    BT_X_77(10077, LINE, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-78). Generated — BTE-7. */
    BT_X_78(10078, LINE, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-79). Generated — BTE-7. */
    BT_X_79(10079, LINE, TEXT, availableInEn16931 = false),

    /** City (BT-X-80). Generated — BTE-7. */
    BT_X_80(10080, LINE, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-81). Generated — BTE-7. */
    BT_X_81(10081, LINE, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-82). Generated — BTE-7. */
    BT_X_82(10082, LINE, TEXT, availableInEn16931 = false),

    /** Electronic address (BT-X-83). Generated — BTE-7. */
    BT_X_83(10083, LINE, TEXT, availableInEn16931 = false),

    /** VAT ID (BT-X-84). Generated — BTE-7. */
    BT_X_84(10084, LINE, TEXT, availableInEn16931 = false),

    /** Actual delivery date for this line, value (BT-X-85). Generated — BTE-7. */
    BT_X_85(10085, LINE, DATE, availableInEn16931 = false),

    /** Despatch advice number (BT-X-86). Generated — BTE-7. */
    BT_X_86(10086, LINE, TEXT, availableInEn16931 = false),

    /** Despatch advice item (BT-X-87). Generated — BTE-7. */
    BT_X_87(10087, LINE, TEXT, availableInEn16931 = false),

    /** Document date, value (BT-X-88). Generated — BTE-7. */
    BT_X_88(10088, LINE, DATE, availableInEn16931 = false),

    /** Goods receipt number (BT-X-89). Generated — BTE-7. */
    BT_X_89(10089, LINE, TEXT, availableInEn16931 = false),

    /** Goods receipt item (BT-X-90). Generated — BTE-7. */
    BT_X_90(10090, LINE, TEXT, availableInEn16931 = false),

    /** Document date, value (BT-X-91). Generated — BTE-7. */
    BT_X_91(10091, LINE, DATE, availableInEn16931 = false),

    /** Delivery Note Number (BT-X-92). Generated — BTE-7. */
    BT_X_92(10092, LINE, TEXT, availableInEn16931 = false),

    /** Delivery note item (BT-X-93). Generated — BTE-7. */
    BT_X_93(10093, LINE, TEXT, availableInEn16931 = false),

    /** Document date, value (BT-X-94). Generated — BTE-7. */
    BT_X_94(10094, LINE, DATE, availableInEn16931 = false),

    /** Tax Amount (BT-X-95). Generated — BTE-7. */
    BT_X_95(10095, LINE, NUMBER, availableInEn16931 = false),

    /** VAT exemption reason text (BT-X-96). Generated — BTE-7. */
    BT_X_96(10096, LINE, TEXT, availableInEn16931 = false),

    /** VAT exemption reason code (BT-X-97). Generated — BTE-7. */
    BT_X_97(10097, LINE, TEXT, availableInEn16931 = false),

    /** Total amount of allowances / charges (BT-X-98). Generated — BTE-7. */
    BT_X_98(10098, LINE, NUMBER, availableInEn16931 = false),

    /** Accounting reference (Code) (BT-X-99). Generated — BTE-7. */
    BT_X_99(10099, LINE, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-100). Generated — BTE-7. */
    BT_X_100(10100, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-101). Generated — BTE-7. */
    BT_X_101(10101, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-102). Generated — BTE-7. */
    BT_X_102(10102, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-103). Generated — BTE-7. */
    BT_X_103(10103, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** City (BT-X-104). Generated — BTE-7. */
    BT_X_104(10104, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-105). Generated — BTE-7. */
    BT_X_105(10105, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-106). Generated — BTE-7. */
    BT_X_106(10106, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Seller contact fax number (BT-X-107). Generated — BTE-7. */
    BT_X_107(10107, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-108). Generated — BTE-7. */
    BT_X_108(10108, BUYER_PARTY, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-109). Generated — BTE-7. */
    BT_X_109(10109, BUYER_PARTY, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-110). Generated — BTE-7. */
    BT_X_110(10110, BUYER_PARTY, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-111). Generated — BTE-7. */
    BT_X_111(10111, BUYER_PARTY, TEXT, availableInEn16931 = false),

    /** City (BT-X-112). Generated — BTE-7. */
    BT_X_112(10112, BUYER_PARTY, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-113). Generated — BTE-7. */
    BT_X_113(10113, BUYER_PARTY, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-114). Generated — BTE-7. */
    BT_X_114(10114, BUYER_PARTY, TEXT, availableInEn16931 = false),

    /** Buyer contact fax number (BT-X-115). Generated — BTE-7. */
    BT_X_115(10115, BUYER_PARTY, TEXT, availableInEn16931 = false),

    /** Identifier (BT-X-116). Generated — BTE-7. */
    BT_X_116(10116, HEADER, TEXT, availableInEn16931 = false),

    /** Global identifier (BT-X-117). Generated — BTE-7. */
    BT_X_117(10117, HEADER, TEXT, availableInEn16931 = false),

    /** Legal registration identifier (BT-X-118). Generated — BTE-7. */
    BT_X_118(10118, HEADER, TEXT, availableInEn16931 = false),

    /** Trading name (BT-X-119). Generated — BTE-7. */
    BT_X_119(10119, HEADER, TEXT, availableInEn16931 = false),

    /** Name of the contact (BT-X-120). Generated — BTE-7. */
    BT_X_120(10120, HEADER, TEXT, availableInEn16931 = false),

    /** Department name (BT-X-121). Generated — BTE-7. */
    BT_X_121(10121, HEADER, TEXT, availableInEn16931 = false),

    /** Contact telephone number, value (BT-X-122). Generated — BTE-7. */
    BT_X_122(10122, HEADER, TEXT, availableInEn16931 = false),

    /** Contact point fax number, value (BT-X-123). Generated — BTE-7. */
    BT_X_123(10123, HEADER, TEXT, availableInEn16931 = false),

    /** Contact email address, value (BT-X-124). Generated — BTE-7. */
    BT_X_124(10124, HEADER, TEXT, availableInEn16931 = false),

    /** Electronic address (BT-X-125). Generated — BTE-7. */
    BT_X_125(10125, HEADER, TEXT, availableInEn16931 = false),

    /** Deviating end user identifier (BT-X-126). Generated — BTE-7. */
    BT_X_126(10126, HEADER, TEXT, availableInEn16931 = false),

    /** Deviating end user global identifier (BT-X-127). Generated — BTE-7. */
    BT_X_127(10127, HEADER, TEXT, availableInEn16931 = false),

    /** Deviating end user identifier Name / Company Name (BT-X-128). Generated — BTE-7. */
    BT_X_128(10128, HEADER, TEXT, availableInEn16931 = false),

    /** Company Registration Number (BT-X-129). Generated — BTE-7. */
    BT_X_129(10129, HEADER, TEXT, availableInEn16931 = false),

    /** Trading Business Name (BT-X-130). Generated — BTE-7. */
    BT_X_130(10130, HEADER, TEXT, availableInEn16931 = false),

    /** Name of the contact (BT-X-131). Generated — BTE-7. */
    BT_X_131(10131, HEADER, TEXT, availableInEn16931 = false),

    /** Department name (BT-X-132). Generated — BTE-7. */
    BT_X_132(10132, HEADER, TEXT, availableInEn16931 = false),

    /** Contact telephone number, value (BT-X-133). Generated — BTE-7. */
    BT_X_133(10133, HEADER, TEXT, availableInEn16931 = false),

    /** Contact point fax number, value (BT-X-134). Generated — BTE-7. */
    BT_X_134(10134, HEADER, TEXT, availableInEn16931 = false),

    /** Contact email address, value (BT-X-135). Generated — BTE-7. */
    BT_X_135(10135, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-136). Generated — BTE-7. */
    BT_X_136(10136, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-137). Generated — BTE-7. */
    BT_X_137(10137, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-138). Generated — BTE-7. */
    BT_X_138(10138, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-139). Generated — BTE-7. */
    BT_X_139(10139, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-140). Generated — BTE-7. */
    BT_X_140(10140, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-141). Generated — BTE-7. */
    BT_X_141(10141, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-142). Generated — BTE-7. */
    BT_X_142(10142, HEADER, TEXT, availableInEn16931 = false),

    /** Electronic address (BT-X-143). Generated — BTE-7. */
    BT_X_143(10143, HEADER, TEXT, availableInEn16931 = false),

    /** VAT ID (BT-X-144). Generated — BTE-7. */
    BT_X_144(10144, HEADER, TEXT, availableInEn16931 = false),

    /** Delivery condition (Code) (BT-X-145). Generated — BTE-7. */
    BT_X_145(10145, HEADER, TEXT, availableInEn16931 = false),

    /** Order confirmation date, value (BT-X-146). Generated — BTE-7. */
    BT_X_146(10146, HEADER, DATE, availableInEn16931 = false),

    /** Order Date, value (BT-X-147). Generated — BTE-7. */
    BT_X_147(10147, HEADER, DATE, availableInEn16931 = false),

    /** Contract date, value (BT-X-148). Generated — BTE-7. */
    BT_X_148(10148, HEADER, DATE, availableInEn16931 = false),

    /** Document date, value (BT-X-149). Generated — BTE-7. */
    BT_X_149(10149, HEADER, DATE, availableInEn16931 = false),

    /** Ultimate Customer Order number of the final customer (BT-X-150). Generated — BTE-7. */
    BT_X_150(10150, HEADER, TEXT, availableInEn16931 = false),

    /** Document date, value (BT-X-151). Generated — BTE-7. */
    BT_X_151(10151, HEADER, DATE, availableInEn16931 = false),

    /** Delivery method (Code) (BT-X-152). Generated — BTE-7. */
    BT_X_152(10152, HEADER, TEXT, availableInEn16931 = false),

    /** Legal registration identifier (BT-X-153). Generated — BTE-7. */
    BT_X_153(10153, HEADER, TEXT, availableInEn16931 = false),

    /** Trading name (BT-X-154). Generated — BTE-7. */
    BT_X_154(10154, HEADER, TEXT, availableInEn16931 = false),

    /** Name of the contact (BT-X-155). Generated — BTE-7. */
    BT_X_155(10155, HEADER, TEXT, availableInEn16931 = false),

    /** Department name (BT-X-156). Generated — BTE-7. */
    BT_X_156(10156, HEADER, TEXT, availableInEn16931 = false),

    /** Contact telephone number, value (BT-X-157). Generated — BTE-7. */
    BT_X_157(10157, HEADER, TEXT, availableInEn16931 = false),

    /** Contact point fax number, value (BT-X-158). Generated — BTE-7. */
    BT_X_158(10158, HEADER, TEXT, availableInEn16931 = false),

    /** Contact email address, value (BT-X-159). Generated — BTE-7. */
    BT_X_159(10159, HEADER, TEXT, availableInEn16931 = false),

    /** Electronic address (BT-X-160). Generated — BTE-7. */
    BT_X_160(10160, HEADER, TEXT, availableInEn16931 = false),

    /** VAT identifier (BT-X-161). Generated — BTE-7. */
    BT_X_161(10161, HEADER, TEXT, availableInEn16931 = false),

    /** Final recipient identifier (BT-X-162). Generated — BTE-7. */
    BT_X_162(10162, HEADER, TEXT, availableInEn16931 = false),

    /** Final recipient global identifier (BT-X-163). Generated — BTE-7. */
    BT_X_163(10163, HEADER, TEXT, availableInEn16931 = false),

    /** Final Recipient Name / Company Name (BT-X-164). Generated — BTE-7. */
    BT_X_164(10164, HEADER, TEXT, availableInEn16931 = false),

    /** Company Registration Number (BT-X-165). Generated — BTE-7. */
    BT_X_165(10165, HEADER, TEXT, availableInEn16931 = false),

    /** Trading Business Name (BT-X-166). Generated — BTE-7. */
    BT_X_166(10166, HEADER, TEXT, availableInEn16931 = false),

    /** Name of the contact (BT-X-167). Generated — BTE-7. */
    BT_X_167(10167, HEADER, TEXT, availableInEn16931 = false),

    /** Department name (BT-X-168). Generated — BTE-7. */
    BT_X_168(10168, HEADER, TEXT, availableInEn16931 = false),

    /** Contact telephone number, value (BT-X-169). Generated — BTE-7. */
    BT_X_169(10169, HEADER, TEXT, availableInEn16931 = false),

    /** Contact point fax number, value (BT-X-170). Generated — BTE-7. */
    BT_X_170(10170, HEADER, TEXT, availableInEn16931 = false),

    /** Contact email address, value (BT-X-171). Generated — BTE-7. */
    BT_X_171(10171, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-172). Generated — BTE-7. */
    BT_X_172(10172, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-173). Generated — BTE-7. */
    BT_X_173(10173, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-174). Generated — BTE-7. */
    BT_X_174(10174, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-175). Generated — BTE-7. */
    BT_X_175(10175, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-176). Generated — BTE-7. */
    BT_X_176(10176, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-177). Generated — BTE-7. */
    BT_X_177(10177, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-178). Generated — BTE-7. */
    BT_X_178(10178, HEADER, TEXT, availableInEn16931 = false),

    /** Electronic address (BT-X-179). Generated — BTE-7. */
    BT_X_179(10179, HEADER, TEXT, availableInEn16931 = false),

    /** VAT ID (BT-X-180). Generated — BTE-7. */
    BT_X_180(10180, HEADER, TEXT, availableInEn16931 = false),

    /** Deviating sender identifier (BT-X-181). Generated — BTE-7. */
    BT_X_181(10181, HEADER, TEXT, availableInEn16931 = false),

    /** Deviating sender global identifier (BT-X-182). Generated — BTE-7. */
    BT_X_182(10182, HEADER, TEXT, availableInEn16931 = false),

    /** Deviating sender name / company name (BT-X-183). Generated — BTE-7. */
    BT_X_183(10183, HEADER, TEXT, availableInEn16931 = false),

    /** Company Registration Number (BT-X-184). Generated — BTE-7. */
    BT_X_184(10184, HEADER, TEXT, availableInEn16931 = false),

    /** Trading Business Name (BT-X-185). Generated — BTE-7. */
    BT_X_185(10185, HEADER, TEXT, availableInEn16931 = false),

    /** Name of the contact (BT-X-186). Generated — BTE-7. */
    BT_X_186(10186, HEADER, TEXT, availableInEn16931 = false),

    /** Department name (BT-X-187). Generated — BTE-7. */
    BT_X_187(10187, HEADER, TEXT, availableInEn16931 = false),

    /** Contact telephone number, value (BT-X-188). Generated — BTE-7. */
    BT_X_188(10188, HEADER, TEXT, availableInEn16931 = false),

    /** Contact point fax number, value (BT-X-189). Generated — BTE-7. */
    BT_X_189(10189, HEADER, TEXT, availableInEn16931 = false),

    /** Contact email address, value (BT-X-190). Generated — BTE-7. */
    BT_X_190(10190, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-191). Generated — BTE-7. */
    BT_X_191(10191, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-192). Generated — BTE-7. */
    BT_X_192(10192, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-193). Generated — BTE-7. */
    BT_X_193(10193, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-194). Generated — BTE-7. */
    BT_X_194(10194, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-195). Generated — BTE-7. */
    BT_X_195(10195, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-196). Generated — BTE-7. */
    BT_X_196(10196, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-197). Generated — BTE-7. */
    BT_X_197(10197, HEADER, TEXT, availableInEn16931 = false),

    /** Electronic address (BT-X-198). Generated — BTE-7. */
    BT_X_198(10198, HEADER, TEXT, availableInEn16931 = false),

    /** VAT ID (BT-X-199). Generated — BTE-7. */
    BT_X_199(10199, HEADER, TEXT, availableInEn16931 = false),

    /** Despatch advice date, value (BT-X-200). Generated — BTE-7. */
    BT_X_200(10200, HEADER, DATE, availableInEn16931 = false),

    /** Goods receipt date, value (BT-X-201). Generated — BTE-7. */
    BT_X_201(10201, HEADER, DATE, availableInEn16931 = false),

    /** Delivery note reference (BT-X-202). Generated — BTE-7. */
    BT_X_202(10202, HEADER, TEXT, availableInEn16931 = false),

    /** Delivery note date, value (BT-X-203). Generated — BTE-7. */
    BT_X_203(10203, HEADER, DATE, availableInEn16931 = false),

    /** Seller reference number (BT-X-204). Generated — BTE-7. */
    BT_X_204(10204, HEADER, TEXT, availableInEn16931 = false),

    /** Deviating invoicer identifier (BT-X-205). Generated — BTE-7. */
    BT_X_205(10205, HEADER, TEXT, availableInEn16931 = false),

    /** Deviating invoicer global identifier (BT-X-206). Generated — BTE-7. */
    BT_X_206(10206, HEADER, TEXT, availableInEn16931 = false),

    /** Name (BT-X-207). Generated — BTE-7. */
    BT_X_207(10207, HEADER, TEXT, availableInEn16931 = false),

    /** Company Registration Number (BT-X-208). Generated — BTE-7. */
    BT_X_208(10208, HEADER, TEXT, availableInEn16931 = false),

    /** Trading Business Name (BT-X-209). Generated — BTE-7. */
    BT_X_209(10209, HEADER, TEXT, availableInEn16931 = false),

    /** Name of the contact (BT-X-210). Generated — BTE-7. */
    BT_X_210(10210, HEADER, TEXT, availableInEn16931 = false),

    /** Department name (BT-X-211). Generated — BTE-7. */
    BT_X_211(10211, HEADER, TEXT, availableInEn16931 = false),

    /** Contact telephone number, value (BT-X-212). Generated — BTE-7. */
    BT_X_212(10212, HEADER, TEXT, availableInEn16931 = false),

    /** Contact point fax number, value (BT-X-213). Generated — BTE-7. */
    BT_X_213(10213, HEADER, TEXT, availableInEn16931 = false),

    /** Contact email address, value (BT-X-214). Generated — BTE-7. */
    BT_X_214(10214, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-215). Generated — BTE-7. */
    BT_X_215(10215, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-216). Generated — BTE-7. */
    BT_X_216(10216, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-217). Generated — BTE-7. */
    BT_X_217(10217, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-218). Generated — BTE-7. */
    BT_X_218(10218, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-219). Generated — BTE-7. */
    BT_X_219(10219, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-220). Generated — BTE-7. */
    BT_X_220(10220, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-221). Generated — BTE-7. */
    BT_X_221(10221, HEADER, TEXT, availableInEn16931 = false),

    /** Electronic address (BT-X-222). Generated — BTE-7. */
    BT_X_222(10222, HEADER, TEXT, availableInEn16931 = false),

    /** VAT ID (BT-X-223). Generated — BTE-7. */
    BT_X_223(10223, HEADER, TEXT, availableInEn16931 = false),

    /** Deviating invoice recipient identifier (BT-X-224). Generated — BTE-7. */
    BT_X_224(10224, HEADER, TEXT, availableInEn16931 = false),

    /** Deviating invoice recipient global identifier (BT-X-225). Generated — BTE-7. */
    BT_X_225(10225, HEADER, TEXT, availableInEn16931 = false),

    /** Deviating invoice recipient name / company name (BT-X-226). Generated — BTE-7. */
    BT_X_226(10226, HEADER, TEXT, availableInEn16931 = false),

    /** Company Registration Number (BT-X-227). Generated — BTE-7. */
    BT_X_227(10227, HEADER, TEXT, availableInEn16931 = false),

    /** Trading Business Name (BT-X-228). Generated — BTE-7. */
    BT_X_228(10228, HEADER, TEXT, availableInEn16931 = false),

    /** Name of the contact (BT-X-229). Generated — BTE-7. */
    BT_X_229(10229, HEADER, TEXT, availableInEn16931 = false),

    /** Department name (BT-X-230). Generated — BTE-7. */
    BT_X_230(10230, HEADER, TEXT, availableInEn16931 = false),

    /** Contact telephone number, value (BT-X-231). Generated — BTE-7. */
    BT_X_231(10231, HEADER, TEXT, availableInEn16931 = false),

    /** Contact point fax number, value (BT-X-232). Generated — BTE-7. */
    BT_X_232(10232, HEADER, TEXT, availableInEn16931 = false),

    /** Contact email address, value (BT-X-233). Generated — BTE-7. */
    BT_X_233(10233, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-234). Generated — BTE-7. */
    BT_X_234(10234, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-235). Generated — BTE-7. */
    BT_X_235(10235, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-236). Generated — BTE-7. */
    BT_X_236(10236, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-237). Generated — BTE-7. */
    BT_X_237(10237, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-238). Generated — BTE-7. */
    BT_X_238(10238, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-239). Generated — BTE-7. */
    BT_X_239(10239, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-240). Generated — BTE-7. */
    BT_X_240(10240, HEADER, TEXT, availableInEn16931 = false),

    /** Electronic address (BT-X-241). Generated — BTE-7. */
    BT_X_241(10241, HEADER, TEXT, availableInEn16931 = false),

    /** VAT ID (BT-X-242). Generated — BTE-7. */
    BT_X_242(10242, HEADER, TEXT, availableInEn16931 = false),

    /** Trading name (BT-X-243). Generated — BTE-7. */
    BT_X_243(10243, HEADER, TEXT, availableInEn16931 = false),

    /** Name of the contact (BT-X-244). Generated — BTE-7. */
    BT_X_244(10244, HEADER, TEXT, availableInEn16931 = false),

    /** Department name (BT-X-245). Generated — BTE-7. */
    BT_X_245(10245, HEADER, TEXT, availableInEn16931 = false),

    /** Contact telephone number, value (BT-X-246). Generated — BTE-7. */
    BT_X_246(10246, HEADER, TEXT, availableInEn16931 = false),

    /** Contact point fax number, value (BT-X-247). Generated — BTE-7. */
    BT_X_247(10247, HEADER, TEXT, availableInEn16931 = false),

    /** Contact email address, value (BT-X-248). Generated — BTE-7. */
    BT_X_248(10248, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-249). Generated — BTE-7. */
    BT_X_249(10249, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-250). Generated — BTE-7. */
    BT_X_250(10250, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-251). Generated — BTE-7. */
    BT_X_251(10251, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-252). Generated — BTE-7. */
    BT_X_252(10252, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-253). Generated — BTE-7. */
    BT_X_253(10253, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-254). Generated — BTE-7. */
    BT_X_254(10254, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-255). Generated — BTE-7. */
    BT_X_255(10255, HEADER, TEXT, availableInEn16931 = false),

    /** Electronic address (BT-X-256). Generated — BTE-7. */
    BT_X_256(10256, HEADER, TEXT, availableInEn16931 = false),

    /** VAT identifier (BT-X-257). Generated — BTE-7. */
    BT_X_257(10257, HEADER, TEXT, availableInEn16931 = false),

    /** Invoice currency (BT-X-258). Generated — BTE-7. */
    BT_X_258(10258, HEADER, TEXT, availableInEn16931 = false),

    /** Local currency (BT-X-259). Generated — BTE-7. */
    BT_X_259(10259, HEADER, TEXT, availableInEn16931 = false),

    /** Exchange rate (BT-X-260). Generated — BTE-7. */
    BT_X_260(10260, HEADER, NUMBER, availableInEn16931 = false),

    /** Exchange rate date, value (BT-X-261). Generated — BTE-7. */
    BT_X_261(10261, HEADER, DATE, availableInEn16931 = false),

    /** Line Total Basis Amount (BT-X-262). Generated — BTE-7. */
    BT_X_262(10262, HEADER, NUMBER, availableInEn16931 = false),

    /** Total amount of charges / allowances on document level (BT-X-263). Generated — BTE-7. */
    BT_X_263(10263, HEADER, NUMBER, availableInEn16931 = false),

    /** Invoicing period description (free text) (BT-X-264). Generated — BTE-7. */
    BT_X_264(10264, HEADER, TEXT, availableInEn16931 = false),

    /** Calculation sequence (BT-X-265). Generated — BTE-7. */
    BT_X_265(10265, HEADER, TEXT, availableInEn16931 = false),

    /** Allowance / charge base quantity (BT-X-266). Generated — BTE-7. */
    BT_X_266(10266, HEADER, NUMBER, availableInEn16931 = false),

    /** Unit code (BT-X-267). Generated — BTE-7. */
    BT_X_267(10267, HEADER, TEXT, availableInEn16931 = false),

    /** Calculation sequence (BT-X-268). Generated — BTE-7. */
    BT_X_268(10268, HEADER, TEXT, availableInEn16931 = false),

    /** Allowance / charge base quantity (BT-X-269). Generated — BTE-7. */
    BT_X_269(10269, HEADER, NUMBER, availableInEn16931 = false),

    /** Price base quantity, unit (BT-X-270). Generated — BTE-7. */
    BT_X_270(10270, HEADER, TEXT, availableInEn16931 = false),

    /** Service fee description (BT-X-271). Generated — BTE-7. */
    BT_X_271(10271, HEADER, TEXT, availableInEn16931 = false),

    /** Service fee amount (BT-X-272). Generated — BTE-7. */
    BT_X_272(10272, HEADER, NUMBER, availableInEn16931 = false),

    /** VAT category code (BT-X-273). Generated — BTE-7. */
    BT_X_273(10273, HEADER, TEXT, availableInEn16931 = false),

    /** VAT category rate (BT-X-274). Generated — BTE-7. */
    BT_X_274(10274, HEADER, NUMBER, availableInEn16931 = false),

    /** Partial payment amount (BT-X-275). Generated — BTE-7. */
    BT_X_275(10275, HEADER, NUMBER, availableInEn16931 = false),

    /** Maturity Reference Date, value (BT-X-276). Generated — BTE-7. */
    BT_X_276(10276, HEADER, DATE, availableInEn16931 = false),

    /** Due date period basis (BT-X-277). Generated — BTE-7. */
    BT_X_277(10277, HEADER, NUMBER, availableInEn16931 = false),

    /** Maturity Period, Unit code (BT-X-278). Generated — BTE-7. */
    BT_X_278(10278, HEADER, TEXT, availableInEn16931 = false),

    /** Payment penalty base amount (BT-X-279). Generated — BTE-7. */
    BT_X_279(10279, HEADER, NUMBER, availableInEn16931 = false),

    /** Payment penalty percentage (BT-X-280). Generated — BTE-7. */
    BT_X_280(10280, HEADER, NUMBER, availableInEn16931 = false),

    /** Payment penalty amount (BT-X-281). Generated — BTE-7. */
    BT_X_281(10281, HEADER, NUMBER, availableInEn16931 = false),

    /** Maturity Reference Date, value (BT-X-282). Generated — BTE-7. */
    BT_X_282(10282, HEADER, DATE, availableInEn16931 = false),

    /** Due date period basis (BT-X-283). Generated — BTE-7. */
    BT_X_283(10283, HEADER, NUMBER, availableInEn16931 = false),

    /** Maturity Period, Unit code (BT-X-284). Generated — BTE-7. */
    BT_X_284(10284, HEADER, TEXT, availableInEn16931 = false),

    /** Payment discount base amount (BT-X-285). Generated — BTE-7. */
    BT_X_285(10285, HEADER, NUMBER, availableInEn16931 = false),

    /** Payment discount percentage (BT-X-286). Generated — BTE-7. */
    BT_X_286(10286, HEADER, NUMBER, availableInEn16931 = false),

    /** Payment discount amount (BT-X-287). Generated — BTE-7. */
    BT_X_287(10287, HEADER, NUMBER, availableInEn16931 = false),

    /** Accounting reference type (Code) (BT-X-290). Generated — BTE-7. */
    BT_X_290(10290, HEADER, TEXT, availableInEn16931 = false),

    /** Advanced payment, value (BT-X-291). Generated — BTE-7. */
    BT_X_291(10291, HEADER, NUMBER, availableInEn16931 = false),

    /** Date of advanced payment, value (BT-X-292). Generated — BTE-7. */
    BT_X_292(10292, HEADER, DATE, availableInEn16931 = false),

    /** Included tax (BT-X-293). Generated — BTE-7. */
    BT_X_293(10293, HEADER, NUMBER, availableInEn16931 = false),

    /** Type of tax (code) (BT-X-294). Generated — BTE-7. */
    BT_X_294(10294, HEADER, TEXT, availableInEn16931 = false),

    /** VAT exemption reason text (BT-X-295). Generated — BTE-7. */
    BT_X_295(10295, HEADER, TEXT, availableInEn16931 = false),

    /** VAT category code (BT-X-296). Generated — BTE-7. */
    BT_X_296(10296, HEADER, TEXT, availableInEn16931 = false),

    /** VAT exemption reason code (BT-X-297). Generated — BTE-7. */
    BT_X_297(10297, HEADER, TEXT, availableInEn16931 = false),

    /** VAT category rate (BT-X-298). Generated — BTE-7. */
    BT_X_298(10298, HEADER, NUMBER, availableInEn16931 = false),

    /** Document description (BT-X-299). Generated — BTE-7. */
    BT_X_299(10299, LINE, TEXT, availableInEn16931 = false),

    /** Charge in percent (BT-X-300). Generated — BTE-7. */
    BT_X_300(10300, LINE, NUMBER, availableInEn16931 = false),

    /** Charge base amount (BT-X-301). Generated — BTE-7. */
    BT_X_301(10301, LINE, NUMBER, availableInEn16931 = false),

    /** Charge amount (BT-X-302). Generated — BTE-7. */
    BT_X_302(10302, LINE, NUMBER, availableInEn16931 = false),

    /** Reason for the charge (free text) (BT-X-303). Generated — BTE-7. */
    BT_X_303(10303, LINE, TEXT, availableInEn16931 = false),

    /** Parent line ID (BT-X-304). Generated — BTE-7. */
    BT_X_304(10304, LINE, TEXT, availableInEn16931 = false),

    /** Product identifier (BT-X-305). Generated — BTE-7. */
    BT_X_305(10305, LINE, TEXT, availableInEn16931 = false),

    /** Item (Trade Product) Instances Batch ID (BT-X-306). Generated — BTE-7. */
    BT_X_306(10306, LINE, TEXT, availableInEn16931 = false),

    /** Item (Trade Product) Instances Supplier Serial ID (BT-X-307). Generated — BTE-7. */
    BT_X_307(10307, LINE, TEXT, availableInEn16931 = false),

    /** ID of Included Referenced Product (BT-X-308). Generated — BTE-7. */
    BT_X_308(10308, LINE, TEXT, availableInEn16931 = false),

    /** IndustryAssignedID of Included Referenced Product (BT-X-309). Generated — BTE-7. */
    BT_X_309(10309, LINE, TEXT, availableInEn16931 = false),

    /** Document number (BT-X-310). Generated — BTE-7. */
    BT_X_310(10310, LINE, TEXT, availableInEn16931 = false),

    /** Referenced position (BT-X-311). Generated — BTE-7. */
    BT_X_311(10311, LINE, TEXT, availableInEn16931 = false),

    /** Document date, value (BT-X-312). Generated — BTE-7. */
    BT_X_312(10312, LINE, DATE, availableInEn16931 = false),

    /** Reason for the discount (code) (BT-X-313). Generated — BTE-7. */
    BT_X_313(10313, LINE, TEXT, availableInEn16931 = false),

    /** Reason for the charge (code) (BT-X-314). Generated — BTE-7. */
    BT_X_314(10314, LINE, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-315). Generated — BTE-7. */
    BT_X_315(10315, LINE, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-316). Generated — BTE-7. */
    BT_X_316(10316, LINE, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-317). Generated — BTE-7. */
    BT_X_317(10317, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-318). Generated — BTE-7. */
    BT_X_318(10318, BUYER_PARTY, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-319). Generated — BTE-7. */
    BT_X_319(10319, HEADER, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-320). Generated — BTE-7. */
    BT_X_320(10320, HEADER, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-321). Generated — BTE-7. */
    BT_X_321(10321, HEADER, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-322). Generated — BTE-7. */
    BT_X_322(10322, HEADER, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-323). Generated — BTE-7. */
    BT_X_323(10323, HEADER, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-324). Generated — BTE-7. */
    BT_X_324(10324, HEADER, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-325). Generated — BTE-7. */
    BT_X_325(10325, HEADER, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-326). Generated — BTE-7. */
    BT_X_326(10326, HEADER, TEXT, availableInEn16931 = false),

    /** Total amount of line item charges (BT-X-327). Generated — BTE-7. */
    BT_X_327(10327, LINE, NUMBER, availableInEn16931 = false),

    /** Total amount of line item allowances (BT-X-328). Generated — BTE-7. */
    BT_X_328(10328, LINE, NUMBER, availableInEn16931 = false),

    /** Total amount of line item taxes (BT-X-329). Generated — BTE-7. */
    BT_X_329(10329, LINE, NUMBER, availableInEn16931 = false),

    /** Total line item gross amount (BT-X-330). Generated — BTE-7. */
    BT_X_330(10330, LINE, NUMBER, availableInEn16931 = false),

    /** Preceding Invoice reference (BT-X-331). Generated — BTE-7. */
    BT_X_331(10331, LINE, TEXT, availableInEn16931 = false),

    /** Preceding incoive type code (BT-X-332). Generated — BTE-7. */
    BT_X_332(10332, LINE, TEXT, availableInEn16931 = false),

    /** Preceding Invoice issue date, value (BT-X-333). Generated — BTE-7. */
    BT_X_333(10333, LINE, DATE, availableInEn16931 = false),

    /** Buyer additional legal information (BT-X-334). Generated — BTE-7. */
    BT_X_334(10334, BUYER_PARTY, TEXT, availableInEn16931 = false),

    /** Sales agent identifier Name / Company Name (BT-X-335). Generated — BTE-7. */
    BT_X_335(10335, HEADER, TEXT, availableInEn16931 = false),

    /** Trading Business Name (BT-X-336). Generated — BTE-7. */
    BT_X_336(10336, HEADER, TEXT, availableInEn16931 = false),

    /** Sales agent identifier (BT-X-337). Generated — BTE-7. */
    BT_X_337(10337, HEADER, TEXT, availableInEn16931 = false),

    /** Sales agent global identifier, value (BT-X-338). Generated — BTE-7. */
    BT_X_338(10338, HEADER, TEXT, availableInEn16931 = false),

    /** Sales Agent Registration Number (BT-X-339). Generated — BTE-7. */
    BT_X_339(10339, HEADER, TEXT, availableInEn16931 = false),

    /** VAT ID (BT-X-340). Generated — BTE-7. */
    BT_X_340(10340, HEADER, TEXT, availableInEn16931 = false),

    /** Electronic address (BT-X-341). Generated — BTE-7. */
    BT_X_341(10341, HEADER, TEXT, availableInEn16931 = false),

    /** Name of the contact (BT-X-342). Generated — BTE-7. */
    BT_X_342(10342, HEADER, TEXT, availableInEn16931 = false),

    /** Department name (BT-X-343). Generated — BTE-7. */
    BT_X_343(10343, HEADER, TEXT, availableInEn16931 = false),

    /** Contact telephone number, value (BT-X-344). Generated — BTE-7. */
    BT_X_344(10344, HEADER, TEXT, availableInEn16931 = false),

    /** Contact point fax number, value (BT-X-345). Generated — BTE-7. */
    BT_X_345(10345, HEADER, TEXT, availableInEn16931 = false),

    /** Contact email address, value (BT-X-346). Generated — BTE-7. */
    BT_X_346(10346, HEADER, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-347). Generated — BTE-7. */
    BT_X_347(10347, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-348). Generated — BTE-7. */
    BT_X_348(10348, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-349). Generated — BTE-7. */
    BT_X_349(10349, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-350). Generated — BTE-7. */
    BT_X_350(10350, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-351). Generated — BTE-7. */
    BT_X_351(10351, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-352). Generated — BTE-7. */
    BT_X_352(10352, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-353). Generated — BTE-7. */
    BT_X_353(10353, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-354). Generated — BTE-7. */
    BT_X_354(10354, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-355). Generated — BTE-7. */
    BT_X_355(10355, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-356). Generated — BTE-7. */
    BT_X_356(10356, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-357). Generated — BTE-7. */
    BT_X_357(10357, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-358). Generated — BTE-7. */
    BT_X_358(10358, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-359). Generated — BTE-7. */
    BT_X_359(10359, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-360). Generated — BTE-7. */
    BT_X_360(10360, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-361). Generated — BTE-7. */
    BT_X_361(10361, HEADER, TEXT, availableInEn16931 = false),

    /** Identifier Name / Company Name (BT-X-362). Generated — BTE-7. */
    BT_X_362(10362, HEADER, TEXT, availableInEn16931 = false),

    /** Trading Business Name (BT-X-363). Generated — BTE-7. */
    BT_X_363(10363, HEADER, TEXT, availableInEn16931 = false),

    /** Identifier (BT-X-364). Generated — BTE-7. */
    BT_X_364(10364, HEADER, TEXT, availableInEn16931 = false),

    /** Global identifier (BT-X-365). Generated — BTE-7. */
    BT_X_365(10365, HEADER, TEXT, availableInEn16931 = false),

    /** Company Registration Number (BT-X-366). Generated — BTE-7. */
    BT_X_366(10366, HEADER, TEXT, availableInEn16931 = false),

    /** VAT ID (BT-X-367). Generated — BTE-7. */
    BT_X_367(10367, HEADER, TEXT, availableInEn16931 = false),

    /** Electronic address (BT-X-368). Generated — BTE-7. */
    BT_X_368(10368, HEADER, TEXT, availableInEn16931 = false),

    /** Name of the contact (BT-X-369). Generated — BTE-7. */
    BT_X_369(10369, HEADER, TEXT, availableInEn16931 = false),

    /** Department name (BT-X-370). Generated — BTE-7. */
    BT_X_370(10370, HEADER, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-371). Generated — BTE-7. */
    BT_X_371(10371, HEADER, TEXT, availableInEn16931 = false),

    /** Contact telephone number, value (BT-X-372). Generated — BTE-7. */
    BT_X_372(10372, HEADER, TEXT, availableInEn16931 = false),

    /** Contact point fax number, value (BT-X-373). Generated — BTE-7. */
    BT_X_373(10373, HEADER, TEXT, availableInEn16931 = false),

    /** Contact email address, value (BT-X-374). Generated — BTE-7. */
    BT_X_374(10374, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-375). Generated — BTE-7. */
    BT_X_375(10375, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-376). Generated — BTE-7. */
    BT_X_376(10376, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-377). Generated — BTE-7. */
    BT_X_377(10377, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-378). Generated — BTE-7. */
    BT_X_378(10378, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-379). Generated — BTE-7. */
    BT_X_379(10379, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-380). Generated — BTE-7. */
    BT_X_380(10380, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-381). Generated — BTE-7. */
    BT_X_381(10381, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-382). Generated — BTE-7. */
    BT_X_382(10382, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-383). Generated — BTE-7. */
    BT_X_383(10383, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-384). Generated — BTE-7. */
    BT_X_384(10384, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-385). Generated — BTE-7. */
    BT_X_385(10385, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-386). Generated — BTE-7. */
    BT_X_386(10386, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-387). Generated — BTE-7. */
    BT_X_387(10387, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-388). Generated — BTE-7. */
    BT_X_388(10388, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-389). Generated — BTE-7. */
    BT_X_389(10389, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-390). Generated — BTE-7. */
    BT_X_390(10390, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-391). Generated — BTE-7. */
    BT_X_391(10391, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-392). Generated — BTE-7. */
    BT_X_392(10392, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-393). Generated — BTE-7. */
    BT_X_393(10393, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-394). Generated — BTE-7. */
    BT_X_394(10394, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-395). Generated — BTE-7. */
    BT_X_395(10395, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-396). Generated — BTE-7. */
    BT_X_396(10396, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-397). Generated — BTE-7. */
    BT_X_397(10397, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-398). Generated — BTE-7. */
    BT_X_398(10398, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-399). Generated — BTE-7. */
    BT_X_399(10399, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-400). Generated — BTE-7. */
    BT_X_400(10400, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-401). Generated — BTE-7. */
    BT_X_401(10401, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-402). Generated — BTE-7. */
    BT_X_402(10402, HEADER, TEXT, availableInEn16931 = false),

    /** Quotation number (BT-X-403). Generated — BTE-7. */
    BT_X_403(10403, HEADER, TEXT, availableInEn16931 = false),

    /** Document date, value (BT-X-404). Generated — BTE-7. */
    BT_X_404(10404, HEADER, DATE, availableInEn16931 = false),

    /** Type of contract (code) (BT-X-405). Generated — BTE-7. */
    BT_X_405(10405, HEADER, TEXT, availableInEn16931 = false),

    /** Name / Company Name (BT-X-406). Generated — BTE-7. */
    BT_X_406(10406, HEADER, TEXT, availableInEn16931 = false),

    /** Trading Business Name (BT-X-407). Generated — BTE-7. */
    BT_X_407(10407, HEADER, TEXT, availableInEn16931 = false),

    /** Identifier (BT-X-408). Generated — BTE-7. */
    BT_X_408(10408, HEADER, TEXT, availableInEn16931 = false),

    /** Global identifier (BT-X-409). Generated — BTE-7. */
    BT_X_409(10409, HEADER, TEXT, availableInEn16931 = false),

    /** Company Registration Number (BT-X-410). Generated — BTE-7. */
    BT_X_410(10410, HEADER, TEXT, availableInEn16931 = false),

    /** VAT ID (BT-X-411). Generated — BTE-7. */
    BT_X_411(10411, HEADER, TEXT, availableInEn16931 = false),

    /** Electronic address (BT-X-412). Generated — BTE-7. */
    BT_X_412(10412, HEADER, TEXT, availableInEn16931 = false),

    /** Name of the contact (BT-X-413). Generated — BTE-7. */
    BT_X_413(10413, HEADER, TEXT, availableInEn16931 = false),

    /** Department name (BT-X-414). Generated — BTE-7. */
    BT_X_414(10414, HEADER, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-415). Generated — BTE-7. */
    BT_X_415(10415, HEADER, TEXT, availableInEn16931 = false),

    /** Contact telephone number, value (BT-X-416). Generated — BTE-7. */
    BT_X_416(10416, HEADER, TEXT, availableInEn16931 = false),

    /** Contact point fax number, value (BT-X-417). Generated — BTE-7. */
    BT_X_417(10417, HEADER, TEXT, availableInEn16931 = false),

    /** Contact email address, value (BT-X-418). Generated — BTE-7. */
    BT_X_418(10418, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-419). Generated — BTE-7. */
    BT_X_419(10419, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-420). Generated — BTE-7. */
    BT_X_420(10420, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-421). Generated — BTE-7. */
    BT_X_421(10421, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-422). Generated — BTE-7. */
    BT_X_422(10422, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-423). Generated — BTE-7. */
    BT_X_423(10423, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-424). Generated — BTE-7. */
    BT_X_424(10424, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-425). Generated — BTE-7. */
    BT_X_425(10425, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-426). Generated — BTE-7. */
    BT_X_426(10426, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-427). Generated — BTE-7. */
    BT_X_427(10427, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-428). Generated — BTE-7. */
    BT_X_428(10428, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-429). Generated — BTE-7. */
    BT_X_429(10429, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-430). Generated — BTE-7. */
    BT_X_430(10430, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-431). Generated — BTE-7. */
    BT_X_431(10431, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-432). Generated — BTE-7. */
    BT_X_432(10432, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-433). Generated — BTE-7. */
    BT_X_433(10433, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-434). Generated — BTE-7. */
    BT_X_434(10434, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-435). Generated — BTE-7. */
    BT_X_435(10435, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-436). Generated — BTE-7. */
    BT_X_436(10436, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-437). Generated — BTE-7. */
    BT_X_437(10437, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-438). Generated — BTE-7. */
    BT_X_438(10438, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-439). Generated — BTE-7. */
    BT_X_439(10439, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-440). Generated — BTE-7. */
    BT_X_440(10440, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-441). Generated — BTE-7. */
    BT_X_441(10441, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-442). Generated — BTE-7. */
    BT_X_442(10442, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-443). Generated — BTE-7. */
    BT_X_443(10443, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-444). Generated — BTE-7. */
    BT_X_444(10444, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-445). Generated — BTE-7. */
    BT_X_445(10445, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-446). Generated — BTE-7. */
    BT_X_446(10446, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-447). Generated — BTE-7. */
    BT_X_447(10447, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-448). Generated — BTE-7. */
    BT_X_448(10448, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-449). Generated — BTE-7. */
    BT_X_449(10449, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-450). Generated — BTE-7. */
    BT_X_450(10450, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-451). Generated — BTE-7. */
    BT_X_451(10451, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-452). Generated — BTE-7. */
    BT_X_452(10452, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-453). Generated — BTE-7. */
    BT_X_453(10453, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-454). Generated — BTE-7. */
    BT_X_454(10454, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-455). Generated — BTE-7. */
    BT_X_455(10455, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-456). Generated — BTE-7. */
    BT_X_456(10456, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-457). Generated — BTE-7. */
    BT_X_457(10457, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-458). Generated — BTE-7. */
    BT_X_458(10458, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-459). Generated — BTE-7. */
    BT_X_459(10459, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-460). Generated — BTE-7. */
    BT_X_460(10460, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-461). Generated — BTE-7. */
    BT_X_461(10461, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-462). Generated — BTE-7. */
    BT_X_462(10462, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-463). Generated — BTE-7. */
    BT_X_463(10463, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-464). Generated — BTE-7. */
    BT_X_464(10464, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-465). Generated — BTE-7. */
    BT_X_465(10465, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-466). Generated — BTE-7. */
    BT_X_466(10466, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-467). Generated — BTE-7. */
    BT_X_467(10467, HEADER, TEXT, availableInEn16931 = false),

    /** Payee role (code) (BT-X-468). Generated — BTE-7. */
    BT_X_468(10468, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-469). Generated — BTE-7. */
    BT_X_469(10469, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-470). Generated — BTE-7. */
    BT_X_470(10470, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-471). Generated — BTE-7. */
    BT_X_471(10471, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-472). Generated — BTE-7. */
    BT_X_472(10472, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-473). Generated — BTE-7. */
    BT_X_473(10473, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-474). Generated — BTE-7. */
    BT_X_474(10474, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-475). Generated — BTE-7. */
    BT_X_475(10475, HEADER, TEXT, availableInEn16931 = false),

    /** Name/company name of the deviating invoice payer (BT-X-476). Generated — BTE-7. */
    BT_X_476(10476, HEADER, TEXT, availableInEn16931 = false),

    /** Trading Business Name (BT-X-477). Generated — BTE-7. */
    BT_X_477(10477, HEADER, TEXT, availableInEn16931 = false),

    /** Deviating invoice payer identifier (BT-X-478). Generated — BTE-7. */
    BT_X_478(10478, HEADER, TEXT, availableInEn16931 = false),

    /** Deviating invoice payer global identifier (BT-X-479). Generated — BTE-7. */
    BT_X_479(10479, HEADER, TEXT, availableInEn16931 = false),

    /** Company Registration Number (BT-X-480). Generated — BTE-7. */
    BT_X_480(10480, HEADER, TEXT, availableInEn16931 = false),

    /** VAT ID (BT-X-481). Generated — BTE-7. */
    BT_X_481(10481, HEADER, TEXT, availableInEn16931 = false),

    /** Payer Electronic address (BT-X-482). Generated — BTE-7. */
    BT_X_482(10482, HEADER, TEXT, availableInEn16931 = false),

    /** Role (code) (BT-X-483). Generated — BTE-7. */
    BT_X_483(10483, HEADER, TEXT, availableInEn16931 = false),

    /** Name of the contact (BT-X-484). Generated — BTE-7. */
    BT_X_484(10484, HEADER, TEXT, availableInEn16931 = false),

    /** Department name (BT-X-485). Generated — BTE-7. */
    BT_X_485(10485, HEADER, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-486). Generated — BTE-7. */
    BT_X_486(10486, HEADER, TEXT, availableInEn16931 = false),

    /** Contact telephone number, value (BT-X-487). Generated — BTE-7. */
    BT_X_487(10487, HEADER, TEXT, availableInEn16931 = false),

    /** Contact point fax number, value (BT-X-488). Generated — BTE-7. */
    BT_X_488(10488, HEADER, TEXT, availableInEn16931 = false),

    /** Contact email address, value (BT-X-489). Generated — BTE-7. */
    BT_X_489(10489, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-490). Generated — BTE-7. */
    BT_X_490(10490, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-491). Generated — BTE-7. */
    BT_X_491(10491, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-492). Generated — BTE-7. */
    BT_X_492(10492, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-493). Generated — BTE-7. */
    BT_X_493(10493, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-494). Generated — BTE-7. */
    BT_X_494(10494, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-495). Generated — BTE-7. */
    BT_X_495(10495, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-496). Generated — BTE-7. */
    BT_X_496(10496, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-497). Generated — BTE-7. */
    BT_X_497(10497, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-498). Generated — BTE-7. */
    BT_X_498(10498, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-499). Generated — BTE-7. */
    BT_X_499(10499, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-500). Generated — BTE-7. */
    BT_X_500(10500, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-501). Generated — BTE-7. */
    BT_X_501(10501, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-502). Generated — BTE-7. */
    BT_X_502(10502, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-503). Generated — BTE-7. */
    BT_X_503(10503, HEADER, TEXT, availableInEn16931 = false),

    /** Name/company name of Payee (BT-X-504). Generated — BTE-7. */
    BT_X_504(10504, HEADER, TEXT, availableInEn16931 = false),

    /** Trading Business Name (BT-X-505). Generated — BTE-7. */
    BT_X_505(10505, HEADER, TEXT, availableInEn16931 = false),

    /** Deviating invoice payee identifier (BT-X-506). Generated — BTE-7. */
    BT_X_506(10506, HEADER, TEXT, availableInEn16931 = false),

    /** Deviating invoice payer global identifier (BT-X-507). Generated — BTE-7. */
    BT_X_507(10507, HEADER, TEXT, availableInEn16931 = false),

    /** Company Registration Number (BT-X-508). Generated — BTE-7. */
    BT_X_508(10508, HEADER, TEXT, availableInEn16931 = false),

    /** VAT ID (BT-X-509). Generated — BTE-7. */
    BT_X_509(10509, HEADER, TEXT, availableInEn16931 = false),

    /** Electronic address (BT-X-510). Generated — BTE-7. */
    BT_X_510(10510, HEADER, TEXT, availableInEn16931 = false),

    /** Role (code) (BT-X-511). Generated — BTE-7. */
    BT_X_511(10511, HEADER, TEXT, availableInEn16931 = false),

    /** Name of the contact (BT-X-512). Generated — BTE-7. */
    BT_X_512(10512, HEADER, TEXT, availableInEn16931 = false),

    /** Department name (BT-X-513). Generated — BTE-7. */
    BT_X_513(10513, HEADER, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-514). Generated — BTE-7. */
    BT_X_514(10514, HEADER, TEXT, availableInEn16931 = false),

    /** Contact telephone number, value (BT-X-515). Generated — BTE-7. */
    BT_X_515(10515, HEADER, TEXT, availableInEn16931 = false),

    /** Contact point fax number, value (BT-X-516). Generated — BTE-7. */
    BT_X_516(10516, HEADER, TEXT, availableInEn16931 = false),

    /** Contact email address, value (BT-X-517). Generated — BTE-7. */
    BT_X_517(10517, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-518). Generated — BTE-7. */
    BT_X_518(10518, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-519). Generated — BTE-7. */
    BT_X_519(10519, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-520). Generated — BTE-7. */
    BT_X_520(10520, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-521). Generated — BTE-7. */
    BT_X_521(10521, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-522). Generated — BTE-7. */
    BT_X_522(10522, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-523). Generated — BTE-7. */
    BT_X_523(10523, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-524). Generated — BTE-7. */
    BT_X_524(10524, HEADER, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-525). Generated — BTE-7. */
    BT_X_525(10525, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-526). Generated — BTE-7. */
    BT_X_526(10526, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-527). Generated — BTE-7. */
    BT_X_527(10527, HEADER, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-528). Generated — BTE-7. */
    BT_X_528(10528, HEADER, TEXT, availableInEn16931 = false),

    /** City (BT-X-529). Generated — BTE-7. */
    BT_X_529(10529, HEADER, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-530). Generated — BTE-7. */
    BT_X_530(10530, HEADER, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-531). Generated — BTE-7. */
    BT_X_531(10531, HEADER, TEXT, availableInEn16931 = false),

    /** Industry assigned product identifier (BT-X-532). Generated — BTE-7. */
    BT_X_532(10532, LINE, TEXT, availableInEn16931 = false),

    /** Model identification of the item (BT-X-533). Generated — BTE-7. */
    BT_X_533(10533, LINE, TEXT, availableInEn16931 = false),

    /** Batch (lot) identification of the item (BT-X-534). Generated — BTE-7. */
    BT_X_534(10534, LINE, TEXT, availableInEn16931 = false),

    /** Item brand name (BT-X-535). Generated — BTE-7. */
    BT_X_535(10535, LINE, TEXT, availableInEn16931 = false),

    /** Item model name (BT-X-536). Generated — BTE-7. */
    BT_X_536(10536, LINE, TEXT, availableInEn16931 = false),

    /** Document number (BT-X-537). Generated — BTE-7. */
    BT_X_537(10537, LINE, TEXT, availableInEn16931 = false),

    /** Referenced position (BT-X-538). Generated — BTE-7. */
    BT_X_538(10538, LINE, TEXT, availableInEn16931 = false),

    /** Document date, value (BT-X-539). Generated — BTE-7. */
    BT_X_539(10539, LINE, DATE, availableInEn16931 = false),

    /** Referenced position (BT-X-540). Generated — BTE-7. */
    BT_X_540(10540, LINE, TEXT, availableInEn16931 = false),

    /** Role (code) (BT-X-541). Generated — BTE-7. */
    BT_X_541(10541, LINE, TEXT, availableInEn16931 = false),

    /** Role (code) (BT-X-542). Generated — BTE-7. */
    BT_X_542(10542, LINE, TEXT, availableInEn16931 = false),

    /** Seller Role (code) (BT-X-543). Generated — BTE-7. */
    BT_X_543(10543, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Role (code) (BT-X-544). Generated — BTE-7. */
    BT_X_544(10544, BUYER_PARTY, TEXT, availableInEn16931 = false),

    /** Role (code) (BT-X-545). Generated — BTE-7. */
    BT_X_545(10545, HEADER, TEXT, availableInEn16931 = false),

    /** Role (code) (BT-X-546). Generated — BTE-7. */
    BT_X_546(10546, HEADER, TEXT, availableInEn16931 = false),

    /** Role (code) (BT-X-547). Generated — BTE-7. */
    BT_X_547(10547, HEADER, TEXT, availableInEn16931 = false),

    /** Role (code) (BT-X-548). Generated — BTE-7. */
    BT_X_548(10548, HEADER, TEXT, availableInEn16931 = false),

    /** Role (code) (BT-X-549). Generated — BTE-7. */
    BT_X_549(10549, HEADER, TEXT, availableInEn16931 = false),

    /** Role (code) (BT-X-550). Generated — BTE-7. */
    BT_X_550(10550, HEADER, TEXT, availableInEn16931 = false),

    /** Role (code) (BT-X-551). Generated — BTE-7. */
    BT_X_551(10551, HEADER, TEXT, availableInEn16931 = false),

    /** Role (code) (BT-X-552). Generated — BTE-7. */
    BT_X_552(10552, HEADER, TEXT, availableInEn16931 = false),

    /** Role (code) (BT-X-553). Generated — BTE-7. */
    BT_X_553(10553, HEADER, TEXT, availableInEn16931 = false),

    /** Role (code) (BT-X-554). Generated — BTE-7. */
    BT_X_554(10554, HEADER, TEXT, availableInEn16931 = false),

    /** Preceding incoive type code (BT-X-555). Generated — BTE-7. */
    BT_X_555(10555, HEADER, TEXT, availableInEn16931 = false),

    /** Document date, value (BT-X-556). Generated — BTE-7. */
    BT_X_556(10556, HEADER, DATE, availableInEn16931 = false),

    /** Document date, value (BT-X-557). Generated — BTE-7. */
    BT_X_557(10557, HEADER, DATE, availableInEn16931 = false),

    /** Preceding Invoice reference (BT-X-558). Generated — BTE-7. */
    BT_X_558(10558, HEADER, TEXT, availableInEn16931 = false),

    /** Preceding incoive type code (BT-X-559). Generated — BTE-7. */
    BT_X_559(10559, HEADER, TEXT, availableInEn16931 = false),

    /** Preceding Invoice issue date (BT-X-560). Generated — BTE-7. */
    BT_X_560(10560, HEADER, DATE, availableInEn16931 = false),

    /** Number of units per package (BT-X-561). Generated — BTE-7. */
    BT_X_561(10561, LINE, NUMBER, availableInEn16931 = false),

    /** Delivery condition (Code) (BT-X-562). Generated — BTE-7. */
    BT_X_562(10562, LINE, TEXT, availableInEn16931 = false),

    /** Country code of location for the delivery terms (BT-X-563). Generated — BTE-7. */
    BT_X_563(10563, HEADER, TEXT, availableInEn16931 = false),

    /** Name of the relevant location for the delivery terms (BT-X-564). Generated — BTE-7. */
    BT_X_564(10564, HEADER, TEXT, availableInEn16931 = false),

    /** Country code of location for the delivery terms (BT-X-565). Generated — BTE-7. */
    BT_X_565(10565, LINE, TEXT, availableInEn16931 = false),

    /** Name of the relevant location for the delivery terms (BT-X-566). Generated — BTE-7. */
    BT_X_566(10566, LINE, TEXT, availableInEn16931 = false),

    /** Item seller identifier (BT-X-567). Generated — BTE-7. */
    BT_X_567(10567, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Item seller global identifier (BT-X-568). Generated — BTE-7. */
    BT_X_568(10568, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Item seller name (BT-X-569). Generated — BTE-7. */
    BT_X_569(10569, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Role (code) (BT-X-570). Generated — BTE-7. */
    BT_X_570(10570, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Seller additional legal information (BT-X-571). Generated — BTE-7. */
    BT_X_571(10571, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Line Item Seller Legal ID (BT-X-572). Generated — BTE-7. */
    BT_X_572(10572, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Trading Business Name (BT-X-573). Generated — BTE-7. */
    BT_X_573(10573, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Name of the contact (BT-X-574). Generated — BTE-7. */
    BT_X_574(10574, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-575). Generated — BTE-7. */
    BT_X_575(10575, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Contact telephone number, value (BT-X-576). Generated — BTE-7. */
    BT_X_576(10576, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Contact point fax number, value (BT-X-577). Generated — BTE-7. */
    BT_X_577(10577, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Contact email address, value (BT-X-578). Generated — BTE-7. */
    BT_X_578(10578, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-579). Generated — BTE-7. */
    BT_X_579(10579, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-580). Generated — BTE-7. */
    BT_X_580(10580, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-581). Generated — BTE-7. */
    BT_X_581(10581, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-582). Generated — BTE-7. */
    BT_X_582(10582, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** City (BT-X-583). Generated — BTE-7. */
    BT_X_583(10583, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-584). Generated — BTE-7. */
    BT_X_584(10584, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-585). Generated — BTE-7. */
    BT_X_585(10585, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Electronic address (BT-X-586). Generated — BTE-7. */
    BT_X_586(10586, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** VAT ID (BT-X-587). Generated — BTE-7. */
    BT_X_587(10587, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Tax ID (local) (BT-X-588). Generated — BTE-7. */
    BT_X_588(10588, SELLER_PARTY, TEXT, availableInEn16931 = false),

    /** Value added tax point date code (BT-X-589). Generated — BTE-7. */
    BT_X_589(10589, LINE, TEXT, availableInEn16931 = false),

    /** Total amount of line item taxes in accounting currency (BT-X-590). Generated — BTE-7. */
    BT_X_590(10590, LINE, NUMBER, availableInEn16931 = false),

    /** VAT exemption reason text (BT-X-591). Generated — BTE-7. */
    BT_X_591(10591, HEADER, TEXT, availableInEn16931 = false),

    /** VAT exemption reason code (BT-X-592). Generated — BTE-7. */
    BT_X_592(10592, HEADER, TEXT, availableInEn16931 = false),

    /** Manufacturer identifier (BT-X-593). Generated — BTE-7. */
    BT_X_593(10593, LINE, TEXT, availableInEn16931 = false),

    /** Global identifier (BT-X-594). Generated — BTE-7. */
    BT_X_594(10594, LINE, TEXT, availableInEn16931 = false),

    /** Manufacturer name (BT-X-595). Generated — BTE-7. */
    BT_X_595(10595, LINE, TEXT, availableInEn16931 = false),

    /** Role (code) (BT-X-596). Generated — BTE-7. */
    BT_X_596(10596, LINE, TEXT, availableInEn16931 = false),

    /** Manufacturer additional legal information (BT-X-597). Generated — BTE-7. */
    BT_X_597(10597, LINE, TEXT, availableInEn16931 = false),

    /** Manufacturer legal registration identifier (BT-X-598). Generated — BTE-7. */
    BT_X_598(10598, LINE, TEXT, availableInEn16931 = false),

    /** Manufacturer trading name (BT-X-599). Generated — BTE-7. */
    BT_X_599(10599, LINE, TEXT, availableInEn16931 = false),

    /** Name of the contact (BT-X-600). Generated — BTE-7. */
    BT_X_600(10600, LINE, TEXT, availableInEn16931 = false),

    /** Department name (BT-X-601). Generated — BTE-7. */
    BT_X_601(10601, LINE, TEXT, availableInEn16931 = false),

    /** Type of contact (code) (BT-X-602). Generated — BTE-7. */
    BT_X_602(10602, LINE, TEXT, availableInEn16931 = false),

    /** Contact telephone number, value (BT-X-603). Generated — BTE-7. */
    BT_X_603(10603, LINE, TEXT, availableInEn16931 = false),

    /** Contact point fax number, value (BT-X-604). Generated — BTE-7. */
    BT_X_604(10604, LINE, TEXT, availableInEn16931 = false),

    /** Contact email address, value (BT-X-605). Generated — BTE-7. */
    BT_X_605(10605, LINE, TEXT, availableInEn16931 = false),

    /** Post code (BT-X-606). Generated — BTE-7. */
    BT_X_606(10606, LINE, TEXT, availableInEn16931 = false),

    /** Address line 1 (BT-X-607). Generated — BTE-7. */
    BT_X_607(10607, LINE, TEXT, availableInEn16931 = false),

    /** Address line 2 (BT-X-608). Generated — BTE-7. */
    BT_X_608(10608, LINE, TEXT, availableInEn16931 = false),

    /** Address line 3 (BT-X-609). Generated — BTE-7. */
    BT_X_609(10609, LINE, TEXT, availableInEn16931 = false),

    /** City (BT-X-610). Generated — BTE-7. */
    BT_X_610(10610, LINE, TEXT, availableInEn16931 = false),

    /** Country code (BT-X-611). Generated — BTE-7. */
    BT_X_611(10611, LINE, TEXT, availableInEn16931 = false),

    /** Country subdivision (BT-X-612). Generated — BTE-7. */
    BT_X_612(10612, LINE, TEXT, availableInEn16931 = false),

    /** Electronic address (BT-X-613). Generated — BTE-7. */
    BT_X_613(10613, LINE, TEXT, availableInEn16931 = false),

    /** VAT ID (BT-X-614). Generated — BTE-7. */
    BT_X_614(10614, LINE, TEXT, availableInEn16931 = false),

    /** Tax ID (local) (BT-X-615). Generated — BTE-7. */
    BT_X_615(10615, LINE, TEXT, availableInEn16931 = false),

    /** Invoice line-level non-VAT tax code (BT-X-616). Generated — BTE-7. */
    BT_X_616(10616, LINE, TEXT, availableInEn16931 = false),
    // GENERATED-TERMS-END

    ;

    init {
        assertB2bTermBindingInvariant(officialCode, binding, B2bCatalogMetadata(xpath, boundProperty, maxLength))
        assertB2bTermValueDomainInvariant(officialCode, fieldKind, valueDomain)
    }

    /**
     * Official spelling, e.g. `"BT-19"` — display and logging. A `BT-X-nn` (EXTENDED)
     * term's [code] is `10000 + nn` (the established numbering scheme); rendered back to its own
     * `"BT-X-nn"` spelling here rather than the misleading `"BT-10019"` the raw offset would give.
     */
    val officialCode: String get() =
        if (code >= EXTENDED_CODE_OFFSET) "BT-X-${code - EXTENDED_CODE_OFFSET}" else "BT-$code"

    /** True for the terms a `B2bProfile` may pick — every term except [catalogExcluded]
     *  ones, no longer simply `binding != null` (see the class KDoc). */
    val catalogEligible: Boolean get() = !catalogExcluded

    /**
     * i18n key of the short display name, e.g. `"b2b.term.bt19.name"` — resolved per locale via
     * [B2bTermTexts.name] against the shared `i18n.b2b-terms` bundles.
     */
    val nameKey: String get() = "b2b.term.bt$code.name"

    /**
     * i18n key of the spec definition text, e.g. `"b2b.catalog.bt19.definition"` — resolved via
     * [B2bTermTexts.definitionOrNull] against the shared `i18n.b2b-terms` bundles; derived,
     * matches the keys already present there.
     */
    val specDefinitionKey: String get() = "b2b.catalog.bt$code.definition"

    companion object {
        /** The established numbering scheme's `BT-X-nn` offset (`10000 + nn`), named once
         *  so [officialCode] and [fromOfficialCodeOrNull] can't drift apart on the raw literal. */
        private const val EXTENDED_CODE_OFFSET = 10_000

        private val byCode: Map<Int, B2bTerm> = entries.associateBy { it.code }

        /** @throws IllegalArgumentException if [code] is not a known BT number. */
        fun fromCode(code: Int): B2bTerm =
            byCode[code] ?: throw IllegalArgumentException("Unknown B2bTerm code: $code")

        fun fromCodeOrNull(code: Int): B2bTerm? = byCode[code]

        /**
         * Parses [officialCode] (the [B2bTerm.officialCode] spelling, e.g. `"BT-19"`) back
         * to its [B2bTerm]. Needed at the internal `Map<String, String?>` boundaries kotlin-invoice's
         * application layer keeps for its per-owner field-value maps (`ReferenceDataFacade`) — those
         * maps are keyed by the display string, not the wire [de.littletrotter.shared.B2bValueDto],
         * so this is the roundtrip counterpart of [B2bTerm.officialCode].
         *
         * @throws IllegalArgumentException if [officialCode] is not `"BT-<code>"` for a known code.
         */
        fun fromOfficialCode(officialCode: String): B2bTerm =
            fromOfficialCodeOrNull(officialCode)
                ?: throw IllegalArgumentException("Unknown B2bTerm official code: $officialCode")

        fun fromOfficialCodeOrNull(officialCode: String): B2bTerm? {
            // "BT-X-nn" must be checked before the plain "BT-" prefix strip below, or its
            // "X-nn" remainder would fail toIntOrNull() and the term would wrongly resolve to null.
            val code = when {
                officialCode.startsWith("BT-X-") ->
                    officialCode.removePrefix("BT-X-").toIntOrNull()?.plus(EXTENDED_CODE_OFFSET)
                officialCode.startsWith("BT-") -> officialCode.removePrefix("BT-").toIntOrNull()
                else -> null
            } ?: return null
            return byCode[code]
        }
    }
}
