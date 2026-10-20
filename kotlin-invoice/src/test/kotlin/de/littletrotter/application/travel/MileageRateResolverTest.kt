package de.littletrotter.application.travel

import de.littletrotter.domain.reference.MileageRate
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.domain.repository.MileageRateRepository
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Ports the rate-resolution cases from the earlier `TravelItemDialogKaribuTest`
 * ("mileage rate resolves from reference data …", lines 390-428 before this class existed) onto
 * [MileageRateResolver]: R2 — the rate depends on vehicle type + date, never the destination
 * country.
 */
class MileageRateResolverTest {

    private val repository: MileageRateRepository = mock()
    private val resolver = MileageRateResolver(repository)

    @Test
    fun `resolves the car rate for the given date`() {
        val carRate = MileageRate().edit { ratePerKm = BigDecimal("0.30") }
        whenever(repository.findByVehicleTypeAndDate(eq(VehicleType.CAR), any())).thenReturn(carRate)

        val resolved = resolver.resolveRatePerKm(VehicleType.CAR, LocalDate.of(2026, 1, 1))

        assertEquals(BigDecimal("0.30"), resolved?.ratePerKm)
    }

    @Test
    fun `resolves a different rate for another motor vehicle`() {
        val otherRate = MileageRate().edit { ratePerKm = BigDecimal("0.20") }
        whenever(repository.findByVehicleTypeAndDate(eq(VehicleType.OTHER_MOTOR_VEHICLE), any())).thenReturn(otherRate)

        val resolved = resolver.resolveRatePerKm(VehicleType.OTHER_MOTOR_VEHICLE, LocalDate.of(2026, 1, 1))

        assertEquals(BigDecimal("0.20"), resolved?.ratePerKm)
    }

    @Test
    fun `returns null when no mileage rate matches`() {
        whenever(repository.findByVehicleTypeAndDate(any(), any())).thenReturn(null)

        val resolved = resolver.resolveRatePerKm(VehicleType.CAR, LocalDate.of(2026, 1, 1))

        assertNull(resolved)
    }

    @Test
    fun `calculateAmount multiplies kilometers by the resolved rate via Money`() {
        val rate = MileageRate().edit { ratePerKm = BigDecimal("0.30") }

        val amount = resolver.calculateAmount(rate, BigDecimal("120"))

        assertEquals(Money(BigDecimal("36.00")), amount)
    }

    @Test
    fun `ratePerKm exposes the configured amount only`() {
        val carRate = MileageRate().edit { ratePerKm = BigDecimal("0.30") }
        whenever(repository.findByVehicleTypeAndDate(eq(VehicleType.CAR), any())).thenReturn(carRate)

        assertEquals(BigDecimal("0.30"), resolver.ratePerKm(VehicleType.CAR, LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `ratePerKm is null when no mileage rate matches`() {
        whenever(repository.findByVehicleTypeAndDate(any(), any())).thenReturn(null)

        assertNull(resolver.ratePerKm(VehicleType.CAR, LocalDate.of(2026, 1, 1)))
    }
}
