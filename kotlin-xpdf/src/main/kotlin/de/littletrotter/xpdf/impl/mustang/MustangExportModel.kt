package de.littletrotter.xpdf.impl.mustang

import java.math.BigDecimal

/**
 * An export-oriented view of an [de.littletrotter.shared.InvoiceDto], holding
 * only what a Mustang/ZUGFeRD transaction needs, already resolved (blank -> null, fallbacks
 * applied) so the second stage ([MustangTransactionBuilder]) is a mechanical scan of this model,
 * not a repeat of the same null/blank decisions. This file and [MustangPartyModel]/
 * [MustangItemModel]/[MustangReceiptRefModel] below have no Mustang import — only
 * [MustangTransactionBuilder] does.
 *
 * ## Why the B2B terms are typed fields here, not XPaths
 *
 * The FREE terms of the B2B catalog arrive generically (`InvoiceDto.b2bValues`, a list of
 * `btCode -> value`) and the catalog carries an `xpath` per term. It is tempting to walk that list
 * and hang each value into the finished document at its XPath. That would produce **schema-invalid
 * XML**: in UN/CEFACT CII the child order of every complex type is fixed (`xsd:sequence`), so an
 * element inserted after the fact lands in whatever position the insertion happens to pick, not in
 * the position the schema demands. The catalog's `xpath` column is therefore purely documentary —
 * see the column comment in `seed/V906__seed_b2b_xpath_cii.sql`.
 *
 * The actual binding is one named field per business term below, mapped in
 * [MustangExportModelMapper] and handed to a typed Mustang setter in [MustangTransactionBuilder].
 * Mustang's writer emits every element in schema order, so the order stays correct by construction.
 *
 * **BT-19 (buyer accounting reference) has deliberately no *header* field here.** Mustang 2.25.0
 * (checked again against 2.26.0, the newest release as of 2026-08-30 — no header
 * setter was added) writes
 * `ram:ReceivableSpecifiedTradeAccountingAccount` only on the *line* level (BT-133,
 * `Item.setAccountingReference`); `IExportableTransaction` has no header-level counterpart, and the
 * element sits deep inside `ram:ApplicableHeaderTradeSettlement`'s schema sequence, past every
 * child `IZUGFeRDTradeSettlement.getSettlementXML` actually writes. A field that nothing in Mustang
 * can write would only look like the term were exported.
 *
 * What the exported document actually needs (Nekar treats the cost center as
 * mandatory) is not the header element itself but *some* trace of the value on the wire. Since
 * Mustang can write the line-level counterpart, [MustangItemModel.accountingReference] carries the
 * resolved value ([MustangExportModelMapper.resolveAccountingReference]: BT-19 wins over
 * `Invoice.references.costCenter` when both are present) and is fanned out identically to every
 * line by [MustangExportModelMapper]. See the updated cases in `En16931TermsExportTest` for the
 * precedence proof.
 *
 * The header element itself is now *also* written, via a route-agnostic
 * post-processing seam ([de.littletrotter.xpdf.impl.Bt19HeaderInjector], applied by
 * [de.littletrotter.xpdf.impl.XmlExportServiceImpl.generate]) rather than a Mustang
 * setter — see that object's own class KDoc for the schema-position reasoning and the "remove once
 * Mustang writes BT-19 itself" marker. The BT-133 line fanout above is unchanged
 * and stays — EN 16931 permits both the header and the line-level accounting reference at once, and
 * Nekar consumers already depend on the line-level one, so removing it would be a regression,
 * not a cleanup.
 */
data class MustangExportModel(
    val number: String,
    /** BT-10 — `getReferenceNumber()` → `ram:BuyerReference`, and nothing else. Carries a public
     *  buyer's Leitweg-ID; it used to feed `getBuyerOrderReferencedDocument()` (BT-13) as well,
     *  which claimed an order number where only a routing address stood. */
    val buyerReference: String?,
    /** BT-13 — `getBuyerOrderReferencedDocument()` → `ram:BuyerOrderReferencedDocument`. The
     *  **buyer's** order number, from its own field (`InvoiceDto.references.purchaseOrderReference`). */
    val purchaseOrderReference: String?,
    /** BT-15 — the *seller's* own order/quote reference
     *  (`ram:SellerOrderReferencedDocument`); neither [buyerReference] nor
     *  [purchaseOrderReference]. */
    val orderReference: String?,
    /** Issue date belonging to [orderReference]. Mustang 2.25.0 offers no CII slot for it at the
     *  header level, so it reaches no element today — see [MustangTransactionBuilder]. */
    val orderReferenceDate: String?,
    val invoiceDate: String?,
    /**
     * BT-72 — already resolved through the fallback chain `InvoiceDto.dates.deliveryDate ->
     * servicePeriodTo (BT-74)`; the builder applies the last step (`-> invoiceDate`) because it
     * needs a non-null `Date` there anyway. Before V031 no `deliveryDate` field existed and this
     * *was* BT-74 — the chain keeps every pre-V031 invoice exporting byte-identically.
     */
    val deliveryDate: String?,
    /** BT-73 — Abrechnungszeitraum Beginn (BG-14). Bis V031 nicht exportiert. */
    val billingPeriodFrom: String?,
    /** BT-74 — Abrechnungszeitraum Ende (BG-14). Bis V031 nicht exportiert. */
    val billingPeriodTo: String?,
    /** BT-3 — UNTDID 1001, `"380"` invoice / `"381"` credit note. Resolved (never null): a
     *  message without a type code reads as `"380"`, the behaviour before the field existed. */
    val documentTypeCode: String,
    /** BT-25 — number of the invoice this credit note cancels (BG-3). Null on a normal invoice. */
    val precedingInvoiceNumber: String?,
    /** BT-26 — issue date of that preceding document. Null when the credit note predates V033
     *  or the original carried no issue date; the element is then omitted, never guessed. */
    val precedingInvoiceDate: String?,
    val currencyCode: String,
    val sender: MustangPartyModel?,
    val recipient: MustangPartyModel?,
    val items: List<MustangItemModel>,
    val paymentTermsDays: Int,
    val dueDate: String?,
    /**
     * (PO decisions of 06.09.2026) — the chosen payment term's own resolved name
     * ([de.littletrotter.shared.InvoiceDatesDto.paymentTermName]), already snapshot-aware and
     * already resolved for [documentLanguage] (EN missing falls back to DE) upstream in
     * `InvoiceDtoMapper`. When present, BT-20 BEGINS with this text, verbatim — the same field
     * the payment due text builder reads for the printed line, so the two outputs can never
     * disagree on the term name. `null` only for an invoice with no payment term at all, the one
     * case where the transaction builder still composes from payment terms and due date.
     * A cash discount ([cashDiscountDays]/[cashDiscountPercent]) appends a structured
     * `#SKONTO#` line to BT-20 beyond this name; the printed PDF appends its own Skonto sentence
     * instead — the two texts start alike and diverge only there.
     */
    val paymentTermName: String? = null,
    /** BR-DE-18 — Skonto discount period in days, mirrored from [de.littletrotter.shared.InvoiceCashDiscountDto.days].
     *  `null` when the invoice carries no cash discount; always set together with [cashDiscountPercent]. */
    val cashDiscountDays: Int? = null,
    /** BR-DE-18 — Skonto percentage points, mirrored from [de.littletrotter.shared.InvoiceCashDiscountDto.percent].
     *  `null` when the invoice carries no cash discount; always set together with [cashDiscountDays]. */
    val cashDiscountPercent: BigDecimal? = null,
    /**
     * The resolved export profile id (`"EN16931"`, `"XRechnung"`, `"EXTENDED"`, …), the same
     * value [de.littletrotter.xpdf.impl.XmlExportServiceImpl.resolveProfile] picks for
     * [org.mustangproject.ZUGFeRD.Profiles.getByName]. Carried here because
     * [MustangTransactionBuilder] must gate the EXTENDED-only structured Skonto element
     * (`ram:ApplicableTradePaymentDiscountTerms`) itself: Mustang's writer
     * (`ZUGFeRD2PullProvider`) emits that element unconditionally whenever
     * `IZUGFeRDPaymentTerms.getDiscountTerms()` is non-null, with no profile check of its own.
     * Defaults to `"EN16931"` (never EXTENDED) so the many existing call sites that build a
     * model without naming a profile keep exporting the discount as BT-20 text only, unchanged.
     */
    val exportProfile: String = "EN16931",
    val senderIban: String?,
    /**
     * BT-12 *Contract reference* — FREE catalog term, captured per invoice.
     * Goes to `ram:ApplicableHeaderTradeAgreement/ram:ContractReferencedDocument/ram:IssuerAssignedID`.
     */
    val contractReference: String? = null,
    /**
     * BT-18 *Invoiced object identifier* — FREE catalog term. Goes to
     * `ram:ApplicableHeaderTradeAgreement/ram:AdditionalReferencedDocument/ram:IssuerAssignedID`
     * with `ram:TypeCode` 130, which Mustang sets itself for this slot.
     */
    val invoicedObjectIdentifier: String? = null,
    /**
     * BG-13. `null` when
     * [de.littletrotter.shared.InvoiceDto.shipTo] was `null` (no delivery address, or one
     * identical to the billing address); the builder then calls `getDeliveryAddress()`'s
     * Mustang default (`null`), so `ram:ShipToTradeParty` is omitted exactly as before this
     * field existed.
     */
    val shipTo: MustangShipToModel? = null,
    /**
     * BT-22 *Invoice note*, from `Invoice.freeText` (`InvoiceMetaDto.freeText`). Goes to
     * the first header-level `ram:IncludedNote` (`getNotes()` in [MustangTransactionBuilder]);
     * `null` when blank, so the element is omitted rather than emitted empty.
     */
    val headerNote: String? = null,
    /**
     * BT-22 *Invoice note*, from `InvoiceMetaDto.consumerNote`: the hint for private customers.
     * Goes to a second header-level `ram:IncludedNote` after [headerNote]; `null` when blank.
     */
    val consumerNote: String? = null,
    /**
     * BT-11 *Project reference*, `BOUND` to `InvoiceDto.references.projectNumber` (no
     * FREE catalog value, no second input field). Goes to
     * `ram:ApplicableHeaderTradeAgreement/ram:SpecifiedProcuringProject/ram:ID` via Mustang's own
     * `getSpecifiedProcuringProjectID()` hook (`IExportableTransaction`, 2.25.0) — no
     * `enrichInvoiceXml` fixup needed. `null` when blank, so the element is omitted rather than
     * emitted empty (PEPPOL-EN16931-R008).
     */
    val projectReference: String? = null,
    /**
     * BG-24 *Additional supporting documents* — one entry per **unique** receipt referenced by
     * this invoice, already deduplicated by [MustangExportModelMapper] across the invoice-level
     * receipt list (`InvoiceDto.receipts`) and every item's receipts (`InvoiceItemDto.receipts`):
     * the same receipt commonly hangs on both, and BG-24 admits it once. Deliberately
     * document-level, not per-line: XRechnung 3.x's CII-SR-113 forbids any
     * `AdditionalReferencedDocument` inside `SpecifiedLineTradeAgreement` at all, so there is no
     * conformant per-line shape to fall back to — see [MustangTransactionBuilder.build]'s
     * `getAdditionalReferencedDocuments()` override.
     */
    val receipts: List<MustangReceiptRefModel> = emptyList(),
    /**
     * One entry per `TRIP` item whose workbook was actually resolved
     * ([de.littletrotter.shared.TripExportDto.resolvedWorkbookBase64] non-null), document-level
     * like [receipts]: BG-24 (`AdditionalReferencedDocument`) is a header-level group in the CII
     * schema regardless of which position an attachment belongs to (the same XRechnung 3.x
     * CII-SR-113 constraint [MustangTransactionBuilder.build]'s `getAdditionalReferencedDocuments()`
     * KDoc documents for receipts). [MustangTransactionBuilder] folds this list and [receipts] into
     * one unified attachment loop — never a second copy of it.
     */
    val tripWorkbookAttachments: List<MustangWorkbookAttachmentModel> = emptyList(),
    /**
     * The frozen document language ("en"/"de"), already normalised by
     * [de.littletrotter.xpdf.impl.resolveDocumentRenderLanguage] (the same rule
     * [de.littletrotter.xpdf.impl.InvoiceReportContextBuilder.resolveRenderLanguage] applies to
     * the PDF, one rule, not two). Drives BT-20's wording in the wire format — the wire's
     * payment-terms text must follow the document language exactly like the printed one does.
     */
    val documentLanguage: String = "de",
)

/**
 * The export-oriented view of [de.littletrotter.shared.ShipToDto]. Deliberately its own,
 * slimmer type rather than [MustangPartyModel]: BG-13 has no VAT id, contact, or identifier slots
 * on the wire, and [MustangTransactionBuilder] maps it to a bare `TradeParty` via Mustang's
 * `getDeliveryAddress()`, not [MustangTransactionBuilder.buildParty]'s sender/recipient path.
 */
data class MustangShipToModel(
    val name: String?,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String,
)

data class MustangPartyModel(
    val name: String?,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String,
    val vatId: String?,
    val taxNumber: String?,
    /** BT-29/46 — falls back to a bare `globalId` (assumed GLN) only for legacy messages predating scheme-carrying identifiers. */
    val globalIdSchemeId: String?,
    val globalIdValue: String?,
    /** BT-30/47. */
    val legalRegistrationSchemeId: String?,
    val legalRegistrationValue: String?,
    /** BT-34/49 — never derived from a bare [email]; only a legacy message without a
     *  scheme-carrying electronic address falls back to `setEmail`'s synthetic "EM" scheme. */
    val electronicAddressSchemeId: String?,
    val electronicAddressValue: String?,
    val email: String?,
    val phone: String?,
    /**
     * BG-6 (BT-41/BT-42/BT-43, seller contact) — the sender gets a `Contact` block built from
     * [name]/[phone]/[email], the recipient doesn't. Same as the former `withContact` flag.
     */
    val includeContact: Boolean,
    /**
     * BT-56 *Buyer contact point* — FREE catalog term. Set on the recipient
     * only, and only from a captured B2B value: BG-8 is the *buyer's* contact, never the recipient's
     * own name, so it is not derived from [name].
     */
    val contactPointName: String? = null,
    /**
     * BT-58 *Buyer contact email address* — FREE catalog term. Same rule as [contactPointName]: a
     * captured value, never derived from [email] (that one is BT-49, the electronic address).
     */
    val contactPointEmail: String? = null,
)

data class MustangItemModel(
    /** BT-153 *Item name* — Mustang's `Product` name. */
    val description: String,
    /** BT-154 *Item description* — Mustang's `Product` description, the second constructor
     *  argument that used to be hardcoded `""`. Empty/blank is normalized to `""` here because
     *  Mustang's writer skips the element on an empty string but not on a blank one. */
    val itemDescription: String,
    /** BT-134 — Beginn des Positions-Leistungszeitraums (BG-26). */
    val servicePeriodFrom: String?,
    /** BT-135 — Ende des Positions-Leistungszeitraums (BG-26). */
    val servicePeriodTo: String?,
    /** Already resolved: `item.unitCode` or the UN/ECE Rec 20 fallback `"C62"`. */
    val unitCode: String,
    /** `item.taxRatePercent` — never absent on the wire. */
    val taxRatePercent: BigDecimal,
    /**
     * BT-146, already sign-normalized by [MustangExportModelMapper]: never negative.
     * The domain (`InvoiceItem`) still stores a rebate/reduction line with the sign in
     * `unitPriceNet`, not in `quantity` (see the B1 comment on `InvoiceItem.validate`); EN 16931
     * BR-27/BR-28 and the Nekar policy both require the opposite convention on the wire —
     * non-negative unit price, negative quantity ([quantity]). This is a wire-format concern only:
     * `quantity * unitPriceNet` is invariant under `(q, p) -> (-q, -p)`, so the line net amount
     * (BT-131) is unaffected by the flip.
     */
    val unitPriceNet: BigDecimal,
    /**
     * BT-129 — already sign-normalized by [MustangExportModelMapper]: carries the negative sign of
     * a rebate/reduction line whose domain `unitPriceNet` was negative. See [unitPriceNet]'s KDoc.
     */
    val quantity: BigDecimal,
    val taxCategoryCode: String?,
    val exemptionReasonCode: String?,
    val exemptionReasonText: String?,
    /** Always resolved (default `"ETIM"`) — see [productClassificationCode]: unused unless that
     *  is non-null, but never itself null. */
    val productClassificationScheme: String,
    val productClassificationCode: String?,
    /**
     * BT-155 *Item Seller's identifier* — FREE catalog term, captured per
     * position. Goes to `ram:SpecifiedTradeProduct/ram:SellerAssignedID`.
     */
    val sellerItemIdentifier: String? = null,
    /**
     * BT-156 *Item Buyer's identifier* — FREE catalog term. Goes to
     * `ram:SpecifiedTradeProduct/ram:BuyerAssignedID`.
     */
    val buyerItemIdentifier: String? = null,
    /**
     * BT-133 *Invoice line's accounting reference*
     * (`ram:ReceivableSpecifiedTradeAccountingAccount`, `Item.setAccountingReference`). The single
     * cost-center value resolved once at invoice level (BT-19 FREE value, falling back to
     * `Invoice.references.costCenter`) and fanned out identically to every line — see the class
     * KDoc of [MustangExportModel] for the header-level counterpart (a separate
     * post-processing seam rather than a Mustang setter). `null` when neither source carries a
     * value, so the element is omitted rather than emitted empty.
     */
    val accountingReference: String? = null,
    /**
     * BT-127 *Invoice line note*: for a `TRIP` item with `detailedOnInvoice`, a plain-text
     * summary of its expenses/per-diem days (truncated at 1000 characters with "… siehe Anlage",
     * README border case 7), built once in [MustangExportModelMapper] from
     * [de.littletrotter.shared.TripExportDto]. `null` for every non-`TRIP` item and for a `TRIP`
     * item with nothing to summarise (README border case 11) — [MustangTransactionBuilder] then
     * emits no `ram:IncludedNote` at all, via `Item.addNote`.
     */
    val lineNote: String? = null,
)

data class MustangReceiptRefModel(
    val fileName: String,
    val objectKey: String?,
    /** Becomes BT-123 (`Name`); the file name stands in when no description was entered. */
    val description: String? = null,
)

/**
 * A resolved, ready-to-attach BG-24 workbook: [base64Content] is
 * [de.littletrotter.shared.TripExportDto.resolvedWorkbookBase64] itself (already GET+verified by
 * `JobConsumer`), decoded to bytes only inside [MustangTransactionBuilder]'s attachment loop.
 * [fileName] doubles as both `IssuerAssignedID` (BT-122) and the attachment's `Name` — see
 * [de.littletrotter.shared.TripWorkbookRefDto]'s own KDoc for where it is built
 * (`<invoice-number>-dienstreise-<pos>.xlsx`).
 */
data class MustangWorkbookAttachmentModel(
    val fileName: String,
    val mimeType: String,
    val base64Content: String,
)
