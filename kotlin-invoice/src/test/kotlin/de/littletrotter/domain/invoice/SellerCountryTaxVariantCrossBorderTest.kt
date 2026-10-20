package de.littletrotter.domain.invoice

import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ArticleTaxVariant
import de.littletrotter.fixture.anArticle
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import de.littletrotter.shared.tax.VatCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.util.stream.Stream
import de.littletrotter.test.FIXED_TODAY
import de.littletrotter.fixture.anInvoiceItem

/**
 * An [InvoiceItem] bound to an [Article] with a country's [ArticleTaxVariant]
 * gets the right rate/VAT/gross via [InvoiceItem.applySellerCountryTaxVariant] and
 * [InvoiceItem.calculateAmounts], across all four seed rates (LU 17/14/8/3, FR
 * 20/10/5.5/2.1, BE 21/12/6, NL 21/9) — not just Luxembourg's standard rate, which
 * [InvoiceItemTaxSnapshotTest] already covers.
 *
 * The same quantity (3) × unit price (33.33) is used for every rate on purpose, not varied per
 * case: net always comes out to an exact 99.99 (no rounding ambiguity there), so every case
 * isolates the same thing — whether the *VAT* rounding (`Money.percentage`, HALF_UP, scale 2) is
 * correct for that rate. FR 5.5 % (99.99 × 5.5 % = 5.49945 → 5.50) and LU 3 % (99.99 × 3 % =
 * 2.9997 → 3.00) are the two cases the spec calls out explicitly as non-trivial cent rounding;
 * every other rate here rounds the same way (the fractional part of 99.99 × rate / 100 is always
 * just under a whole cent for these rates), so the table below states the already-rounded
 * expectation for all of them rather than re-deriving it in comments per row.
 */
class SellerCountryTaxVariantCrossBorderTest {

    companion object {
        private val QUANTITY = BigDecimal("3")
        private val UNIT_PRICE = Money.of("33.33")
        private val EXPECTED_NET = Money.of("99.99")

        @JvmStatic
        fun rates(): Stream<Arguments> = Stream.of(
            // country, code, rate, expected VAT, expected gross
            Arguments.of(Country.LU, "LU_STANDARD_17", BigDecimal("17.00"), Money.of("17.00"), Money.of("116.99")),
            Arguments.of(Country.LU, "LU_INTERMEDIATE_14", BigDecimal("14.00"), Money.of("14.00"), Money.of("113.99")),
            Arguments.of(Country.LU, "LU_REDUCED_8", BigDecimal("8.00"), Money.of("8.00"), Money.of("107.99")),
            Arguments.of(Country.LU, "LU_SUPER_REDUCED_3", BigDecimal("3.00"), Money.of("3.00"), Money.of("102.99")),
            Arguments.of(Country.FR, "FR_STANDARD_20", BigDecimal("20.00"), Money.of("20.00"), Money.of("119.99")),
            Arguments.of(Country.FR, "FR_REDUCED_10", BigDecimal("10.00"), Money.of("10.00"), Money.of("109.99")),
            Arguments.of(Country.FR, "FR_REDUCED_5_5", BigDecimal("5.50"), Money.of("5.50"), Money.of("105.49")),
            Arguments.of(Country.FR, "FR_REDUCED_2_1", BigDecimal("2.10"), Money.of("2.10"), Money.of("102.09")),
            Arguments.of(Country.BE, "BE_STANDARD_21", BigDecimal("21.00"), Money.of("21.00"), Money.of("120.99")),
            Arguments.of(Country.BE, "BE_REDUCED_12", BigDecimal("12.00"), Money.of("12.00"), Money.of("111.99")),
            Arguments.of(Country.BE, "BE_REDUCED_6", BigDecimal("6.00"), Money.of("6.00"), Money.of("105.99")),
            Arguments.of(Country.NL, "NL_STANDARD_21", BigDecimal("21.00"), Money.of("21.00"), Money.of("120.99")),
            Arguments.of(Country.NL, "NL_REDUCED_9", BigDecimal("9.00"), Money.of("9.00"), Money.of("108.99")),
        )
    }

    @ParameterizedTest(name = "{1} ({0}, {2} %)")
    @MethodSource("rates")
    fun `applySellerCountryTaxVariant taxes an article-bound position at the country's rate, with correct rounding`(
        country: Country,
        code: String,
        rate: BigDecimal,
        expectedVat: Money,
        expectedGross: Money,
    ) {
        val taxRate = TaxRate().edit {
            this.code = code; category = "S"; this.rate = TaxRatePercent(rate); this.country = country
        }
        val article = anArticle { name = "Cross-border article ($code)" }
        article.replaceTaxVariants(listOf(ArticleTaxVariant.of(article, country, taxRate, today = FIXED_TODAY)), today = FIXED_TODAY)

        val item = anInvoiceItem(
            articleRef = article,
            quantity = QUANTITY,
            unitPriceNet = UNIT_PRICE,
        )

        item.applySellerCountryTaxVariant(country)

        assertEquals(code, item.taxRuleCode)
        assertEquals(VatCategory.STANDARD, item.taxCategory)
        assertEquals(0, rate.compareTo(item.taxRateSnapshot?.value), "tax rate snapshot must match the variant's rate")

        item.calculateAmounts()

        assertEquals(EXPECTED_NET, item.netAmount, "net amount")
        assertEquals(expectedVat, item.vatAmount, "vat amount")
        assertEquals(expectedGross, item.grossAmount, "gross amount")
    }
}
