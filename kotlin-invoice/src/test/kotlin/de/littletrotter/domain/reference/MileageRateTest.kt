package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class MileageRateTest {

    @Test
    fun `isApplicableFor requires matching country and date range`() {
        val rate = MileageRate().apply {
            country = Country.DE
            validFrom = LocalDate.of(2025, 1, 1)
            validTo = LocalDate.of(2025, 12, 31)
        }

        assertTrue(rate.isApplicableFor(Country.DE, LocalDate.of(2025, 6, 1)))
        assertFalse(rate.isApplicableFor(Country.FR, LocalDate.of(2025, 6, 1)))
        assertFalse(rate.isApplicableFor(Country.DE, LocalDate.of(2026, 1, 1)))
    }

    // ── R2 — vehicle-type resolution, never country ─────────────────────────

    @Test
    fun `R2-T01-T03 - isApplicableFor by vehicle type ignores the destination country entirely`() {
        val carRate = MileageRate().apply {
            country = Country.DE; vehicleType = VehicleType.CAR; ratePerKm = BigDecimal("0.30")
            validFrom = LocalDate.of(2024, 1, 1)
        }
        val today = LocalDate.of(2026, 6, 1)

        // A domestic trip, a trip to an EU neighbour and a trip to a third country all resolve
        // to the exact same German rate — the destination is not part of the resolution at all.
        assertTrue(carRate.isApplicableFor(VehicleType.CAR, today))
        // isApplicableFor(VehicleType, date) never even accepts a country parameter — that's the
        // whole point (see the country-based overload above, kept only for legacy rows).
    }

    @Test
    fun `R2-T04 - other motor vehicle resolves to a different rate than a car`() {
        val car = MileageRate().apply { vehicleType = VehicleType.CAR; ratePerKm = BigDecimal("0.30"); validFrom = LocalDate.of(2024, 1, 1) }
        val other = MileageRate().apply { vehicleType = VehicleType.OTHER_MOTOR_VEHICLE; ratePerKm = BigDecimal("0.20"); validFrom = LocalDate.of(2024, 1, 1) }
        val date = LocalDate.of(2026, 6, 1)

        assertTrue(car.isApplicableFor(VehicleType.CAR, date))
        assertFalse(car.isApplicableFor(VehicleType.OTHER_MOTOR_VEHICLE, date))
        assertTrue(other.isApplicableFor(VehicleType.OTHER_MOTOR_VEHICLE, date))
        assertFalse(other.isApplicableFor(VehicleType.CAR, date))
    }

    @Test
    fun `an inactive row is never applicable even when vehicle type and date match`() {
        val deactivated = MileageRate().apply {
            vehicleType = VehicleType.CAR; ratePerKm = BigDecimal("0.35"); isActive = false
        }
        assertFalse(deactivated.isApplicableFor(VehicleType.CAR, LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `a legacy country-based row with no vehicle type never matches the new resolution`() {
        val legacy = MileageRate().apply { country = Country.FR; ratePerKm = BigDecimal("0.35") }
        assertFalse(legacy.isApplicableFor(VehicleType.CAR, LocalDate.of(2026, 1, 1)))
        assertFalse(legacy.isApplicableFor(VehicleType.OTHER_MOTOR_VEHICLE, LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `calculateRoundTripAmount doubles one-way distance before pricing`() {
        val rate = MileageRate().apply { ratePerKm = BigDecimal("0.30") }

        assertEquals(Money.of("24.00"), rate.calculateRoundTripAmount(BigDecimal("40")))
    }

    @Test
    fun `calculateRoundTripAmount rejects negative input`() {
        val rate = MileageRate().apply { ratePerKm = BigDecimal("0.30") }

        assertThrows(InvalidParameterException::class.java) {
            rate.calculateRoundTripAmount(BigDecimal("-1"))
        }
    }

    // ── isApplicableFor(Country, date) — validity-window edge cases the legacy overload
    //    still needs (open-ended start/end, request date before the window starts) ──

    @Test
    fun `a legacy row with no validFrom is applicable from the very beginning`() {
        val rate = MileageRate().apply { country = Country.DE; validFrom = null; validTo = LocalDate.of(2025, 12, 31) }

        assertTrue(rate.isApplicableFor(Country.DE, LocalDate.of(2000, 1, 1)))
    }

    @Test
    fun `a legacy row is not yet applicable before its validFrom date`() {
        val rate = MileageRate().apply { country = Country.DE; validFrom = LocalDate.of(2025, 6, 1) }

        assertFalse(rate.isApplicableFor(Country.DE, LocalDate.of(2025, 1, 1)))
    }

    @Test
    fun `a legacy row with no validTo stays applicable indefinitely`() {
        val rate = MileageRate().apply { country = Country.DE; validFrom = LocalDate.of(2020, 1, 1); validTo = null }

        assertTrue(rate.isApplicableFor(Country.DE, LocalDate.of(2099, 1, 1)))
    }

    // ── isApplicableFor(VehicleType, date) — same validity-window rules, R2 resolution ──

    @Test
    fun `R2 - a rate with no validFrom is applicable from the very beginning`() {
        val rate = MileageRate().apply { vehicleType = VehicleType.CAR; validFrom = null }

        assertTrue(rate.isApplicableFor(VehicleType.CAR, LocalDate.of(2000, 1, 1)))
    }

    @Test
    fun `R2 - a rate is not yet applicable before its validFrom date`() {
        val rate = MileageRate().apply { vehicleType = VehicleType.CAR; validFrom = LocalDate.of(2026, 1, 1) }

        assertFalse(rate.isApplicableFor(VehicleType.CAR, LocalDate.of(2025, 1, 1)))
    }

    @Test
    fun `R2 - a rate stops being applicable once validTo has passed`() {
        val rate = MileageRate().apply {
            vehicleType = VehicleType.CAR
            validFrom = LocalDate.of(2020, 1, 1)
            validTo = LocalDate.of(2025, 12, 31)
        }

        assertTrue(rate.isApplicableFor(VehicleType.CAR, LocalDate.of(2025, 12, 31)))
        assertFalse(rate.isApplicableFor(VehicleType.CAR, LocalDate.of(2026, 1, 1)))
    }

    // ── validate() — R2's seeded two-row model still needs its own invariants checked ──

    @Test
    fun `a mileage rate without a country is rejected`() {
        val rate = MileageRate().apply { ratePerKm = BigDecimal("0.30") }

        assertThrows(InvalidParameterException::class.java) { rate.validate() }
    }

    @Test
    fun `a negative rate per kilometer is rejected`() {
        val rate = MileageRate().apply { country = Country.DE; ratePerKm = BigDecimal("-0.01") }

        assertThrows(InvalidParameterException::class.java) { rate.validate() }
    }

    @Test
    fun `validFrom after validTo is rejected`() {
        val rate = MileageRate().apply {
            country = Country.DE
            ratePerKm = BigDecimal("0.30")
            validFrom = LocalDate.of(2026, 1, 1)
            validTo = LocalDate.of(2025, 1, 1)
        }

        assertThrows(InvalidParameterException::class.java) { rate.validate() }
    }

    @Test
    fun `a fully specified mileage rate passes validation`() {
        val rate = MileageRate().apply {
            country = Country.DE
            ratePerKm = BigDecimal("0.30")
            validFrom = LocalDate.of(2024, 1, 1)
            validTo = LocalDate.of(2025, 12, 31)
        }

        rate.validate() // must not throw
    }

    @Test
    fun `boundary - a validFrom without a validTo is an open-ended rate and passes validation`() {
        val rate = MileageRate().apply {
            country = Country.DE
            ratePerKm = BigDecimal("0.30")
            validFrom = LocalDate.of(2024, 1, 1)
            validTo = null
        }

        rate.validate() // no upper bound to compare against, must not throw
    }

    @Test
    fun `boundary - validFrom equal to validTo is a single valid day and passes validation`() {
        val rate = MileageRate().apply {
            country = Country.DE
            ratePerKm = BigDecimal("0.30")
            validFrom = LocalDate.of(2024, 6, 1)
            validTo = LocalDate.of(2024, 6, 1)
        }

        rate.validate() // equal dates are not "after", must not throw
    }

    @Test
    fun `boundary - createdAt is unset until Hibernate stamps it on persist`() {
        val rate = MileageRate()

        assertEquals(null, rate.createdAt)
    }

    @Test
    fun `calculateAmount with zero kilometers returns zero money`() {
        val rate = MileageRate().apply { ratePerKm = BigDecimal("0.30") }
        assertEquals(Money.ZERO, rate.calculateAmount(BigDecimal.ZERO))
    }

    @Test
    fun `calculateAmount with positive kilometers returns correct amount`() {
        val rate = MileageRate().apply { ratePerKm = BigDecimal("0.30") }
        assertEquals(Money.of("15.00"), rate.calculateAmount(BigDecimal("50")))
    }

    @Test
    fun `calculateAmount rejects negative kilometers`() {
        val rate = MileageRate().apply { ratePerKm = BigDecimal("0.30") }
        assertThrows(InvalidParameterException::class.java) {
            rate.calculateAmount(BigDecimal("-10"))
        }
    }

    @Test
    fun `R2 - isApplicableFor with no validTo stays applicable indefinitely`() {
        val rate = MileageRate().apply {
            vehicleType = VehicleType.CAR
            validFrom = LocalDate.of(2024, 1, 1)
            validTo = null
        }
        assertTrue(rate.isApplicableFor(VehicleType.CAR, LocalDate.of(2099, 1, 1)))
    }

    @Test
    fun `validate with zero rate per kilometer passes`() {
        val rate = MileageRate().apply {
            country = Country.DE
            ratePerKm = BigDecimal.ZERO
        }
        rate.validate() // must not throw
    }
}
