package de.littletrotter.e2e.masterdata

import de.littletrotter.application.reference.TaxRateService
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.TestTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class ReferenceTaxRateE2EIT {
    @Inject lateinit var em: EntityManager
    @Inject lateinit var service: TaxRateService

    @Test
    @TestTransaction
    fun `every seeded tax country has exactly one usable reference`() {
        val countries = em.createQuery("select distinct t.country from TaxRate t", Country::class.java).resultList
        assertTrue(countries.isNotEmpty())
        countries.forEach { country ->
            val rate = service.findReferenceTaxRate(country, LocalDate.of(2026, 9, 16))
            assertEquals(country, rate.country)
            assertTrue(rate.isReference)
            assertTrue(rate.isActive && rate.isSelectable)
            assertEquals("S", rate.category)
            assertFalse(rate.isHotelRate)
        }
    }

    @Test
    @TestTransaction
    fun `reference change persists and validity boundaries are inclusive`() {
        val date = LocalDate.of(2026, 9, 16)
        val original = service.findReferenceTaxRate(Country.DE, date)
        val originalDefault = original.isDefault
        val replacement = TaxRate().edit {
            name = "E2E reference replacement"
            code = "E2E_REFERENCE_REPLACEMENT"
            country = Country.DE
            rate = TaxRatePercent(BigDecimal("19"))
            category = "S"
            isReference = true
            validFrom = date
            validTo = date
        }
        assertThrows(InvalidParameterException::class.java) { service.saveTaxRate(replacement) }
        original.edit { isReference = false }
        service.saveTaxRate(original)
        em.flush()
        val id = service.saveTaxRate(replacement).id
        em.flush()
        em.clear()
        assertEquals(id, service.findReferenceTaxRate(Country.DE, date).id)
        assertEquals(originalDefault, service.findTaxRateById(original.id!!)!!.isDefault)
        assertThrows(InvalidParameterException::class.java) { service.findReferenceTaxRate(Country.DE, date.minusDays(1)) }
        assertThrows(InvalidParameterException::class.java) { service.findReferenceTaxRate(Country.DE, date.plusDays(1)) }
    }
}
