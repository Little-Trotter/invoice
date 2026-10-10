package de.littletrotter.domain.invoice

import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.Money
import de.littletrotter.shared.plusExact
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The full rule identity a tax summary row must be grouped by: category, rate AND
 * exemption reason, not rate alone. Two 0 %-rules with different categories (`AE` vs. `E` vs.
 * `O`) must stay distinguishable; two positions sharing category+rate+reason collapse into one
 * (Umsetzung §9).
 */
data class TaxRuleSummaryKey(
    val category: VatCategory?,
    val rate: BigDecimal,
    val exemptionReasonCode: String?,
)

data class TaxRuleSummary(
    val key: TaxRuleSummaryKey,
    val netAmount: Money,
    val vatAmount: Money,
    val grossAmount: Money,
)

/** Scale kept while accumulating per-key sums, ahead of the caller-chosen [displayScale]
 *  rounding — wider than any invoice line item's own amount scale to avoid intermediate loss. */
private const val ACCUMULATION_SCALE = 4

/**
 * R4 — groups this item list's amounts by [TaxRuleSummaryKey], reading each item's snapshot via
 * [InvoiceItem.effectiveTaxRate]/[InvoiceItem.taxCategoryEnum]/[InvoiceItem.exemptionReasonCode].
 *
 * Groups by the full tax key rather than by rate alone: two positions can share a rate and still
 * belong to different tax rules (category and exemption reason differ), which a rate-only grouping
 * would silently merge.
 * the same handoff shape as R1 handing `InvoiceItem.snapshotTaxRule()` to R4 itself.
 */
fun List<InvoiceItem>.taxRuleSummary(displayScale: Int = 2, rounding: RoundingMode = RoundingMode.HALF_UP): List<TaxRuleSummary> {
    val totals = mutableMapOf<TaxRuleSummaryKey, Pair<Money, Money>>()
    for (item in this) {
        val key = TaxRuleSummaryKey(item.taxCategoryEnum, item.effectiveTaxRate(), item.exemptionReasonCode)
        val net = Money.exact(item.netAmount.amount).withScale(ACCUMULATION_SCALE)
        val vat = Money.exact(item.vatAmount.amount).withScale(ACCUMULATION_SCALE)
        val prev = totals[key] ?: (Money.exact(BigDecimal.ZERO) to Money.exact(BigDecimal.ZERO))
        totals[key] = prev.first.plusExact(net) to prev.second.plusExact(vat)
    }
    return totals.entries
        .sortedWith(compareBy({ it.key.rate }, { it.key.category?.code ?: "" }, { it.key.exemptionReasonCode ?: "" }))
        .map { (key, sums) ->
            val net = sums.first.withScale(displayScale, rounding)
            val vat = sums.second.withScale(displayScale, rounding)
            val gross = net.plusExact(vat).withScale(displayScale, rounding)
            TaxRuleSummary(key, net, vat, gross)
        }
}
