package de.littletrotter.domain.repository

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.value.TaxRatePercent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * [TaxRateRepository.findByCode]/[TaxRateRepository.findReferences] carry default bodies
 * (full-scan compatibility fallbacks for test doubles, per their own KDoc), same idiom as
 * [ArticleRepositoryDefaultsTest]. [TaxRateRepository.requireReferenceTaxRate] is a real extension
 * function with business logic (single/none/multiple branches) sitting directly on the port —
 * covered here rather than moved, since it is a pure read-side resolution with no persistence
 * concern of its own (nothing to encapsulate behind a command). [TaxRateRepository.findAll]
 * additionally declares default parameter values without a body, covered the same way as
 * [B2bProfileRepositoryDefaultsTest].
 */
class TaxRateRepositoryDefaultsTest {

    private fun rate(
        id: Long,
        code: String? = null,
        country: Country = Country.DE,
        isReference: Boolean = false,
        isActive: Boolean = true,
        isSelectable: Boolean = true,
        category: String = "S",
        isHotelRate: Boolean = false,
        rateValue: BigDecimal = BigDecimal("19.00"),
        validFrom: LocalDate? = null,
        validTo: LocalDate? = null,
    ) = TaxRate().edit {
        this.id = id
        this.code = code
        this.country = country
        this.isReference = isReference
        this.isActive = isActive
        this.isSelectable = isSelectable
        this.category = category
        this.isHotelRate = isHotelRate
        this.rate = TaxRatePercent(rateValue)
        this.validFrom = validFrom
        this.validTo = validTo
    }

    /** Minimal [TaxRateRepository] implementing only the non-default members — every default
     *  body under test here runs as the interface's own implementation. */
    private fun repoWith(vararg rates: TaxRate): TaxRateRepository = object : TaxRateRepository {
        private val data = rates.toList()
        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String?, sortDir: SortDirection) = data
        override fun count(filterText: String?) = data.size.toLong()
        override fun countInactive(filterText: String?) = 0L
        override fun findById(id: Long) = data.find { it.id == id }
        override fun findValidAt(date: LocalDate) = data
        override fun findDefault(): TaxRate? = null
        override fun findDefaults(): List<TaxRate> = emptyList()
        override fun save(rate: TaxRate) = rate
        override fun delete(id: Long) {}
        override fun isInUse(id: Long) = false
        override fun usageCounts(): Map<Long, Long> = emptyMap()
    }

    @Test
    fun `happy path findByCode finds the one rate with a matching code`() {
        val repo = repoWith(rate(1, code = "RC"), rate(2, code = "STD"))

        assertEquals(2L, repo.findByCode("STD")?.id)
    }

    @Test
    fun `boundary findByCode is null when no rate carries the code`() {
        val repo = repoWith(rate(1, code = "RC"))

        assertEquals(null, repo.findByCode("MISSING"))
    }

    @Test
    fun `boundary findAll with every argument omitted uses the documented defaults`() {
        var captured: List<Any?>? = null
        val repo = object : TaxRateRepository {
            override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String?, sortDir: SortDirection): List<TaxRate> {
                captured = listOf(pageIndex, pageSize, filterText, sortField, sortDir)
                return emptyList()
            }
            override fun count(filterText: String?) = 0L
            override fun countInactive(filterText: String?) = 0L
            override fun findById(id: Long): TaxRate? = null
            override fun findValidAt(date: LocalDate) = emptyList<TaxRate>()
            override fun findDefault(): TaxRate? = null
            override fun findDefaults(): List<TaxRate> = emptyList()
            override fun save(rate: TaxRate) = rate
            override fun delete(id: Long) {}
            override fun isInUse(id: Long) = false
            override fun usageCounts(): Map<Long, Long> = emptyMap()
        }

        repo.findAll()

        assertEquals(listOf(0, 60, null, null, SortDirection.ASC), captured)
    }

    @Test
    fun `boundary count and countInactive are exercised directly through the interface`() {
        val repo = repoWith(rate(1))

        assertEquals(1L, repo.count())
        assertEquals(0L, repo.countInactive())
    }

    @Test
    fun `boundary findReferences keeps only reference rates of the requested country`() {
        val repo = repoWith(
            rate(1, country = Country.DE, isReference = true),
            rate(2, country = Country.AT, isReference = true),
            rate(3, country = Country.DE, isReference = false),
        )

        assertEquals(listOf(1L), repo.findReferences(Country.DE).map { it.id })
    }

    @Test
    fun `happy path requireReferenceTaxRate resolves the single eligible reference rate`() {
        val repo = repoWith(
            rate(1, country = Country.DE, isReference = true, category = "S", rateValue = BigDecimal("19.00")),
        )

        val result = repo.requireReferenceTaxRate(Country.DE, LocalDate.of(2026, 1, 1))

        assertEquals(1L, result.id)
    }

    @Test
    fun `boundary requireReferenceTaxRate throws when no reference rate is eligible`() {
        val repo = repoWith(
            rate(1, country = Country.DE, isReference = true, isActive = false),
        )

        assertThrows(InvalidParameterException::class.java) {
            repo.requireReferenceTaxRate(Country.DE, LocalDate.of(2026, 1, 1))
        }
    }

    @Test
    fun `boundary requireReferenceTaxRate throws when several reference rates are eligible`() {
        val repo = repoWith(
            rate(1, country = Country.DE, isReference = true),
            rate(2, country = Country.DE, isReference = true),
        )

        assertThrows(InvalidParameterException::class.java) {
            repo.requireReferenceTaxRate(Country.DE, LocalDate.of(2026, 1, 1))
        }
    }

    @Test
    fun `boundary requireReferenceTaxRate excludes a rate outside its validity window`() {
        val repo = repoWith(
            rate(
                1,
                country = Country.DE,
                isReference = true,
                validFrom = LocalDate.of(2027, 1, 1),
            ),
        )

        assertThrows(InvalidParameterException::class.java) {
            repo.requireReferenceTaxRate(Country.DE, LocalDate.of(2026, 1, 1))
        }
    }

    @Test
    fun `boundary requireReferenceTaxRate excludes a hotel rate and a non-standard category`() {
        val repo = repoWith(
            rate(1, country = Country.DE, isReference = true, isHotelRate = true),
            rate(2, country = Country.DE, isReference = true, category = "Z"),
        )

        assertThrows(InvalidParameterException::class.java) {
            repo.requireReferenceTaxRate(Country.DE, LocalDate.of(2026, 1, 1))
        }
    }
}
