package de.littletrotter.testing.invariants

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.taxRuleSummary
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.Money
import de.littletrotter.shared.sum
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

/**
 * Reusable seeded-random generators for the calculation core's property-based
 * suites, built over the REAL reference tax rates and categories this application actually
 * ships (not arbitrary numbers), so a generated invoice always exercises rules
 * [de.littletrotter.shared.tax.TaxRuleResolver]/[de.littletrotter.domain.compliance
 * .TaxRules] would also accept in production. Shared with the dev-tools monkey-tests fuzzer
 * (dev-tools/monkey-tests) once that module exists — kept dependency-free of any single test class for that reason.
 *
 * Every generator takes its [Random] explicitly (never a module-global instance): a caller picks
 * the seed, prints it on failure, and can pin a failing draw as a plain regression test by
 * replaying the same seed — as the property-based tests' acceptance criteria ask for.
 */
object InvoiceCalculationGenerators {

    /** The real domestic rate set (`domain.reference.TaxRate` seed data mirrors these three). */
    val REAL_TAX_RATES: List<BigDecimal> = listOf(BigDecimal("19"), BigDecimal("7"), BigDecimal("0"))

    fun randomTaxRate(rnd: Random, from: List<BigDecimal> = REAL_TAX_RATES): BigDecimal = from[rnd.nextInt(from.size)]

    /** Quantities across the range real positions use: fractional travel hours (invariant 3)
     *  up to bulk product counts, always at [de.littletrotter.domain.invoice.InvoiceItem
     *  .quantity]'s own scale (4). */
    fun randomQuantity(rnd: Random): BigDecimal =
        BigDecimal(0.01 + rnd.nextDouble() * 999.99).setScale(4, RoundingMode.HALF_UP)

    /**
     * Unit prices deliberately weighted toward cent-edge values (`x.xx5`) a third of the time —
     * Invariant 3's "0.005 boundary" rounding traps only surface when the generator actually
     * produces them, not just uniformly random cents.
     */
    fun randomUnitPrice(rnd: Random, maxAmount: Double = 10_000.0): Money {
        val magnitude = rnd.nextDouble() * maxAmount
        val amount = if (rnd.nextInt(3) == 0) {
            // Force a value exactly on a half-cent boundary at 3 decimals, e.g. 12.345.
            BigDecimal(magnitude).setScale(2, RoundingMode.DOWN).add(BigDecimal("0.005"))
        } else {
            BigDecimal(magnitude).setScale(4, RoundingMode.HALF_UP)
        }
        return Money.of(amount)
    }

    fun randomItem(
        rnd: Random,
        positionId: String,
        category: InvoiceItemCategory = InvoiceItemCategory.SERVICE,
        vatCategory: VatCategory = VatCategory.STANDARD,
        // R5 — Reverse Charge always carries a zero rate (the buyer, not the seller, owes the
        // tax); a caller asking for VatCategory.REVERSE_CHARGE never needs to name a rate itself.
        rate: BigDecimal = if (vatCategory == VatCategory.REVERSE_CHARGE) BigDecimal.ZERO else randomTaxRate(rnd),
    ): InvoiceItem = anInvoiceItem(
        positionId = positionId,
        category = category,
        description = "generated-$positionId",
        quantity = randomQuantity(rnd),
        unitPriceNet = randomUnitPrice(rnd),
    ).apply {
        applyTaxRule(
            TaxRate().edit {
                this.code = "GEN_${vatCategory.code}_$rate"
                this.category = vatCategory.code
                this.rate = TaxRatePercent(rate)
                if (vatCategory == VatCategory.REVERSE_CHARGE) exemptionReasonCode = "VATEX-EU-AE"
            },
        )
    }

    /** A whole invoice of [itemCount] independently generated positions, every one already
     *  snapshotted via [randomItem]/[InvoiceItem.applyTaxRule] so [taxRuleSummary] and
     *  [de.littletrotter.domain.compliance.hasMixedReverseChargeAndStandard] can read it. */
    fun randomInvoice(rnd: Random, itemCount: Int, vatCategory: VatCategory = VatCategory.STANDARD): Invoice =
        Invoice().apply {
            repeat(itemCount) { i -> addItem(randomItem(rnd, (i + 1).toString(), vatCategory = vatCategory)) }
        }
}

/**
 * Assertions over [Invoice]/[InvoiceItem] that hold for ANY valid combination of
 * items, independent of the specific values a generator drew. Each function names the seed on
 * failure via the assertion message the caller supplies (or a default), so a counterexample is
 * reproducible without re-running the whole property.
 */
object InvoiceCalculationInvariants {

    /** Invariant 1 — sum(items.net) == totals.net, sum(items.vat) == totals.vat,
     *  net + vat == gross, all at [Money.SCALE]. */
    fun assertTotalsMatchItemSums(invoice: Invoice, seedInfo: String = "") {
        invoice.recalculateTotals()
        val expectedNet = invoice.itemList.map { it.netAmount }.sum()
        val expectedVat = invoice.itemList.map { it.vatAmount }.sum()
        assertEquals(expectedNet, invoice.totals.netAmount, "net total mismatch$seedInfo")
        assertEquals(expectedVat, invoice.totals.vatAmount, "vat total mismatch$seedInfo")
        assertEquals(invoice.totals.netAmount + invoice.totals.vatAmount, invoice.totals.grossAmount, "gross != net + vat$seedInfo")
    }

    /**
     * Invariant 1 — [taxRuleSummary]'s per-rate-group net/vat is the sum of exactly the
     * items sharing that group's key (category/rate/exemption reason), and the groups' sums
     * match the invoice's own totals.
     *
     * Deliberately NOT asserted here: `group.netAmount.percentage(group.key.rate) ==
     * group.vatAmount`. [InvoiceItem.calculateAmounts] rounds each line's VAT individually
     * (`net.percentage(taxRate)`, task A) before [taxRuleSummary] sums the already-rounded line
     * VATs — that is round-then-sum. Recomputing a group's VAT from its *summed* net
     * (sum-then-round) can legitimately land a cent away (two 0.03 net lines at 50 %: each line
     * rounds 0.015 HALF_UP to 0.02, summing to 0.04; the aggregate 0.06 * 50 % rounds to 0.03) —
     * asserting equality there would be a false invariant, not a real one. See
     * [de.littletrotter.domain.invoice.InvoiceCalculationPropertyTest]'s dedicated rounding-trap test for that distinction
     * instead.
     */
    fun assertRateGroupsMatchTotals(invoice: Invoice, seedInfo: String = "") {
        invoice.recalculateTotals()
        val groups = invoice.itemList.taxRuleSummary()
        groups.forEach { group ->
            val members = invoice.itemList.filter {
                it.taxCategory == group.key.category && it.effectiveTaxRate().compareTo(group.key.rate) == 0 &&
                    it.exemptionReasonCode == group.key.exemptionReasonCode
            }
            val expectedNet = members.map { it.netAmount }.sum()
            val expectedVat = members.map { it.vatAmount }.sum()
            assertEquals(0, expectedNet.amount.compareTo(group.netAmount.amount), "rate-group net mismatch for ${group.key}$seedInfo")
            assertEquals(0, expectedVat.amount.compareTo(group.vatAmount.amount), "rate-group vat mismatch for ${group.key}$seedInfo")
        }
        assertEquals(0, groups.map { it.netAmount }.sum().amount.compareTo(invoice.totals.netAmount.amount), "rate-group net sum != invoice net$seedInfo")
        assertEquals(0, groups.map { it.vatAmount }.sum().amount.compareTo(invoice.totals.vatAmount.amount), "rate-group vat sum != invoice vat$seedInfo")
    }

    /** Invariant 1 — recalculating twice in a row must not change any already-computed
     *  total (no drifting/compounding rounding across repeated saves). */
    fun assertRecalculationIsIdempotent(invoice: Invoice, seedInfo: String = "") {
        invoice.recalculateTotals()
        val net = invoice.totals.netAmount
        val vat = invoice.totals.vatAmount
        val gross = invoice.totals.grossAmount
        val itemAmounts = invoice.itemList.map { Triple(it.netAmount, it.vatAmount, it.grossAmount) }

        invoice.recalculateTotals()

        assertEquals(net, invoice.totals.netAmount, "netAmount drifted on a second recalculation$seedInfo")
        assertEquals(vat, invoice.totals.vatAmount, "vatAmount drifted on a second recalculation$seedInfo")
        assertEquals(gross, invoice.totals.grossAmount, "grossAmount drifted on a second recalculation$seedInfo")
        assertEquals(itemAmounts, invoice.itemList.map { Triple(it.netAmount, it.vatAmount, it.grossAmount) }, "item amounts drifted on a second recalculation$seedInfo")
    }

    /** Invariant 1 — a totals set built purely from positive item inputs is never negative. */
    fun assertNoNegativeTotalsFromPositiveInputs(invoice: Invoice, seedInfo: String = "") {
        invoice.recalculateTotals()
        assertFalse(invoice.totals.netAmount.isNegative(), "unexpected negative net from positive inputs$seedInfo")
        assertFalse(invoice.totals.vatAmount.isNegative(), "unexpected negative vat from positive inputs$seedInfo")
        assertFalse(invoice.totals.grossAmount.isNegative(), "unexpected negative gross from positive inputs$seedInfo")
    }

    /** Invariant 2 — every REVERSE_CHARGE item nets to zero VAT. */
    fun assertReverseChargeItemsHaveZeroVat(invoice: Invoice, seedInfo: String = "") {
        invoice.recalculateTotals()
        invoice.itemList.filter { it.taxCategory == VatCategory.REVERSE_CHARGE }.forEach {
            assertEquals(Money.ZERO, it.vatAmount, "a REVERSE_CHARGE item must carry zero VAT$seedInfo")
        }
    }
}
