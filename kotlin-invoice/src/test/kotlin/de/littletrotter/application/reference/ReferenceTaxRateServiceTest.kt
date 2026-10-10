package de.littletrotter.application.reference

import de.littletrotter.application.cache.NoopInvoiceCachePort
import com.fasterxml.jackson.databind.ObjectMapper
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.TaxRateRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

class ReferenceTaxRateServiceTest {
    private val repository = mock<TaxRateRepository>()
    private val service = TaxRateService(repository, NoopInvoiceCachePort(), ObjectMapper())
    private val date = LocalDate.of(2026, 9, 16)

    private fun reference(id: Long, country: Country = Country.DE) = TaxRate().apply {
        this.id = id
        name = "Regelsatz"
        rate = TaxRatePercent(BigDecimal("19.00"))
        this.country = country
        category = "S"
        isReference = true
    }

    @Test
    fun `findReferenceTaxRate resolves the unique eligible rate with inclusive validity`() {
        val rate = reference(1).apply { validFrom = date; validTo = date }
        whenever(repository.findReferences(Country.DE)).thenReturn(listOf(rate))

        assertEquals(rate, service.findReferenceTaxRate(Country.DE, date))
        verify(repository).findReferences(Country.DE)
        verify(repository, never()).findAll()
    }

    @Test
    fun `findReferenceTaxRate reports a missing currently eligible rate`() {
        val expired = reference(1).apply { validTo = date.minusDays(1) }
        val inactive = reference(2).apply { isActive = false }
        whenever(repository.findReferences(Country.DE)).thenReturn(listOf(expired, inactive))

        val error = assertThrows(InvalidParameterException::class.java) {
            service.findReferenceTaxRate(Country.DE, date)
        }
        assertTrue(error.message!!.contains("kein gültiger Referenzsteuersatz"))
    }

    @Test
    fun `findReferenceTaxRate reports ambiguous eligible data`() {
        whenever(repository.findReferences(Country.DE)).thenReturn(listOf(reference(1), reference(2)))

        val error = assertThrows(InvalidParameterException::class.java) {
            service.findReferenceTaxRate(Country.DE, date)
        }
        assertTrue(error.message!!.contains("mehrere Referenzsteuersätze"))
    }

    @Test
    fun `saveTaxRate rejects a competing reference in the same country`() {
        val candidate = reference(2)
        whenever(repository.findReferences(Country.DE)).thenReturn(listOf(reference(1)))

        val error = assertThrows(InvalidParameterException::class.java) { service.saveTaxRate(candidate) }
        assertTrue(error.message!!.contains("bereits ein Referenzsteuersatz"))
        verify(repository, never()).save(candidate)
    }

    @Test
    fun `saveTaxRate permits updating the same reference`() {
        val candidate = reference(1)
        whenever(repository.findReferences(Country.DE)).thenReturn(listOf(reference(1)))
        whenever(repository.save(candidate)).thenReturn(candidate)

        assertEquals(candidate, service.saveTaxRate(candidate))
    }
}
