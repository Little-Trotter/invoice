package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.reference.HotelRate
import de.littletrotter.domain.reference.MileageRate
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.Money
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ReferenceDataCrudIT {

    @Inject
    lateinit var em: EntityManager

    @Test
    @Transactional
    fun `TaxRate - insert update delete`() {
        val tax = TaxRate().apply {
            // `code` is NOT NULL since V904 — display and export reference the rule by it,
            // not by name/rate.
            code = "IT_CRUD_DE_STANDARD_19"
            // chk_tax_rate_selectable_needs_category (V904): selectable means the UStG category
            // must be set. S = standard rate.
            category = "S"
            name = "MwSt 19%"
            rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE
            validFrom = LocalDate.of(2020, 1, 1)
        }
        em.persist(tax)
        em.flush()

        assertNotNull(tax.id)
        assertEquals(0L, tax.version)

        tax.name = "MwSt 19% (updated)"
        em.merge(tax)
        em.flush()
        em.clear()

        val reloaded = em.find(TaxRate::class.java, tax.id)!!
        assertEquals(1L, reloaded.version)
        assertEquals("MwSt 19% (updated)", reloaded.name)

        em.remove(reloaded)
        em.flush()
        em.clear()
        assertNull(em.find(TaxRate::class.java, tax.id))
    }

    @Test
    @Transactional
    fun `PerDiemRate - insert update delete`() {
        val rate = PerDiemRate().apply {
            country = Country.DE
            fullDayRate = Money.of("28.00")
            arrivalDepartureRate = Money.of("14.00")
            inlandRules = true
            validFrom = LocalDate.of(2024, 1, 1)
            // chk_per_diem_rate_active_needs_source (V905): a selectable row must name its
            // source. Only deactivated legacy rows may go without one.
            source = "Testdaten ReferenceDataCrudIT"
        }
        em.persist(rate)
        em.flush()

        assertNotNull(rate.id)
        assertEquals(0L, rate.version)

        rate.fullDayRate = Money.of("30.00")
        em.merge(rate)
        em.flush()
        em.clear()

        val reloaded = em.find(PerDiemRate::class.java, rate.id)!!
        assertEquals(1L, reloaded.version)
        assertEquals(Money.of("30.00"), reloaded.fullDayRate)

        em.remove(reloaded)
        em.flush()
        em.clear()
        assertNull(em.find(PerDiemRate::class.java, rate.id))
    }

    @Test
    @Transactional
    fun `MileageRate - insert update delete`() {
        val rate = MileageRate().apply {
            country = Country.DE
            ratePerKm = BigDecimal("0.3000")
            validFrom = LocalDate.of(2024, 1, 1)
            // chk_mileage_rate_active_needs_vehicle_type (V905) — see PerDiemRate above.
            vehicleType = VehicleType.CAR
        }
        em.persist(rate)
        em.flush()

        assertNotNull(rate.id)
        assertEquals(0L, rate.version)

        rate.ratePerKm = BigDecimal("0.3500")
        em.merge(rate)
        em.flush()
        em.clear()

        val reloaded = em.find(MileageRate::class.java, rate.id)!!
        assertEquals(1L, reloaded.version)
        assertEquals(0, BigDecimal("0.35").compareTo(reloaded.ratePerKm))

        em.remove(reloaded)
        em.flush()
        em.clear()
        assertNull(em.find(MileageRate::class.java, rate.id))
    }

    @Test
    @Transactional
    fun `HotelRate - insert update delete`() {
        val rate = HotelRate().apply {
            country = Country.DE
            ratePerNight = Money.of("120.00")
            validFrom = LocalDate.of(2024, 1, 1)
            // chk_hotel_rate_active_needs_source (V905) — see PerDiemRate above.
            source = "Testdaten ReferenceDataCrudIT"
        }
        em.persist(rate)
        em.flush()

        assertNotNull(rate.id)
        assertEquals(0L, rate.version)

        rate.ratePerNight = Money.of("130.00")
        em.merge(rate)
        em.flush()
        em.clear()

        val reloaded = em.find(HotelRate::class.java, rate.id)!!
        assertEquals(1L, reloaded.version)
        assertEquals(Money.of("130.00"), reloaded.ratePerNight)

        em.remove(reloaded)
        em.flush()
        em.clear()
        assertNull(em.find(HotelRate::class.java, rate.id))
    }
}
