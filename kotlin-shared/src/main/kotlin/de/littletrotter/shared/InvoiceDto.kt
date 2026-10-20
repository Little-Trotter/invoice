package de.littletrotter.shared

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import java.math.BigDecimal

data class InvoiceNumberDto(val value: String = "")

/**
 * The invoice's date fields (issue date, service period, delivery, due date, payment term
 * length), grouped out of the former 39-parameter flat [InvoiceDto].
 */
data class InvoiceDatesDto(
  val invoiceDate: String? = null, // ISO date
  val servicePeriodFrom: String? = null,
  val servicePeriodTo: String? = null,
  /** BT-72 *Actual delivery date* (ISO date) — a dedicated field. `null` means "not
   *  captured"; the fallback to [servicePeriodTo] (BT-74) and then [invoiceDate] happens only at
   *  export time, so a check "delivery date within the service period" can tell the two terms
   *  apart at all. */
  val deliveryDate: String? = null,
  val dueDate: String? = null,
  val paymentTermsDays: Int = 0,
  /**
   * The chosen payment term's own reference-data name, already
   * resolved for the invoice's document language (EN missing
   * falls back to DE) and already snapshot-aware (the frozen `payment_term_snapshot_name` wins
   * for an already-ISSUED invoice, same rule the overview list/detail view use). `null` only when
   * the invoice has no payment term at all — the one remaining case the `DocumentTexts`
   * date-composed sentences (`paymentTerms.net`/`dueBy`/`immediate`) are still for. Both BT-20
   * (`MustangTransactionBuilder.resolvePaymentTermDescription`) and the printed payment line
   * (`InvoiceReportContextBuilder.buildPaymentDueText`) BEGIN with this same text, so the two
   * outputs can never disagree on the term name; they diverge only where a cash discount
   * ([InvoiceDto.cashDiscount]) is present: BT-20 then carries an additional, structured
   * `#SKONTO#` line for BR-DE-18, the printed PDF a human-readable Skonto sentence.
   */
  val paymentTermName: String? = null,
)

/**
 * References/BT-refs grouped out of the former flat [InvoiceDto]: buyer/order/offer
 * references, the free project/cost-center numbers, and the preceding-invoice reference (BG-3,
 * only set on a credit note).
 */
data class InvoiceReferencesDto(
  /** BT-10 — `ram:BuyerReference`; a public buyer's Leitweg-ID lands here. Never BT-13. */
  val buyerReference: String? = null,
  /** BT-13 — the buyer's purchase order reference
   *  (`ram:BuyerOrderReferencedDocument/ram:IssuerAssignedID`). Additive since the BT-13/BT-10
   *  split; a legacy message without it exports no `BuyerOrderReferencedDocument` at all, which is
   *  conformant (BT-13 is optional in EN 16931). Distinct from both [buyerReference] (BT-10) and
   *  [orderReference] (BT-15, the *seller's* order number) — see
   *  `de.littletrotter.domain.invoice.Invoice.purchaseOrderReference`. */
  val purchaseOrderReference: String? = null,
  val orderReference: String? = null,
  val orderReferenceDate: String? = null,
  val offerReference: String? = null,
  val offerDate: String? = null,
  /** BT-11 *Project reference* — `ram:SpecifiedProcuringProject/ram:ID` on export.
   *  `BOUND` in the B2B catalog (`bound_property = 'projectNumber'`); the frontend decides whether
   *  this value is collected via a field-module, the export layer skips the element when
   *  null/blank. */
  val projectNumber: String? = null,
  /** Optional cost-center at invoice level. */
  val costCenter: String? = null,
  /** BT-25 *Preceding invoice reference* — the number of the invoice this credit note cancels
   *  (BG-3). Only set on a credit note. */
  val originalInvoiceNumber: String? = null,
  /** BT-26 (ISO date) — issue date of the preceding document (BG-3). `null` means the element is
   *  omitted on export; never derived from the credit note's own invoice date. */
  val originalInvoiceDate: String? = null,
)

/**
 * Currency and the three money totals, plus the reverse-charge flag (grouped with totals
 * because it is derived from the same tax computation, not a standalone header field).
 */
data class InvoiceTotalsDto(
  // Typed as Currency (was a bare Int? ordinal): every consumer used to repeat its own
  // Currency.entries.getOrNull(...)  ?: EUR fallback on an already-materialized InvoiceDto, which
  // coverage flagged as unreachable dead code (a valid InvoiceTotalsDto's currency can never
  // actually be out-of-range once Kotlin's type system enforces it). [CurrencyOrdinalSerializer]/
  // [CurrencyOrdinalDeserializer] keep the wire byte-identical (still a plain ordinal Int, still
  // EUR for `null`/out-of-range) — Currency.ofOrdinal's own fallback now lives in exactly the one
  // place that fallback is genuinely needed: parsing a NATS payload nobody has type-checked yet.
  @JsonSerialize(using = CurrencyOrdinalSerializer::class)
  @JsonDeserialize(using = CurrencyOrdinalDeserializer::class)
  val currency: Currency = Currency.EUR,
  val netAmount: Money = Money.ZERO,
  val vatAmount: Money = Money.ZERO,
  val grossAmount: Money = Money.ZERO,
  /** Derived from any item's `taxCategory == "AE"`, not from a freely settable legacy
   *  flag — the producer still writes it on every message, consistent with the item categories,
   *  so a consumer without item-level categories can still trust this field rather
   *  than guessing from a 0% rate. */
  val reverseCharge: Boolean = false,
)

/**
 * Language/format/profile metadata grouped out of the former flat [InvoiceDto].
 */
data class InvoiceMetaDto(
  /** BT LanguageID for the XML export — a manually settable, per-export override
   *  (`XmlExportServiceImpl`). Distinct from [documentLanguage], which drives the PDF renderer's
   *  label/format language and is frozen once at invoice creation; this field was deliberately
   *  left untouched by that change. */
  val languageId: String? = null,
  /** The frozen document language ("de"/"en") that governs the PDF renderer's label
   *  bundle, number/date formatting and unit labels. Resolved once at invoice creation
   *  (`resolveDocumentLanguage`) and never re-derived. */
  val documentLanguage: String? = null,
  val documentStatusReasonCode: String? = null,
  val freeText: String? = null,
  /** The invoice-owned notice for private customers of a crafts business (keep the invoice,
   *  labour costs can be stated in the tax return). Copied onto the invoice like the seller's
   *  note and never re-resolved from master data; the PDF prints it as a second paragraph of the
   *  note box and the XML emits it as an additional invoice note. `null` = not printed. */
  val consumerNote: String? = null,
  /** The export profile id ("XRechnung"/"EN16931") kotlin-invoice's finalization gate
   *  already resolved for this invoice (`resolveExportProfileId`).
   *  `XmlExportServiceImpl.resolveProfile()` prefers this over re-deriving it locally so the
   *  gate and the actual export can never disagree; null on legacy messages falls back to the
   *  old local heuristic. */
  val profileId: String? = null,
  /** Optional — which seller VAT registration (ISO 3166-1 alpha-2, e.g. `"LU"`) this
   *  invoice is billed under. `null` = the German default (today's behavior, unchanged
   *  `TaxRuleResolver` path — not importable here,
   *  kotlin-shared has no dependency on kotlin-invoice's domain). */
  val sellerVatRegistrationCountry: String? = null,
  /** Optional — `"GERMAN"` | `"INTERNATIONAL"`, only meaningful while
   *  [sellerVatRegistrationCountry] is non-null. `null`/absent reads as the German default, same
   *  as before this field existed. */
  val invoiceFormat: String? = null,
  /** The [de.littletrotter.shared.wording.WordingProfile.code] active for this instance at
   *  the moment `kotlin-invoice`'s `DocumentJobPublishTransaction` published this job (never
   *  retroactive: an already-issued document keeps whichever bytes it was rendered with).
   *  `kotlin-xpdf`'s print-label loader (`ReportFormatHelpers.Labels.label`) resolves it via
   *  [de.littletrotter.shared.wording.WordingProfile.fromCode]; `null`/unknown reads as
   *  [de.littletrotter.shared.wording.WordingProfile.DEFAULT], the same fail-open rule the active
   *  gate itself applies. */
  val wordingProfile: Int? = null,
)

/**
 * Early-payment discount (Skonto) as the domain computed it once, for every consumer to read
 * rather than recompute: the deadline is the invoice date plus [days] calendar days; [amount] is
 * [percent] of the gross invoice amount rounded half-up to cents in one step; [payableAmount] is the
 * gross amount less [amount]. Absent (`null` on [InvoiceDto.cashDiscount]) when the payment term
 * carries no discount, the gross amount is not positive, or the document is a cancellation.
 */
data class InvoiceCashDiscountDto(
  /** Percentage points as maintained on the payment term (`2`, `2.5`), no trailing normalisation. */
  val percent: BigDecimal,
  val days: Int,
  /** ISO date of the last day the discount may be taken. */
  val deadline: String,
  val amount: Money,
  val payableAmount: Money,
)

/**
 * The former flat 39-parameter [InvoiceDto] grouped into cohesive
 * nested DTOs ([InvoiceDatesDto], [InvoiceReferencesDto], [InvoiceTotalsDto], [InvoiceMetaDto]),
 * the same pattern already established for parties/items ([PartyDto], [ShipToDto]). Hard cut, no
 * `InvoiceDtoV2`, no tolerant reader: `kotlin-invoice` (producer) and `kotlin-xpdf` (consumer)
 * deploy together, so in-flight NATS messages against the old shape are expendable.
 */
data class InvoiceDto(
  val id: Long? = null,
  val invoiceNumber: InvoiceNumberDto = InvoiceNumberDto(),
  /** BT-3 *Invoice type code* (UNTDID 1001): `"380"` invoice, `"381"` credit note. `null`/absent
   *  reads as `"380"` — the behavior of every message before this field existed, which never
   *  carried a type code and was therefore always exported as an invoice. */
  val typeCode: String? = null,
  val senderProfile: PartyDto? = null,
  val recipient: PartyDto? = null,
  /** BG-13, only present when a delivery address exists **and** differs in substance
   *  from the billing address used for [recipient]. See [ShipToDto] for why this is not folded
   *  into [recipient] itself. */
  val shipTo: ShipToDto? = null,
  val items: List<InvoiceItemDto> = emptyList(),
  /** Receipts attached to the invoice **as a whole** (Auftragsbestätigung,
   *  Vertrag, Stundenzettel), not to any position. Distinct from [InvoiceItemDto.receipts]; the
   *  same receipt may appear in both. The PDF annex renders one page per distinct
   *  objectKey and labels the header accordingly. */
  val receipts: List<ReceiptDto> = emptyList(),
  val dates: InvoiceDatesDto = InvoiceDatesDto(),
  val references: InvoiceReferencesDto = InvoiceReferencesDto(),
  val totals: InvoiceTotalsDto = InvoiceTotalsDto(),
  val meta: InvoiceMetaDto = InvoiceMetaDto(),
  // ── Erfasste B2B-Frei-Felder des Rechnungskopfs (owner_type INVOICE) ──
  /** `HEADER`-level FREE B2B values of this invoice (BT-19/BT-56/BT-58/BT-12/BT-18, …). Empty
   *  = no B2B profile active (Community) — the wire is unchanged. The mustang XML injection of
   *  these values is a named requirement on the EXPORT holder (this DTO holds
   *  WIRE/KERN, not EXPORT); the data is in place so that mapping is purely additive later. */
  val b2bValues: List<B2bValueDto> = emptyList(),
  /** `true` when the invoice's status is `DRAFT` or `IN_REVIEW` (not yet
   *  finalized), set by `kotlin-invoice`'s `InvoiceDtoMapper` from the entity's own
   *  `InvoiceStatus`. `kotlin-xpdf`'s `PdfExportServiceImpl.completeExport` reads only this flag —
   *  never the status enum itself, which stays out of the wire (kotlin-shared has no dependency
   *  on kotlin-invoice's domain) — to decide whether the rendered PDF carries the two-line draft
   *  watermark instead of the demo text. Default `false` keeps every payload/tolerant
   *  reader that predates this field reading as "finalized/unchanged", the same as today. */
  val draft: Boolean = false,
  /** `true` when this invoice's own type code is `384` (`CORRECTED_INVOICE`), set by
   *  `kotlin-invoice`'s `InvoiceDtoMapper` from `Invoice.typeCode` (previously
   *  `381`/`CREDIT_NOTE`). `kotlin-xpdf`'s `PdfExportServiceImpl.applyWatermark` reads
   *  this first, ahead of [draft]: a cancellation document is itself the correction, so it must carry
   *  the cancellation mark even though its own status is also `CANCELLED` (mark table row 1 wins over
   *  row 2). Default `false` keeps every payload before this field existed reading as an ordinary
   *  invoice, unchanged. The wire name stays `storno`: it is part of the persisted job payload. */
  @param:JsonProperty("storno") @get:JsonProperty("storno") val cancellation: Boolean = false,
  /** Skonto as computed by the domain (see [InvoiceCashDiscountDto]); `null` when none applies. */
  val cashDiscount: InvoiceCashDiscountDto? = null,
)
