package de.littletrotter.application.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.MileageRate
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.domain.repository.MileageRateRepository
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

/**
 * mileage-rate slice of the former `ReferenceDataServiceRatesTest`, split by
 * Referenzdaten domain alongside [MileageRateService]
 */
class MileageRateServiceTest {

    private val repository = mock<MileageRateRepository>()
    private fun service() = MileageRateService(repository)

    private fun mileageRate(from: LocalDate?, to: LocalDate?) = MileageRate().apply {
        country = Country.DE
        ratePerKm = BigDecimal("0.30")
        vehicleType = VehicleType.CAR
        validFrom = from
        validTo = to
    }

    @Test
    fun `happy path - every read and write delegates to the repository`() {
        val svc = service()
        val mileage = mileageRate(null, null)
        whenever(repository.findById(4L)).thenReturn(mileage)
        whenever(repository.findAll(0, 60, null, "country", SortDirection.ASC)).thenReturn(listOf(mileage))
        whenever(repository.count(null)).thenReturn(8L)
        whenever(repository.countInactive(null)).thenReturn(1L)
        whenever(repository.save(mileage)).thenReturn(mileage)

        assertSame(mileage, svc.findMileageRateById(4L))
        assertEquals(listOf(mileage), svc.findAllMileageRates())
        assertEquals(8L, svc.countMileageRates())
        assertEquals(1L, svc.countInactiveMileageRates())
        assertSame(mileage, svc.saveMileageRate(mileage))
        svc.deleteMileageRate(4L)

        verify(repository).delete(4L)
    }

    @Test
    fun `boundary - unknown id resolves to null, empty page resolves to empty`() {
        val svc = service()

        assertNull(svc.findMileageRateById(404L))
        assertTrue(svc.findAllMileageRates(pageIndex = 3, filterText = "zzz").isEmpty())
    }

    @Test
    fun `boundary - validity window - a one-day window is valid, an inverted window is rejected`() {
        val svc = service()
        val day = LocalDate.of(2026, 1, 1)
        val oneDay = mileageRate(day, day)
        whenever(repository.save(oneDay)).thenReturn(oneDay)

        assertSame(oneDay, svc.saveMileageRate(oneDay))
        assertThrows(InvalidParameterException::class.java) { svc.saveMileageRate(mileageRate(day, day.minusDays(1))) }
        verify(repository).save(oneDay)
    }
}
