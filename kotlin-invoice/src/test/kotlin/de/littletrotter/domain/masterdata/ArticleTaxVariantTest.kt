package de.littletrotter.domain.masterdata

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import de.littletrotter.test.FIXED_TODAY

/** Per-country MWST variants of an [Article]. */
class ArticleTaxVariantTest {

    private fun article(): Article = Article().apply { name = "Beratung"; id = 1L }

    private fun taxRate(country: Country, id: Long = 1L): TaxRate = TaxRate().apply {
        this.id = id
        name = "USt $country"
        rate = TaxRatePercent(BigDecimal("17.00"))
        this.country = country
        code = "${country.alpha2}_STANDARD_17"
        category = "S"
    }

    @Test
    fun `a curated-country variant whose rate matches the country is valid`() {
        val a = article()
        val rate = taxRate(Country.LU)
        assertDoesNotThrow { ArticleTaxVariant.of(a, Country.LU, rate, today = FIXED_TODAY) }
    }

    @Test
    fun `a country outside the curated set is rejected`() {
        val a = article()
        val rate = taxRate(Country.CH)
        assertThrows(InvalidParameterException::class.java) { ArticleTaxVariant.of(a, Country.CH, rate, today = FIXED_TODAY) }
    }

    @Test
    fun `a rate belonging to a different country than the variant is rejected`() {
        val a = article()
        val frRate = taxRate(Country.FR)
        assertThrows(InvalidParameterException::class.java) { ArticleTaxVariant.of(a, Country.LU, frRate, today = FIXED_TODAY) }
    }

    @Test
    fun `duplicate countries in a candidate list are rejected`() {
        val a = article()
        val v1 = ArticleTaxVariant.of(a, Country.LU, taxRate(Country.LU, 1L), today = FIXED_TODAY)
        val v2 = ArticleTaxVariant.of(a, Country.LU, taxRate(Country.LU, 2L), today = FIXED_TODAY)
        assertThrows(InvalidParameterException::class.java) { ArticleTaxVariant.assertUniqueCountries(listOf(v1, v2)) }
    }

    // ── Article.taxVariantFor / replaceTaxVariants ──────────────────────────────

    @Test
    fun `taxVariantFor returns null when no variant is maintained for the country`() {
        val a = article()
        assertNull(a.taxVariantFor(Country.LU))
    }

    @Test
    fun `taxVariantFor returns the matching rate after replaceTaxVariants`() {
        val a = article()
        val rate = taxRate(Country.LU)
        a.replaceTaxVariants(listOf(ArticleTaxVariant.of(a, Country.LU, rate, today = FIXED_TODAY)), today = FIXED_TODAY)

        assertEquals(rate, a.taxVariantFor(Country.LU))
        assertNull(a.taxVariantFor(Country.FR))
    }

    @Test
    fun `replaceTaxVariants sets the owning article on every row`() {
        val a = article()
        val variant = ArticleTaxVariant.of(a, Country.LU, taxRate(Country.LU), today = FIXED_TODAY)
        a.replaceTaxVariants(listOf(variant), today = FIXED_TODAY)
        assertTrue(a.taxVariants.single().article === a)
    }

    @Test
    fun `replacing with a duplicate country throws and leaves the previous list untouched`() {
        val a = article()
        val lu1 = ArticleTaxVariant.of(a, Country.LU, taxRate(Country.LU, 1L), today = FIXED_TODAY)
        a.replaceTaxVariants(listOf(lu1), today = FIXED_TODAY)
        val retained = a.taxVariants.single()

        val lu2a = ArticleTaxVariant.of(a, Country.LU, taxRate(Country.LU, 2L), today = FIXED_TODAY)
        val lu2b = ArticleTaxVariant.of(a, Country.LU, taxRate(Country.LU, 3L), today = FIXED_TODAY)
        assertThrows(InvalidParameterException::class.java) { a.replaceTaxVariants(listOf(lu2a, lu2b), today = FIXED_TODAY) }
        assertEquals(1, a.taxVariants.size)
        assertTrue(a.taxVariants.single() === retained)
    }

    @Test
    fun `adding and changing countries retains persisted row identity and version`() {
        val a = article()
        a.replaceTaxVariants(listOf(ArticleTaxVariant.of(a, Country.LU, taxRate(Country.LU), today = FIXED_TODAY)), today = FIXED_TODAY)
        val lu = a.taxVariants.single().apply { id = 42L; version = 3L }
        val changedRate = taxRate(Country.LU, 2L)
        val next = listOf(
            ArticleTaxVariant.of(a, Country.LU, changedRate, today = FIXED_TODAY),
            ArticleTaxVariant.of(a, Country.FR, taxRate(Country.FR), today = FIXED_TODAY),
        )

        a.replaceTaxVariants(next, today = FIXED_TODAY)
        a.replaceTaxVariants(next, today = FIXED_TODAY)

        assertEquals(2, a.taxVariants.size)
        assertTrue(a.taxVariants.first() === lu)
        assertEquals(42L, lu.id)
        assertEquals(3L, lu.version)
        assertEquals(changedRate, lu.taxRateRef)
        a.replaceTaxVariants(a.taxVariants, today = FIXED_TODAY)
        assertEquals(2, a.taxVariants.size)
        a.replaceTaxVariants(listOf(next.last()), today = FIXED_TODAY)
        assertNull(a.taxVariantFor(Country.LU))
        a.replaceTaxVariants(emptyList(), today = FIXED_TODAY)
        assertTrue(a.taxVariants.isEmpty())
    }

    @Test
    fun `invalid later variant does not partially update earlier managed row`() {
        val a = article()
        a.replaceTaxVariants(listOf(ArticleTaxVariant.of(a, Country.LU, taxRate(Country.LU), today = FIXED_TODAY)), today = FIXED_TODAY)
        val lu = a.taxVariants.single()
        val originalRate = lu.taxRateRef
        val invalid = ArticleTaxVariant().apply { country = Country.FR }

        assertThrows(InvalidParameterException::class.java) {
            a.replaceTaxVariants(listOf(ArticleTaxVariant.of(a, Country.LU, taxRate(Country.LU, 2L), today = FIXED_TODAY), invalid), today = FIXED_TODAY)
        }

        assertEquals(listOf(lu), a.taxVariants)
        assertEquals(originalRate, lu.taxRateRef)
        assertNull(invalid.article)
    }

    @Test
    fun `foreign variant identity and owner cannot transfer to another aggregate`() {
        val source = article()
        val target = article().apply { id = 2L }
        source.replaceTaxVariants(listOf(ArticleTaxVariant.of(source, Country.LU, taxRate(Country.LU), today = FIXED_TODAY)), today = FIXED_TODAY)
        val foreign = source.taxVariants.single().apply { id = 42L; version = 3L }

        target.replaceTaxVariants(listOf(foreign), today = FIXED_TODAY)

        val added = target.taxVariants.single()
        assertNull(added.id)
        assertEquals(0L, added.version)
        assertTrue(added !== foreign)
        assertTrue(added.article === target)
        assertTrue(foreign.article === source)
        assertTrue(source.taxVariants.single() === foreign)
        assertEquals(42L, foreign.id)
        assertEquals(3L, foreign.version)
        assertEquals(foreign.taxRateRef, added.taxRateRef)
    }

    @Test
    fun `tax variant collection cannot bypass aggregate replacement`() {
        assertThrows(UnsupportedOperationException::class.java) {
            (article().taxVariants as MutableList<ArticleTaxVariant>).clear()
        }
    }

    @Test
    fun `the curated set is every current EU member except Germany`() {
        assertEquals(
            Country.supportedSellerVatRegistrationCountries(today = FIXED_TODAY),
            setOf(
                Country.AT, Country.BE, Country.BG, Country.CY, Country.CZ, Country.DK,
                Country.EE, Country.ES, Country.FI, Country.FR, Country.GR, Country.HR,
                Country.HU, Country.IE, Country.IT, Country.LT, Country.LU, Country.LV,
                Country.MT, Country.NL, Country.PL, Country.PT, Country.RO, Country.SE,
                Country.SI, Country.SK,
            ),
        )
    }
}
