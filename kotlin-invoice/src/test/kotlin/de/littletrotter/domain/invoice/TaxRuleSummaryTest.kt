package de.littletrotter.domain.invoice

import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Tax summaries group by (category, rate, exemption reason), not rate alone. */
class TaxRuleSummaryTest {

    private fun rule(code: String, category: String, rate: String, reasonCode: String? = null, reasonText: String? = null): TaxRate =
        TaxRate().apply {
            this.code = code
            this.category = category
            this.rate = TaxRatePercent(BigDecimal(rate))
            this.exemptionReasonCode = reasonCode
            this.exemptionReasonText = reasonText
        }

    private fun item(rule: TaxRate, net: String): InvoiceItem =
        InvoiceItem().apply {
            applyTaxRule(rule)
            netAmount = Money.of(net)
            vatAmount = netAmount.percentage(rule.rate.value)
            grossAmount = netAmount + vatAmount
        }

    @Test
    fun `AE, E and O at 0pct produce three separate summary groups`() {
        val items = listOf(
            item(rule("EU_B2B_RC_SERVICE", "AE", "0.00", reasonCode = "VATEX-EU-AE"), "100.00"),
            item(rule("DE_EXEMPT_SERVICE", "E", "0.00", reasonText = "steuerbefreit"), "200.00"),
            item(rule("OUTSIDE_SCOPE_SERVICE", "O", "0.00", reasonText = "nicht steuerbar"), "300.00"),
        )

        val summary = items.taxRuleSummary()

        assertEquals(3, summary.size)
        assertEquals(setOf(VatCategory.REVERSE_CHARGE, VatCategory.EXEMPT, VatCategory.OUTSIDE_SCOPE), summary.map { it.key.category }.toSet())
    }

    @Test
    fun `two positions at 19pct standard rate collapse into one group`() {
        val rule19 = rule("DE_STANDARD_19", "S", "19.00")
        val items = listOf(item(rule19, "100.00"), item(rule19, "50.00"))

        val summary = items.taxRuleSummary()

        assertEquals(1, summary.size)
        assertEquals(Money.of("150.00"), summary[0].netAmount)
        assertEquals(Money.of("28.50"), summary[0].vatAmount)
        assertEquals(Money.of("178.50"), summary[0].grossAmount)
    }

    @Test
    fun `a position predating R1-R4 with no recognizable tax category still sorts and groups`() {
        // taxCategoryEnum is null when the stored code matches no known VatCategory (or none was
        // ever snapshotted) — the sort key falls back to "" instead of crashing on a null compare.
        val items = listOf(
            item(rule("LEGACY_UNKNOWN", "ZZ", "19.00"), "100.00"),
            item(rule("DE_STANDARD_19", "S", "19.00"), "50.00"),
        )

        val summary = items.taxRuleSummary()

        assertEquals(2, summary.size)
        assertEquals(setOf(null, VatCategory.STANDARD), summary.map { it.key.category }.toSet())
    }

    @Test
    fun `two groups tied on both rate and category are still ordered by their exemption reason`() {
        // The sort key is (rate, category, exemptionReasonCode) in that priority — with rate AND
        // category tied here, only a distinct exemptionReasonCode (one row has none, the other
        // does) actually breaks the tie, which is what exercises that third comparator step.
        val items = listOf(
            item(rule("S_WITH_REASON", "S", "19.00", reasonCode = "VATEX-SPECIAL"), "100.00"),
            item(rule("DE_STANDARD_19", "S", "19.00"), "50.00"),
        )

        val summary = items.taxRuleSummary()

        assertEquals(2, summary.size)
        assertEquals(listOf(null, "VATEX-SPECIAL"), summary.map { it.key.exemptionReasonCode })
    }

    @Test
    fun `same rate but different category and reason stay in separate groups`() {
        val items = listOf(
            item(rule("DE_STANDARD_19", "S", "19.00"), "100.00"),
            // a hypothetical foreign 19% rule that is NOT the same rule identity
            item(rule("LEGACY_LU_19", "S", "19.00"), "50.00"),
        )

        val summary = items.taxRuleSummary()

        // both share (category=S, rate=19.00, reason=null) — same key, so they DO collapse:
        // the grouping key is (category, rate, exemptionReasonCode), not the rule code itself.
        assertEquals(1, summary.size)
        assertEquals(Money.of("150.00"), summary[0].netAmount)
    }
}
