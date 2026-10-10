package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.domain.reference.TravelExpenseEntry
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import de.littletrotter.domain.shared.MoneyConverter
import de.littletrotter.domain.shared.NamedEntity
import de.littletrotter.domain.shared.converters.InvoiceItemCategoryConverter
import de.littletrotter.domain.shared.converters.InvoiceItemUnitConverter
import de.littletrotter.domain.value.TaxRatePercent
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.Transient
import jakarta.persistence.Version
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate

@Entity
@Table(name = "invoice_item")
class InvoiceItem : NamedEntity {
    companion object {
        /**
         * The "quantity x price (+ tax)" formula, factored out so the UI amount preview
         * (`InvoiceDetailView.calculateAmounts`) and the Travel dialog can compute the same
         * numbers a saved position will have, instead of maintaining their own copy (task Q2).
         * Pure — no [InvoiceItem] instance required, so it works before one exists.
         */
        fun calculateAmounts(quantity: BigDecimal, unitPriceNet: Money, taxRate: BigDecimal): Triple<Money, Money, Money> {
            // The price is rounded to Money's scale here, not just at persistence: a caller that
            // has not yet gone through a save round-trip (MoneyConverter) could still hold an
            // unrounded in-memory unitPriceNet (e.g. 33.3333). Rounding before the multiply
            // guarantees the calculation always matches what will end up printed and stored —
            // 3 x 33.33 = 99.99, not 3 x 33.3333 rounded afterwards to 100.00 (task A).
            val effectiveUnitPrice = Money.of(unitPriceNet.amount)
            val net = effectiveUnitPrice * quantity
            val vat = net.percentage(taxRate)
            val gross = net + vat
            return Triple(net, vat, gross)
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    var invoice: Invoice? = null

    @field:Size(max = 255)
    var positionId: String = ""

    @Convert(converter = InvoiceItemCategoryConverter::class)
    @Column(nullable = false)
    var category: InvoiceItemCategory = InvoiceItemCategory.SERVICE

    /** BT-153 — item name (mandatory). */
    @Column(columnDefinition = "TEXT")
    var description: String = ""

    /**
     * BT-154 — item description (optional; EN 16931 requires only BT-153).
     * Deliberately no fallback to [description] in export — doubled identical text is noise.
     */
    @Column(name = "item_description", columnDefinition = "TEXT")
    var itemDescription: String? = null

    /**
     * Buyer's item description — a recipient-specific label for this position,
     * independent of BT-153 (the seller's own description) and BT-154 (the detailed description).
     * This is stored directly on the position, not in the B2B key-value table, because it is
     * not a standard EN 16931 business term.
     */
    @Column(name = "buyer_item_description", columnDefinition = "TEXT")
    var buyerItemDescription: String? = null

    /** BT-134 — line-item service period start. Must lie within header period per PeriodConsistencyRules. */
    @Column(name = "service_period_from")
    var servicePeriodFrom: LocalDate? = null

    /** BT-135 — line-item service period end. */
    @Column(name = "service_period_to")
    var servicePeriodTo: LocalDate? = null

    /** TRIP only: `false` (default) prints one summary line, `true` prints indented
     *  1.1/1.2… sub-lines with [tripExpenses] (net only). Ignored for SERVICE/PRODUCT. */
    @Column(name = "trip_detailed_on_invoice", nullable = false)
    var tripDetailedOnInvoice: Boolean = false

    /** TRIP only: a fixed workshop/trip price that replaces the sum of
     *  [tripExpenses]/[perDiemEntries] on the invoice; the delta to [calculatedTripNet] is
     *  Excel-only, never printed. `null` = price the item from its parts. */
    @Column(name = "trip_fixed_net_price", precision = 14, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var tripFixedNetPrice: Money? = null

    @field:Size(max = 100)
    @Column(name = "delivery_note_reference", length = 100)
    var deliveryNoteReference: String? = null

    /** Cost-center for this position. */
    @field:Size(max = 100)
    @Column(name = "cost_center", length = 100)
    var costCenter: String? = null

    @Column(precision = 14, scale = 4)
    var quantity: BigDecimal = BigDecimal.ONE

    @Convert(converter = InvoiceItemUnitConverter::class)
    @Column(nullable = false)
    var unit: InvoiceItemUnit = InvoiceItemUnit.HOUR

    @Column(precision = 14, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var unitPriceNet: Money = Money.ZERO

    /** UN/ECE Rec 20 code for [unit] (Task F, BR-CL-23) — derived, so it can never be missing. */
    val unitCode: String
        get() = unit.uneceCode

    @Transient
    var productClassificationScheme: String? = null

    @Transient
    var productClassificationCode: String? = null

    // Reference to TaxRate entity (optional). Kept alongside numeric taxRate for backward compatibility in migrations/tests.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tax_rate_id")
    var taxRateRef: TaxRate? = null

    /**
     * Which [Article] this position was created from, if any; `null` for a free-text
     * position (never set afterward). Unlike [taxRateRef]/[unitPriceNet]/etc., which are only
     * *copied* from the article at entry time, this is a durable link: it lets
     * [applySellerCountryTaxVariant] re-derive the position's tax whenever
     * [Invoice.sellerVatRegistrationCountry] changes after the position was created, not only at
     * the moment it was first added.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id")
    var articleRef: Article? = null

    // ── R1 — immutable tax snapshot (GoBD) ─────────────────────────
    // All nullable and deliberately NOT auto-populated on each taxRateRef assignment.
    // WHEN in a position's lifecycle is frozen is R4's "atomic position snapshot" concern.
    // R1 supplies only the fields and the explicit [snapshotTaxRule] call, which R4 wires at the right place.

    @field:Size(max = 64)
    @Column(name = "tax_rule_code")
    var taxRuleCode: String? = null

    @field:Size(max = 4)
    @Column(name = "tax_category")
    var taxCategory: String? = null

    @Column(name = "tax_rate_snapshot", precision = 5, scale = 2)
    var taxRateSnapshot: TaxRatePercent? = null

    @field:Size(max = 32)
    @Column(name = "exemption_reason_code")
    var exemptionReasonCode: String? = null

    @Column(name = "exemption_reason_text", columnDefinition = "TEXT")
    var exemptionReasonText: String? = null

    @Column(name = "tax_legal_basis", columnDefinition = "TEXT")
    var taxLegalBasis: String? = null

    /** R4 — typed view of [taxCategory]; the column remains the persisted truth. */
    val taxCategoryEnum: VatCategory? get() = VatCategory.fromCodeOrNull(taxCategory)

    /**
     * A finalized (or cancelled) invoice's positions never change tax snapshot or
     * amounts again (GoBD): both [snapshotTaxRule] and [applyTaxRule] guard on this before
     * writing anything.
     */
    private fun assertSnapshotMutable() {
        val inv = invoice ?: return
        if (inv.isReadOnly()) throw InvoiceImmutableException(inv.invoiceNumber.value)
    }

    /**
     * Freezes [taxRateRef]'s code/category/rate/exemption/legal-basis onto this item. A later
     * change to the referenced [TaxRate] row must never alter an already-snapshotted item's
     * displayed or exported values (RV-02, GoBD) — that's the entire point of these columns
     * existing separately from the live FK.
     */
    fun snapshotTaxRule() {
        assertSnapshotMutable()
        val tr = taxRateRef ?: throw InvoiceValidationException(InvoiceValidationError.ITEM_TAX_RATE_REFERENCE_MISSING)
        taxRuleCode = tr.code
        taxCategory = tr.category
        taxRateSnapshot = tr.rate
        exemptionReasonCode = tr.exemptionReasonCode
        exemptionReasonText = tr.exemptionReasonText
        taxLegalBasis = tr.legalBasis
    }

    /**
     * R4 — selects [rule] and snapshots it in one atomic call (one method that adopts a rule and
     * sets all snapshot fields atomically, never a field-by-field set from outside), instead of
     * the two-step `taxRateRef = rule; snapshotTaxRule()` R1 left as two
     * separate primitives. Callers that need the live-FK-only behavior (e.g. a draft UI preview
     * before committing) can still set [taxRateRef] directly; this is the commit step.
     */
    fun applyTaxRule(rule: TaxRate) {
        assertSnapshotMutable()
        taxRateRef = rule
        snapshotTaxRule()
    }

    /**
     * The non-R5 counterpart to [applyTaxRule]: derives and applies the MWST for a
     * seller VAT registration in [country] off this item's bound [articleRef]
     * ([Article.taxVariantFor]). Never falls back to Germany or any other rate — the same
     * never-silently-guess philosophy as R5's blocking (see
     * [de.littletrotter.shared.tax.TaxRuleResolver]): a position without a bound article,
     * or whose article carries no variant for [country], throws instead of picking a default, so
     * the position cannot be saved/finalized until a matching variant exists.
     */
    fun applySellerCountryTaxVariant(country: Country, referenceFallback: TaxRate? = null) {
        assertSnapshotMutable()
        val variant = articleRef?.taxVariantFor(country) ?: referenceFallback
            ?: throw InvoiceValidationException(InvoiceValidationError.ITEM_ARTICLE_TAX_VARIANT_MISSING)
        applyTaxRule(variant)
    }

    /**
     * Qodana wk39/DuplicatedCode — the position text trio every editor commits together:
     * BT-153 [description], BT-154 [itemDescription] and the [deliveryNoteReference]. Owned by
     * the item (same idiom as [applyTaxRule]) so the lifecycle service's `applyCommandFields`
     * and TripService's `applyTripFields` cannot drift on which text fields travel as one set;
     * each caller maps its own command onto the trio (trip: title/purpose). Pure assignment —
     * the read-only guard stays with the calling services' `invoice.isReadOnly()` checks.
     */
    fun applyPositionText(description: String, itemDescription: String?, deliveryNoteReference: String?) {
        this.description = description
        this.itemDescription = itemDescription
        this.deliveryNoteReference = deliveryNoteReference
    }

    @Column(precision = 14, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var netAmount: Money = Money.ZERO

    @Column(precision = 14, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var vatAmount: Money = Money.ZERO

    @Column(precision = 14, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var grossAmount: Money = Money.ZERO

    var sortOrder: Int = 0

    /** Daily per diem (Verpflegungspauschale) entries. Only populated for
     *  [InvoiceItemCategory.TRIP] items ([validate] rejects a non-empty list otherwise). */
    @OneToMany(mappedBy = "invoiceItem", cascade = [CascadeType.ALL], orphanRemoval = true)
    var perDiemEntries: MutableList<TravelExpenseEntry> = mutableListOf()

    /** A TRIP position's own expense lines (hotel, taxi, mileage, …). Only
     *  meaningful for [InvoiceItemCategory.TRIP]; a child collection, never its own repository
     *  (DDD — [Invoice] stays the aggregate root). Ordered by [TripExpense.sortOrder], the same
     *  display-order contract [positionId]'s sub-line numbering (README border case 8) relies on. */
    @OneToMany(mappedBy = "invoiceItem", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    var tripExpenses: MutableList<TripExpense> = mutableListOf()

    @ManyToMany
    @JoinTable(
        name = "invoice_item_receipt",
        joinColumns = [JoinColumn(name = "invoice_item_id")],
        inverseJoinColumns = [JoinColumn(name = "receipt_id")]
    )
    var receipts: MutableList<Receipt> = mutableListOf()

    @Version
    var version: Long? = 0

    // ── Domain logic ──────────────────────────────────────────────────────────

    fun validate() {
        if (positionId.isBlank()) throw InvoiceValidationException(InvoiceValidationError.ITEM_POSITION_ID_BLANK)
        if (description.isBlank()) throw InvoiceValidationException(InvoiceValidationError.ITEM_DESCRIPTION_BLANK)
        // B1 — a discount/rebate position carries a negative unitPriceNet (and therefore negative
        // net/vat/gross amounts) on purpose; only the aggregated invoice totals must stay
        // non-negative (Invoice.validate()). quantity itself is never negative — a count of
        // hours/pieces is not signed, the sign lives in the price.
        if (quantity < BigDecimal.ZERO) throw InvoiceValidationException(InvoiceValidationError.ITEM_QUANTITY_NEGATIVE)
        // EffectiveTaxRate() now always reads through TaxRatePercent (taxRateSnapshot or
        // taxRateRef.rate, both fail-fast 0..100 at construction), so the former deferred
        // out-of-range check here was dead code.
        // The trip-only fields/children never appear on another category (README border
        // case 5's "eingefroren" rule is a DB-trigger concern, not this in-memory invariant).
        if (category != InvoiceItemCategory.TRIP) {
            if (tripFixedNetPrice != null) throw InvoiceValidationException(InvoiceValidationError.ITEM_TRIP_FIXED_PRICE_WITHOUT_TRIP_CATEGORY)
            if (perDiemEntries.isNotEmpty()) throw InvoiceValidationException(InvoiceValidationError.ITEM_PER_DIEM_ENTRIES_WITHOUT_TRIP_CATEGORY)
        }
        // N15 finding — no negativity check on tripFixedNetPrice here, mirroring
        // unitPriceNet's own policy right above (B1): negateForStorno() negates a trip's
        // fixed price for a credit-note copy exactly like it negates unitPriceNet, and this
        // method runs for that copy too (Invoice.validate()). The "a user-entered fixed price is
        // >= 0" rule lives at creation/update time in TripCommandValidator (application layer),
        // which the storno copy path never goes through.
        tripExpenses.forEach { it.validate() }
    }

    /**
     * For a [InvoiceItemCategory.TRIP] position, quantity/unit are forced
     * (never user-set: `1` / [InvoiceItemUnit.FLAT_RATE]) and the net amount is priced from
     * [tripFixedNetPrice] if set, otherwise from [calculatedTripNet] — the sum of [tripExpenses]
     * and [perDiemEntries]. Every other category keeps the plain "quantity x price" formula.
     */
    fun calculateAmounts() {
        if (category == InvoiceItemCategory.TRIP) {
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.FLAT_RATE
            val net = tripFixedNetPrice ?: calculatedTripNet()
            unitPriceNet = net
            val rate = effectiveTaxRate()
            val vat = net.percentage(rate)
            netAmount = net
            vatAmount = vat
            grossAmount = net + vat
        } else {
            val (roundedNet, roundedVat, roundedGross) = calculateAmountsSnapshot()
            netAmount = roundedNet
            vatAmount = roundedVat
            grossAmount = roundedGross
        }
    }

    /** The trip's price computed from its parts, independent of [tripFixedNetPrice]:
     *  the Excel Δ-column and the UI preview both need this figure regardless of
     *  whether a fixed price is actually set. */
    fun calculatedTripNet(): Money {
        val expenses = tripExpenses.fold(Money.ZERO) { acc, expense -> acc + expense.netAmount }
        val perDiem = perDiemEntries.fold(Money.ZERO) { acc, entry -> acc + entry.calculatedAmount }
        // A storno document carries its per-diem days unchanged (a day's claimable amount is
        // never negative), so their share of the credit is subtracted here instead.
        return if (invoice?.typeCode == InvoiceTypeCode.CORRECTED_INVOICE) expenses - perDiem else expenses + perDiem
    }

    /**
     * README border case 4 (ST-3) — negates this item's price for a storno/credit-note copy:
     * [tripFixedNetPrice] and every [tripExpenses] line's own unit price are negated (quantities
     * untouched, same as [unitPriceNet] elsewhere); [perDiemEntries] are left as-is (a per-diem
     * day's [TravelExpenseEntry.calculatedAmount] is never negative) — [calculatedTripNet]
     * subtracts their share on a storno document instead. Callers must call [calculateAmounts]
     * afterward to re-derive net/vat/gross from the negated inputs — this method only flips the
     * stored inputs themselves.
     */
    fun negateForStorno() {
        unitPriceNet = -unitPriceNet
        tripFixedNetPrice = tripFixedNetPrice?.let { -it }
        tripExpenses.forEach { it.negateForStorno() }
    }

    fun calculateAmountsSnapshot(): Triple<Money, Money, Money> =
        calculateAmounts(quantity, unitPriceNet, effectiveTaxRate())

    /**
     * Reads the frozen [taxRateSnapshot] once one exists; only an item that predates
     * R1/R4 (no snapshot taken yet) falls back to the live [taxRateRef]. Once snapshotted, a
     * later edit to the referenced [TaxRate] row must never change what this returns.
     */
    fun effectiveTaxRate(): BigDecimal {
        taxRateSnapshot?.let { return it.value }
        val taxRate = taxRateRef ?: throw InvoiceValidationException(InvoiceValidationError.ITEM_TAX_RATE_REFERENCE_MISSING)
        return taxRate.rate.value
    }
}
