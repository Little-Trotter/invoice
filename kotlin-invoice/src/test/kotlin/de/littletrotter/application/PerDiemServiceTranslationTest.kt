package de.littletrotter.application

import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.application.reference.PerDiemDay
import de.littletrotter.application.reference.PerDiemRateLookup
import de.littletrotter.application.reference.PerDiemService
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.repository.PerDiemRateRepository
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

class PerDiemServiceTranslationTest {

    @Test
    fun `invalid country is translated before repository call`() {
        val repository = mock<PerDiemRateRepository>()
        val service = PerDiemService(repository)

        val exception = assertThrows(ApplicationValidationException::class.java) {
            service.calculate(PerDiemRateLookup("XX", ""), PerDiemDay(LocalDate.of(2025, 1, 1), false), PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false), Money.ZERO)
        }

        assertEquals("Invalid country: XX", exception.message)
        verifyNoInteractions(repository)
    }

    @Test
    fun `missing rate is translated to application validation exception`() {
        val repository = mock<PerDiemRateRepository>()
        whenever(repository.findByCountryAndRegionAndDate(Country.DE, "BER", LocalDate.of(2025, 1, 1))).thenReturn(null)
        val service = PerDiemService(repository)

        val exception = assertThrows(ApplicationValidationException::class.java) {
            service.calculate(PerDiemRateLookup("DE", "BER"), PerDiemDay(LocalDate.of(2025, 1, 1), false), PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false), Money.ZERO)
        }

        assertTrue(exception.message!!.contains("No per diem rate found"))
    }

    @Test
    fun `repository rate branch succeeds through application translation layer`() {
        val repository = mock<PerDiemRateRepository>()
        val rate = PerDiemRate().apply {
            country = Country.DE
            fullDayRate = Money.of("28.00")
            arrivalDepartureRate = Money.of("14.00")
            inlandRules = true
        }
        whenever(repository.findByCountryAndRegionAndDate(Country.DE, "", LocalDate.of(2025, 1, 1))).thenReturn(rate)
        val service = PerDiemService(repository)

        val result = service.calculate(PerDiemRateLookup("DE", ""), PerDiemDay(LocalDate.of(2025, 1, 1), false), PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false), Money.ZERO)

        assertEquals(BigDecimal("28.00"), result)
    }
}
