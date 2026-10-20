package de.littletrotter.shared

import com.fasterxml.jackson.annotation.JsonIgnore
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import java.math.BigDecimal

data class InvoiceItemDto(
  val positionId: String = "",
  val description: String = "",
  val quantity: BigDecimal = BigDecimal.ONE,
  val unitPriceNet: Money = Money.ZERO,
  val unitCode: String? = null,
  /** Human-readable German label (e.g. "Stück", "Tage") for PDF display — [unitCode] is the
   *  machine-readable UN/ECE Rec 20 code (e.g. "H87", "DAY"), not suitable for humans and
   *  ambiguous besides (several units share one code). */
  val unitLabel: String? = null,
  val productClassificationScheme: String? = null,
  val productClassificationCode: String? = null,
  val netAmount: Money = Money.ZERO,
  val vatAmount: Money = Money.ZERO,
  /** Mirrors `InvoiceItem.grossAmount` (net + vat, already rounded there); additive so a
   *  consumer never has to re-derive it (the trip position's printed "Brutto: X" subline is its
   *  first reader). */
  val grossAmount: Money = Money.ZERO,
  val receipts: List<ReceiptDto> = emptyList(),
  // ── Full tax rule snapshot, not just a bare rate ────────────────
  /** Rule code, e.g. `"DE_STANDARD_19"`, `"EU_B2B_RC_SERVICE"`. */
  val taxRuleCode: String? = null,
  /** UNTDID 5305 / EN-16931 category: `S`, `Z`, `E`, `AE`, `O`, `G`, `K`. */
  val taxCategory: String? = null,
  /** The frozen rate this position was actually invoiced at (issued item) or the referenced rate
   *  (draft, preview rendering) — see `InvoiceItem.effectiveTaxRate`. Never absent: a DTO cannot
   *  be built without a rate. */
  val taxRatePercent: BigDecimal,
  val exemptionReasonCode: String? = null,
  val exemptionReasonText: String? = null,
  val taxLegalBasis: String? = null,
  /** Optional cost-center assignment for this position. */
  val costCenter: String? = null,
  /** BT-154 — never derived from [description]: a duplicated identical text is not a description. */
  val itemDescription: String? = null,
  val servicePeriodFrom: String? = null,
  val servicePeriodTo: String? = null,
  val b2bValues: List<B2bValueDto> = emptyList(),
  /** System article number from `Article` (kotlin-invoice), independent of B2B profile. */
  val articleNumber: String? = null,
  /** Recipient-specific article designation, resolved at mapping time (not frozen). */
  val buyerArticleDescription: String? = null,
  /** Buyer's item description — recipient-specific label for this position. */
  val buyerItemDescription: String? = null,
  /** SERVICE/PRODUCT/TRIP, the export-side mirror of `InvoiceItemCategory` (kotlin-invoice
   *  cannot be depended on from here, see [InvoiceItemCategory]'s own KDoc). */
  val category: InvoiceItemCategory = InvoiceItemCategory.SERVICE,
  /** Wire field only; non-null exactly when [category] == [InvoiceItemCategory.TRIP]
   *  (enforced in [init], never left to a caller to get right). Read [categoryDetail] instead of
   *  this raw pair — it turns the invariant into a type a consumer cannot get wrong. */
  val trip: TripExportDto? = null,
) {
    init {
        require((category == InvoiceItemCategory.TRIP) == (trip != null)) {
            "Position $positionId: category=$category requires trip to be " +
                if (category == InvoiceItemCategory.TRIP) "non-null" else "null"
        }
    }

    /**
     * The type-safe view of [category]/[trip]: coverage found several consumers
     * re-deriving "is this the TRIP position, and if so where is its detail" from the loose
     * `category`/nullable-`trip` pair, each with its own now-provably-unreachable else/null arm.
     * [category]/[trip] stay on the class for wire (JSON/NATS) compatibility — see their own
     * KDoc — but a consumer should match on this instead: [InvoiceItemCategoryDetail.Trip] simply
     * cannot be constructed without a [TripExportDto], so there is nothing left to null-check.
     */
    @get:JsonIgnore
    val categoryDetail: InvoiceItemCategoryDetail =
        when (category) {
            InvoiceItemCategory.TRIP -> InvoiceItemCategoryDetail.Trip(checkNotNull(trip) { "unreachable: init already required trip non-null for TRIP" })
            else -> InvoiceItemCategoryDetail.NonTrip
        }
}

/**
 * See [InvoiceItemDto.categoryDetail]'s own KDoc: the sealed, compiler-checked
 * counterpart to the wire pair [InvoiceItemDto.category]/[InvoiceItemDto.trip].
 */
sealed interface InvoiceItemCategoryDetail {
    /** Every category other than [InvoiceItemCategory.TRIP] — no trip payload to carry. */
    data object NonTrip : InvoiceItemCategoryDetail

    /** [category] == [InvoiceItemCategory.TRIP] — [export] is never null, unlike the wire
     *  field it is built from. */
    data class Trip(val export: TripExportDto) : InvoiceItemCategoryDetail
}
