package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class ReferenceRateBehaviorTest {

    @Test
    fun `tax rate is applicable within validity window`() {
        val rate = TaxRate().edit {
            validFrom = LocalDate.of(2025, 1, 1)
            validTo = LocalDate.of(2025, 12, 31)
        }

        assertTrue(rate.isApplicableFor(LocalDate.of(2025, 6, 1)))
        assertFalse(rate.isApplicableFor(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `tax rate validation rejects an invalid window`() {
        val rate = TaxRate().edit {
            name = "VAT"
            this.rate = TaxRatePercent(BigDecimal("19.00"))
            validFrom = LocalDate.of(2025, 12, 31)
            validTo = LocalDate.of(2025, 1, 1)
        }

        assertThrows(InvalidParameterException::class.java) {
            rate.validate()
        }
    }

    // an out-of-range percentage now throws at TaxRatePercent construction, before
    // TaxRate.validate() is ever reached.
    @Test
    fun `tax rate construction rejects an out-of-range percentage`() {
        assertThrows(InvalidParameterException::class.java) {
            TaxRate().edit {
                name = "VAT"
                this.rate = TaxRatePercent(BigDecimal("120.00"))
            }
        }
    }

    @Test
    fun `mileage rate applies by country and date and computes amount`() {
        val mileage = MileageRate().edit {
            country = Country.DE
            ratePerKm = BigDecimal("0.30")
            validFrom = LocalDate.of(2024, 1, 1)
            validTo = LocalDate.of(2026, 12, 31)
        }

        assertTrue(mileage.isApplicableFor(Country.DE, LocalDate.of(2025, 3, 1)))
        assertFalse(mileage.isApplicableFor(Country.FR, LocalDate.of(2025, 3, 1)))
        assertEquals(Money.of("12.00"), mileage.calculateAmount(BigDecimal("40")))
    }

    @Test
    fun `mileage calculation rejects negative kilometers`() {
        val mileage = MileageRate().edit { ratePerKm = BigDecimal("0.30") }

        assertThrows(InvalidParameterException::class.java) {
            mileage.calculateAmount(BigDecimal("-1"))
        }
    }

    @Test
    fun `per diem uses inland deduction on full day rate`() {
        val rate = PerDiemRate().edit {
            country = Country.DE
            fullDayRate = Money.of("28.00")
            arrivalDepartureRate = Money.of("14.00")
            inlandRules = true
        }

        val claimable = rate.calculateClaimableAmount(
            isArrivalDeparture = true,
            deductions = PerDiemMealDeductions(breakfast = true, lunch = false, dinner = false),
            employerContribution = Money.ZERO,
        )

        assertEquals(BigDecimal("8.40"), claimable)
    }

    @Test
    fun `per diem applicability respects region fallback`() {
        val global = PerDiemRate().edit {
            country = Country.FR
            region = null
            validFrom = LocalDate.of(2024, 1, 1)
            validTo = LocalDate.of(2026, 12, 31)
        }
        val regional = PerDiemRate().edit {
            country = Country.FR
            region = "PAR"
            validFrom = LocalDate.of(2024, 1, 1)
            validTo = LocalDate.of(2026, 12, 31)
        }

        assertTrue(global.isApplicableFor(Country.FR, "LYO", LocalDate.of(2025, 6, 1)))
        assertTrue(regional.isApplicableFor(Country.FR, "PAR", LocalDate.of(2025, 6, 1)))
        assertFalse(regional.isApplicableFor(Country.FR, "LYO", LocalDate.of(2025, 6, 1)))
    }

    @Test
    fun `per diem claimable amount floors at zero after deductions and employer contribution`() {
        val rate = PerDiemRate().edit {
            country = Country.DE
            fullDayRate = Money.of("28.00")
            arrivalDepartureRate = Money.of("14.00")
            inlandRules = true
        }

        val claimable = rate.calculateClaimableAmount(
            isArrivalDeparture = false,
            deductions = PerDiemMealDeductions(breakfast = true, lunch = true, dinner = true),
            employerContribution = Money.of("99.99"),
        )

        assertEquals(BigDecimal("0.00"), claimable)
    }
}
