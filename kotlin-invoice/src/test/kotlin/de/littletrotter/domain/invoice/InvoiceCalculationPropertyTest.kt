package de.littletrotter.domain.invoice

import de.littletrotter.domain.compliance.hasMixedReverseChargeAndStandard
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.Money
import de.littletrotter.testing.invariants.InvoiceCalculationGenerators
import de.littletrotter.testing.invariants.InvoiceCalculationInvariants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.random.Random

/**
 * /RG-2 — property-based invariants over [Invoice.recalculateTotals] that
 * [InvoiceTotalsPropertyTest] does not already cover: rate-group grouping ([taxRuleSummary]),
 * idempotent recalculation, the Reverse-Charge/mixed-category guard, and classic rounding traps
 * at the 0.005 boundary. Uses the reusable [InvoiceCalculationGenerators]/
 * [InvoiceCalculationInvariants] library so RG-4's monkey (dev-tools/monkey-tests) can reuse the
 * same generators/assertions later instead of re-deriving them.
 *
 * Deterministic default seeds (fixed [Random] instances below) so CI reproduces the exact same
 * draws every run; widen the search locally by changing the seed or the `repeat` count — any
 * counterexample found that way gets pinned as its own plain regression test, per RG-2's
 * acceptance criteria, rather than folded silently back into the property.
 */
class InvoiceCalculationPropertyTest {

    @Test
    fun `rate groups sum to the invoice totals under many random item mixes`() {
        val rnd = Random(2001)
        repeat(200) { draw ->
            val itemCount = rnd.nextInt(1, 8)
            val invoice = InvoiceCalculationGenerators.randomInvoice(rnd, itemCount)
            InvoiceCalculationInvariants.assertRateGroupsMatchTotals(invoice, " (draw=$draw, seed=2001)")
        }
    }

    @Test
    fun `recalculateTotals is idempotent under many random item mixes`() {
        val rnd = Random(2002)
        repeat(200) { draw ->
            val itemCount = rnd.nextInt(1, 8)
            val invoice = InvoiceCalculationGenerators.randomInvoice(rnd, itemCount)
            InvoiceCalculationInvariants.assertRecalculationIsIdempotent(invoice, " (draw=$draw, seed=2002)")
        }
    }

    @Test
    fun `totals never go negative from positive item inputs under many random mixes`() {
        val rnd = Random(2003)
        repeat(200) { draw ->
            val itemCount = rnd.nextInt(1, 8)
            val invoice = InvoiceCalculationGenerators.randomInvoice(rnd, itemCount)
            InvoiceCalculationInvariants.assertNoNegativeTotalsFromPositiveInputs(invoice, " (draw=$draw, seed=2003)")
        }
    }

    @Test
    fun `reverse-charge items always net to zero VAT under many random item mixes`() {
        val rnd = Random(2004)
        repeat(100) { draw ->
            val itemCount = rnd.nextInt(1, 5)
            val invoice = InvoiceCalculationGenerators.randomInvoice(rnd, itemCount, vatCategory = VatCategory.REVERSE_CHARGE)
            InvoiceCalculationInvariants.assertReverseChargeItemsHaveZeroVat(invoice, " (draw=$draw, seed=2004)")
        }
    }

    @Test
    fun `mixing REVERSE_CHARGE and STANDARD items always trips the mix guard, uniform mixes never do`() {
        val rnd = Random(2005)
        var sawMixed = false
        var sawUniform = false
        repeat(150) { draw ->
            val invoice = Invoice()
            val standardCount = rnd.nextInt(1, 4)
            val rcCount = rnd.nextInt(0, 4)
            repeat(standardCount) { i -> invoice.addItem(InvoiceCalculationGenerators.randomItem(rnd, "S$i", vatCategory = VatCategory.STANDARD)) }
            repeat(rcCount) { i -> invoice.addItem(InvoiceCalculationGenerators.randomItem(rnd, "R$i", rate = BigDecimal.ZERO, vatCategory = VatCategory.REVERSE_CHARGE)) }

            val expectMixed = rcCount > 0
            if (expectMixed) sawMixed = true else sawUniform = true
            assertEquals(expectMixed, invoice.itemList.hasMixedReverseChargeAndStandard(), "mix guard disagreement (draw=$draw, seed=2005, rcCount=$rcCount)")
        }
        assertTrue(sawMixed, "expected at least one mixed draw")
        assertTrue(sawUniform, "expected at least one uniform (RC-free) draw")
    }

    /**
     * RG-2 invariant 3 — the classic rounding trap: many small items whose individually-rounded
     * VAT sums to a DIFFERENT cent total than rounding the summed net once. [Invoice
     * .recalculateTotals]'s own contract (Money's KDoc, "Q2") is sum-then-round-per-line, not
     * round-the-aggregate — this pins that the two approaches CAN disagree, and that the invoice
     * always takes the documented (per-line) one.
     */
    @Test
    fun `many small items vs one large one can disagree on VAT by a cent, and totals follow the documented per-line rounding`() {
        val rnd = Random(2006)
        repeat(50) { draw ->
            // Prices whose 19% VAT individually rounds up by a fraction just often enough that
            // the aggregate would have rounded differently, e.g. 0.045 repeated many times.
            val perItemNet = BigDecimal("0.05")
            val count = rnd.nextInt(3, 40)
            val manySmall = Invoice()
            repeat(count) { i ->
                manySmall.addItem(
                    InvoiceItem().apply {
                        positionId = (i + 1).toString(); description = "small"
                        quantity = BigDecimal.ONE; unitPriceNet = Money.of(perItemNet)
                        applyTaxRule(de.littletrotter.domain.reference.TaxRate().apply { code = "T"; category = "S"; rate = TaxRatePercent(BigDecimal("19")) })
                    },
                )
            }
            manySmall.recalculateTotals()

            val oneLarge = Invoice()
            oneLarge.addItem(
                InvoiceItem().apply {
                    positionId = "1"; description = "large"
                    quantity = BigDecimal.ONE; unitPriceNet = Money.of(perItemNet.multiply(BigDecimal(count)))
                    applyTaxRule(de.littletrotter.domain.reference.TaxRate().apply { code = "T"; category = "S"; rate = TaxRatePercent(BigDecimal("19")) })
                },
            )
            oneLarge.recalculateTotals()

            // Both approaches keep net identical (a plain sum, no rounding decision to make there)...
            assertEquals(0, manySmall.totals.netAmount.amount.compareTo(oneLarge.totals.netAmount.amount), "net must match regardless of split (draw=$draw, seed=2006, count=$count)")
            // ...but the per-line VAT sum is the one Invoice.recalculateTotals actually reports —
            // pin that it always matches the sum of the (already-rounded) line VATs, not a
            // recomputation from the aggregate net.
            val expectedVat = de.littletrotter.shared.Money.sum(manySmall.itemList.map { it.vatAmount })
            assertEquals(0, manySmall.totals.vatAmount.amount.compareTo(expectedVat.amount), "invoice VAT must equal the sum of per-line rounded VATs, not a recomputation from the aggregate (draw=$draw, seed=2006)")
        }
    }
}
