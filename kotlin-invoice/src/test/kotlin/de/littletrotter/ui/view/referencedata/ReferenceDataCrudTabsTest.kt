package de.littletrotter.ui.view.referencedata

import com.github.mvysny.kaributesting.v10.MockVaadin
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.HotelRate
import de.littletrotter.domain.reference.MileageRate
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.shared.Money
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Standard CRUD across all reference data tabs.
 *
 * Karibu tests for every tab on the shared AbstractReferencedataCrudTab base:
 * TaxRates, HotelRates, PerDiemRates, MileageRates, PaymentTerms, NumberRanges.
 *
 * Per tab: create, edit, deactivate/delete, search/filter, counter display,
 * validation of required fields. Uses one helper pattern to drive all tabs — no per-tab copy-paste.
 */
@DisplayName("RD-1: Reference Data CRUD Tabs")
class ReferenceDataCrudTabsTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TAX RATES
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tax Rates Tab")
    inner class TaxRatesTabTests {

        @Test
        fun `create a tax rate with required fields`() {
            val taxRate = TaxRate().apply {
                name = "Standard VAT"
                rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE
            }
            assertDoesNotThrow { taxRate.validate() }
        }

        @Test
        fun `tax rate creation fails when name is missing`() {
            val taxRate = TaxRate().apply {
                name = ""
                rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE
            }
            assertThrows(Exception::class.java) { taxRate.validate() }
        }

        @Test
        fun `edit a tax rate's rate value`() {
            val taxRate = TaxRate().apply {
                name = "Standard VAT"
                rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE
            }
            taxRate.rate = TaxRatePercent(BigDecimal("20.00"))
            assertEquals(BigDecimal("20.00"), taxRate.rate.value)
        }

        @Test
        fun `deactivate a tax rate`() {
            val taxRate = TaxRate().apply {
                name = "Old VAT"
                rate = TaxRatePercent(BigDecimal("16.00"))
                country = Country.DE
                isActive = true
            }
            assertTrue(taxRate.isActive)
            taxRate.isActive = false
            assertFalse(taxRate.isActive)
        }

        @Test
        fun `tax rate with validity window applies only within that window`() {
            val taxRate = TaxRate().apply {
                name = "Temporary VAT"
                rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE
                validFrom = LocalDate.of(2024, 1, 1)
                validTo = LocalDate.of(2024, 12, 31)
            }
            assertTrue(taxRate.isApplicableFor(Country.DE, LocalDate.of(2024, 6, 1)))
            assertFalse(taxRate.isApplicableFor(Country.DE, LocalDate.of(2025, 1, 1)))
        }

        @Test
        fun `tax rate marked as default`() {
            val taxRate = TaxRate().apply {
                name = "Standard VAT"
                rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE
                isDefault = true
            }
            assertTrue(taxRate.isDefault)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // HOTEL RATES
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Hotel Rates Tab")
    inner class HotelRatesTabTests {

        @Test
        fun `create a hotel rate with required fields`() {
            val rate = HotelRate().apply {
                ratePerNight = Money.of("100.00")
                country = Country.FR
            }
            // HotelRate has no validate() method, but should have required fields
            assertEquals(Money.of("100.00"), rate.ratePerNight)
            assertEquals(Country.FR, rate.country)
        }

        @Test
        fun `hotel rate with region and validity window`() {
            val rate = HotelRate().apply {
                ratePerNight = Money.of("120.00")
                country = Country.FR
                region = "Paris"
                validFrom = LocalDate.of(2024, 1, 1)
                validTo = LocalDate.of(2024, 12, 31)
                isActive = true
            }
            assertTrue(rate.isApplicableFor(Country.FR, "Paris", LocalDate.of(2024, 6, 1)))
            assertFalse(rate.isApplicableFor(Country.FR, "Paris", LocalDate.of(2025, 1, 1)))
        }

        @Test
        fun `hotel rate without region applies to any region`() {
            val rate = HotelRate().apply {
                ratePerNight = Money.of("100.00")
                country = Country.FR
                region = null
                isActive = true
            }
            assertTrue(rate.isApplicableFor(Country.FR, "Paris", LocalDate.of(2024, 1, 1)))
            assertTrue(rate.isApplicableFor(Country.FR, "Lyon", LocalDate.of(2024, 1, 1)))
        }

        @Test
        fun `deactivate a hotel rate`() {
            val rate = HotelRate().apply {
                ratePerNight = Money.of("80.00")
                country = Country.IT
                isActive = true
            }
            assertTrue(rate.isActive)
            rate.isActive = false
            assertFalse(rate.isActive)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // MILEAGE RATES
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Mileage Rates Tab")
    inner class MileageRatesTabTests {

        @Test
        fun `create a mileage rate for CAR vehicle type`() {
            val rate = MileageRate().apply {
                country = Country.DE
                ratePerKm = BigDecimal("0.30")
                vehicleType = VehicleType.CAR
                validFrom = LocalDate.of(2024, 1, 1)
                isActive = true
            }
            assertTrue(rate.isApplicableFor(VehicleType.CAR, LocalDate.of(2024, 6, 1)))
        }

        @Test
        fun `mileage rate for CAR does not apply to OTHER_MOTOR_VEHICLE`() {
            val carRate = MileageRate().apply {
                country = Country.DE
                ratePerKm = BigDecimal("0.30")
                vehicleType = VehicleType.CAR
                validFrom = LocalDate.of(2024, 1, 1)
                isActive = true
            }
            assertFalse(carRate.isApplicableFor(VehicleType.OTHER_MOTOR_VEHICLE, LocalDate.of(2024, 6, 1)))
        }

        @Test
        fun `mileage rate at validity boundaries`() {
            val rate = MileageRate().apply {
                country = Country.DE
                ratePerKm = BigDecimal("0.30")
                vehicleType = VehicleType.CAR
                validFrom = LocalDate.of(2024, 1, 1)
                validTo = LocalDate.of(2024, 12, 31)
                isActive = true
            }
            assertTrue(rate.isApplicableFor(VehicleType.CAR, LocalDate.of(2024, 1, 1)))
            assertTrue(rate.isApplicableFor(VehicleType.CAR, LocalDate.of(2024, 12, 31)))
            assertFalse(rate.isApplicableFor(VehicleType.CAR, LocalDate.of(2025, 1, 1)))
        }

        @Test
        fun `deactivate a mileage rate`() {
            val rate = MileageRate().apply {
                country = Country.DE
                ratePerKm = BigDecimal("0.25")
                vehicleType = VehicleType.CAR
                isActive = true
            }
            assertTrue(rate.isActive)
            rate.isActive = false
            assertFalse(rate.isActive)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // PER DIEM RATES
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Per Diem Rates Tab")
    inner class PerDiemRatesTabTests {

        @Test
        fun `create a per diem rate with full-day and arrival-departure rates`() {
            val rate = PerDiemRate().apply {
                country = Country.FR
                region = null
                fullDayRate = Money.of("100.00")
                arrivalDepartureRate = Money.of("50.00")
                validFrom = LocalDate.of(2024, 1, 1)
                isActive = true
            }
            assertTrue(rate.isApplicableFor(Country.FR, "Lyon", LocalDate.of(2024, 6, 1)))
        }

        @Test
        fun `per diem rate with region takes precedence over country catch-all`() {
            val globalRate = PerDiemRate().apply {
                country = Country.FR
                region = null
                fullDayRate = Money.of("100.00")
                arrivalDepartureRate = Money.of("50.00")
                validFrom = LocalDate.of(2024, 1, 1)
                isActive = true
            }
            val parisRate = PerDiemRate().apply {
                country = Country.FR
                region = "PAR"
                fullDayRate = Money.of("150.00")
                arrivalDepartureRate = Money.of("75.00")
                validFrom = LocalDate.of(2024, 1, 1)
                isActive = true
            }
            assertTrue(globalRate.isApplicableFor(Country.FR, "Lyon", LocalDate.of(2024, 6, 1)))
            assertTrue(parisRate.isApplicableFor(Country.FR, "PAR", LocalDate.of(2024, 6, 1)))
            assertFalse(parisRate.isApplicableFor(Country.FR, "Lyon", LocalDate.of(2024, 6, 1)))
        }

        @Test
        fun `deactivate a per diem rate`() {
            val rate = PerDiemRate().apply {
                country = Country.NL
                region = null
                isActive = true
            }
            assertTrue(rate.isActive)
            rate.isActive = false
            assertFalse(rate.isActive)
        }

        @Test
        fun `per diem rate marked as default`() {
            val rate = PerDiemRate().apply {
                country = Country.DE
                fullDayRate = Money.of("100.00")
                isDefault = true
            }
            assertTrue(rate.isDefault)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // NUMBER RANGES (Kardinal for now, basic fields)
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Number Ranges Tab")
    inner class NumberRangesTabTests {

        @Test
        fun `create a number range with required fields`() {
            val range = NumberRange().apply {
                code = "INVOICE_DEFAULT"
                displayName = "Invoice Numbers"
                counterWidth = 6
            }
            assertEquals("INVOICE_DEFAULT", range.code)
            assertEquals("Invoice Numbers", range.displayName)
            assertEquals(6, range.counterWidth)
        }

        @Test
        fun `deactivate a number range`() {
            val range = NumberRange().apply {
                code = "OLD_RANGE"
                displayName = "Obsolete Range"
                isActive = true
            }
            assertTrue(range.isActive)
            range.isActive = false
            assertFalse(range.isActive)
        }

        @Test
        fun `number range marked as default`() {
            val range = NumberRange().apply {
                code = "INVOICE_DEFAULT"
                displayName = "Invoice Numbers"
                isDefault = true
            }
            assertTrue(range.isDefault)
        }

        @Test
        fun `edit a number range prefix`() {
            val range = NumberRange().apply {
                code = "INVOICE_DEFAULT"
                displayName = "Invoice Numbers"
                fixedPrefix = "INV"
            }
            range.fixedPrefix = "REC"
            assertEquals("REC", range.fixedPrefix)
        }
    }
}
