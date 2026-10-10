package de.littletrotter.application

import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.application.reference.PerDiemDay
import de.littletrotter.application.reference.PerDiemRateLookup
import de.littletrotter.application.reference.PerDiemService
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.repository.PerDiemRateRepository
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.shared.country.Country

class PerDiemServiceTest {

    // ── calculate ─────────────────────────────────────────────────────────────

    @Test
    fun `calculate full day without deductions returns full day rate`() {
        val service = serviceWith(deRate(fullDay = "28.00", arrDep = "14.00", inland = true))

        val result = service.calculate(PerDiemRateLookup("DE", ""), PerDiemDay(today, false), PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false), Money.ZERO)

        assertEquals(bd("28.00"), result)
    }

    @Test
    fun `calculate arrival departure without deductions returns arrival departure rate`() {
        val service = serviceWith(deRate(fullDay = "28.00", arrDep = "14.00", inland = true))

        val result = service.calculate(PerDiemRateLookup("DE", ""), PerDiemDay(today, true), PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false), Money.ZERO)

        assertEquals(bd("14.00"), result)
    }

    @Test
    fun `calculate inland breakfast deduction is 20 percent of full day rate`() {
        // inland rule: deduction always from fullDayRate (28.00 * 20% = 5.60)
        val service = serviceWith(deRate(fullDay = "28.00", arrDep = "14.00", inland = true))

        val result = service.calculate(PerDiemRateLookup("DE", ""), PerDiemDay(today, false), PerDiemMealDeductions(breakfast = true, lunch = false, dinner = false), Money.ZERO)

        assertEquals(bd("22.40"), result)
    }

    @Test
    fun `calculate inland all meals deduction clamps to zero`() {
        // fullDay = 28.00; deductions = 20%+40%+40% = 100% => 28.00 >= 28.00 => result = 0.00
        val service = serviceWith(deRate(fullDay = "28.00", arrDep = "14.00", inland = true))

        val result = service.calculate(PerDiemRateLookup("DE", ""), PerDiemDay(today, false), PerDiemMealDeductions(breakfast = true, lunch = true, dinner = true), Money.ZERO)

        assertEquals(bd("0.00"), result)
    }

    @Test
    fun `calculate international arrival departure breakfast deduction uses base rate`() {
        // international rule: deductions from baseRate (arrDep = 30.00, breakfast = 20% = 6.00)
        val service = serviceWith(deRate(fullDay = "60.00", arrDep = "30.00", inland = false))

        val result = service.calculate(PerDiemRateLookup("FR", ""), PerDiemDay(today, true), PerDiemMealDeductions(breakfast = true, lunch = false, dinner = false), Money.ZERO)

        assertEquals(bd("24.00"), result)
    }

    @Test
    fun `calculate employer contribution is subtracted from result`() {
        // fullDay = 28.00, no meal deductions, contribution = 10.00 => 28.00 - 10.00 = 18.00
        val service = serviceWith(deRate(fullDay = "28.00", arrDep = "14.00", inland = true))

        val result = service.calculate(
            PerDiemRateLookup("DE", ""), PerDiemDay(today, false),
            PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false),
            employerContribution = Money.of(bd("10.00")),
        )

        assertEquals(bd("18.00"), result)
    }

    @Test
    fun `calculate throws when no rate found`() {
        val service = serviceWith() // empty repo

        assertThrows(ApplicationValidationException::class.java) {
            service.calculate(PerDiemRateLookup("DE", ""), PerDiemDay(today, false), PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false), Money.ZERO)
        }
    }

    @Test
    fun `calculate throws when employer contribution is negative`() {
        val service = serviceWith(deRate(fullDay = "28.00", arrDep = "14.00", inland = true))

        assertThrows(ApplicationValidationException::class.java) {
            service.calculate(
                PerDiemRateLookup("DE", ""), PerDiemDay(today, false),
                PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false),
                employerContribution = Money.of(bd("-1.00")),
            )
        }
    }

    // ── createEntry ───────────────────────────────────────────────────────────

    @Test
    fun `createEntry snapshots rates and sets calculated amount`() {
        val service = serviceWith(deRate(fullDay = "28.00", arrDep = "14.00", inland = true))

        val entry = service.createEntry(PerDiemRateLookup("DE", ""), PerDiemDay(today, false), PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false), Money.ZERO)

        assertEquals(Money.of("28.00"), entry.fullDayRateSnapshot)
        assertEquals(Money.of("14.00"), entry.arrivalDepartureRateSnapshot)
        assertEquals(Money.of("28.00"), entry.calculatedAmount)
        assertEquals("DE", checkNotNull(entry.country).alpha2)
        assertEquals(today, entry.travelDate)
    }

    @Test
    fun `createEntry ueber die Laenderkennung nimmt eine gesetzte Region in den Kurznamen auf`() {
        val service = serviceWith(deRate(fullDay = "28.00", arrDep = "14.00", inland = true))

        val entry = service.createEntry(PerDiemRateLookup("DE", "Bayern"), PerDiemDay(today, false), PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false), Money.ZERO)

        assertEquals("DE Bayern " + today, entry.shortName, "eine gesetzte Region gehoert in den Kurznamen, nicht nur in die Repository-Suche")
        assertEquals("Bayern", entry.participant, "die Region wird als Teilnehmer uebernommen (RK-15)")
        assertEquals(entry.shortName, entry.description, "die Block-Beschreibung hat hier kein echtes UI-Formular und faellt auf den Kurznamen zurueck")
    }

    @Test
    fun `createEntry throws when no rate found`() {
        val service = serviceWith() // empty repo

        assertThrows(ApplicationValidationException::class.java) {
            service.createEntry(PerDiemRateLookup("DE", ""), PerDiemDay(today, false), PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false), Money.ZERO)
        }
    }

    @Test
    fun `boundary - createEntry throws for an unrecognized ISO-2 country code`() {
        val service = serviceWith(deRate(fullDay = "28.00", arrDep = "14.00", inland = true))

        val exception = assertThrows(ApplicationValidationException::class.java) {
            service.createEntry(PerDiemRateLookup("XX", ""), PerDiemDay(today, false), PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false), Money.ZERO)
        }
        assertEquals("Invalid country: XX", exception.message)
    }

    // ── createEntry(rate, ...) — vorausgewaehlter Satz ohne DB-Lookup ────────────

    @Test
    fun `createEntry mit vorausgewaehltem Satz uebernimmt den Satz direkt ohne Repository-Zugriff`() {
        val service = serviceWith() // leeres Repository -- der direkte Pfad braucht keins
        val rate = deRate(fullDay = "28.00", arrDep = "14.00", inland = true)

        val entry = service.createEntry(rate, PerDiemDay(today, false), PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false))

        assertEquals(Money.of("28.00"), entry.calculatedAmount)
        assertEquals(rate, entry.perDiemRate, "der uebergebene Satz wird unveraendert als Referenz uebernommen")
    }

    @Test
    fun `createEntry mit vorausgewaehltem Satz wirft wenn die Arbeitgeberleistung negativ ist`() {
        val service = serviceWith()
        val rate = deRate(fullDay = "28.00", arrDep = "14.00", inland = true)

        assertThrows(ApplicationValidationException::class.java) {
            service.createEntry(rate, PerDiemDay(today, false), PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false), employerContribution = Money.of(bd("-1.00")))
        }
    }

    @Test
    fun `createEntry mit vorausgewaehltem Satz wirft wenn der Satz kein Land traegt`() {
        val service = serviceWith()
        val rate = deRate(fullDay = "28.00", arrDep = "14.00", inland = true).apply { country = null }

        val ex = assertThrows(ApplicationValidationException::class.java) {
            service.createEntry(rate, PerDiemDay(today, false), PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false))
        }
        assertEquals("Per-Diem-Satz hat kein Land.", ex.message)
    }

    @Test
    fun `createEntry mit vorausgewaehltem Satz ohne Regionsangabe speichert den Kurznamen als Teilnehmer`() {
        val service = serviceWith()
        val rate = deRate(fullDay = "28.00", arrDep = "14.00", inland = true).apply { region = null }

        val entry = service.createEntry(rate, PerDiemDay(today, false), PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false))

        assertEquals("DE " + today, entry.shortName, "ohne Region enthaelt der Kurzname nur Land und Datum")
        // participant ist Pflichtfeld (nicht leer) -- ohne eigene Region faellt es auf den
        // bereits berechneten Kurznamen zurueck, statt einen Leerstring zu speichern.
        assertEquals(entry.shortName, entry.participant, "eine fehlende Region faellt auf den Kurznamen zurueck, niemals auf einen Leerstring")
        // description (Block-Ebene) hat hier kein echtes UI-Formular -- der Kurzname dient als
        // sinnvoller Platzhalter, unabhaengig von der Region.
        assertEquals(entry.shortName, entry.description, "die Block-Beschreibung faellt mangels echtem Formular ebenfalls auf den Kurznamen zurueck")
    }

    @Test
    fun `createEntry mit vorausgewaehltem Satz uebernimmt eine gesetzte Region in Kurzname und Teilnehmer`() {
        val service = serviceWith()
        val rate = deRate(fullDay = "28.00", arrDep = "14.00", inland = true).apply { region = "Bayern" }

        val entry = service.createEntry(rate, PerDiemDay(today, false), PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false))

        assertEquals("DE Bayern " + today, entry.shortName)
        assertEquals("Bayern", entry.participant)
        assertEquals(entry.shortName, entry.description, "die Block-Beschreibung bleibt der Kurzname, unabhaengig von der Region")
    }

    @Test
    fun `createEntry mit vorausgewaehltem Satz behandelt eine leere Region wie eine fehlende`() {
        val service = serviceWith()
        val rate = deRate(fullDay = "28.00", arrDep = "14.00", inland = true).apply { region = "" }

        val entry = service.createEntry(rate, PerDiemDay(today, false), PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false))

        assertEquals("DE " + today, entry.shortName, "eine leere Region darf nicht als eigenes Segment im Kurznamen auftauchen")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private val today: LocalDate = LocalDate.of(2025, 1, 15)

    private fun bd(value: String) = BigDecimal(value)

    private fun deRate(
        fullDay: String,
        arrDep: String,
        inland: Boolean,
        country: String = if (inland) "DE" else "FR",
    ) = PerDiemRate().apply {
        this.country = Country.fromAlpha2(country)
        this.region = null
        this.fullDayRate = Money.of(bd(fullDay))
        this.arrivalDepartureRate = Money.of(bd(arrDep))
        this.inlandRules = inland
        this.validFrom = LocalDate.of(2024, 1, 1)
    }

    private fun serviceWith(vararg rates: PerDiemRate): PerDiemService =
        PerDiemService(InMemoryPerDiemRepo(*rates))

    private class InMemoryPerDiemRepo(vararg rates: PerDiemRate) : PerDiemRateRepository {
        private val data = rates.mapIndexed { i, r -> (i + 1L).also { r.id = it } to r }.toMap().toMutableMap()
        private var seq = data.size.toLong()

        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String, sortDir: SortDirection): List<PerDiemRate> {
            val filtered = if (filterText.isNullOrBlank()) data.values.toList() else data.values.filter { it.country?.alpha2?.lowercase()?.contains(filterText) == true || it.region?.lowercase()?.contains(filterText) == true }
            return filtered
        }

        override fun count(filterText: String?): Long = if (filterText.isNullOrBlank()) data.size.toLong() else data.values.count { it.country?.alpha2?.lowercase()?.contains(filterText) == true || it.region?.lowercase()?.contains(filterText) == true }.toLong()
        override fun countInactive(filterText: String?): Long = data.values.count { !it.isActive }.toLong()
        override fun findById(id: Long) = data[id]
        override fun findDefault(): PerDiemRate? = data.values.firstOrNull { it.isDefault }
        override fun clearAllDefaults() { data.values.forEach { it.isDefault = false } }
        override fun findByCountryAndRegionAndDate(country: Country, region: String?, date: LocalDate) =
            data.values.firstOrNull { it.country == country }
        override fun save(rate: PerDiemRate): PerDiemRate {
            val id = rate.id ?: ++seq
            rate.id = id; data[id] = rate; return rate
        }
        override fun delete(id: Long) { data.remove(id) }
    }
}
