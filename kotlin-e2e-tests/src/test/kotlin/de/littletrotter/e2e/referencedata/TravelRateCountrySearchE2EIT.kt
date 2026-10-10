package de.littletrotter.e2e.referencedata

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.HotelRate
import de.littletrotter.domain.reference.MileageRate
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.repository.HotelRateRepository
import de.littletrotter.domain.repository.MileageRateRepository
import de.littletrotter.domain.repository.PerDiemRateRepository
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * finding-fixes (2026-09-01) — the mileage/hotel/per-diem-rate reference lists' free-text search
 * (`MileageRatesTab`/`HotelRatesTab`/`PerDiemRatesTab`) used `likeFilter("country")`, i.e.
 * `lower(country) LIKE :f ESCAPE '\'`. `country` is a [Country] enum persisted as an `Integer`
 * ([de.littletrotter.domain.masterdata.CountryAttributeConverter]), and Postgres/Hibernate
 * rejects `lower()` on an integer column at query-*parse* time — so ANY non-blank search term in
 * any of these three lists threw, not just ones that happened to find nothing. Fixed by resolving
 * the search text to matching [Country] values first ([Country.matching]) and comparing with
 * `IN (...)` ([de.littletrotter.infrastructure.persistence.countryPredicate]); `PerDiemRate` keeps
 * an additional real `LIKE` on its text `region` column, which was never part of the defect (it is
 * a `VARCHAR`, `lower()` is legal on it) and must keep working unchanged.
 *
 * All three repositories ([MileageRateRepository]/[HotelRateRepository]/[PerDiemRateRepository])
 * are `@ApplicationScoped` CDI beans backed by the real, Flyway-migrated Postgres
 * ([PostgresTestResource]) — the full baseline seed (`V2__baseline_seed.sql`) is present, so this
 * suite never assumes these tables are empty: every row this class inserts uses `HU` (Hungary,
 * numeric 348), which none of the three seed sections use, or a distinctive region marker, and
 * every row is deleted again in [cleanup] regardless of a test's outcome. [MileageRate] rows are
 * additionally always `isActive = false` — the seed's own two `DE` rows are the sole active
 * mileage rows an unrelated suite (`TravelRateMigrationIT`, `kotlin-invoice`) pins as an exact
 * count; this suite runs against a different module's Postgres than that one; kept `false` anyway
 * on principle, since search/count/countInactive never filter on `isActive` themselves and an
 * inactive row exercises the exact same query path.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class TravelRateCountrySearchE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var mileageRateRepository: MileageRateRepository

    @Inject
    lateinit var hotelRateRepository: HotelRateRepository

    @Inject
    lateinit var perDiemRateRepository: PerDiemRateRepository

    private val createdMileageIds = mutableListOf<Long>()
    private val createdHotelIds = mutableListOf<Long>()
    private val createdPerDiemIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        QuarkusTransaction.requiringNew().run {
            createdMileageIds.forEach { em.find(MileageRate::class.java, it)?.let(em::remove) }
            createdHotelIds.forEach { em.find(HotelRate::class.java, it)?.let(em::remove) }
            createdPerDiemIds.forEach { em.find(PerDiemRate::class.java, it)?.let(em::remove) }
        }
    }

    private fun insertMileage(country: Country): Long =
        QuarkusTransaction.requiringNew().call {
            val row = MileageRate().apply {
                this.country = country
                ratePerKm = BigDecimal("0.300")
                validFrom = LocalDate.of(2024, 1, 1)
                isActive = false // see class KDoc: never a fresh active row in the shared seed
                source = "TravelRateCountrySearchE2EIT fixture"
            }
            em.persist(row)
            em.flush()
            row.id!!
        }.also { createdMileageIds += it }

    private fun insertHotel(country: Country, region: String? = null): Long =
        QuarkusTransaction.requiringNew().call {
            val row = HotelRate().apply {
                this.country = country
                this.region = region
                ratePerNight = Money.of("80.00")
                validFrom = LocalDate.of(2026, 1, 1)
                isActive = true
                source = "TravelRateCountrySearchE2EIT fixture"
            }
            em.persist(row)
            em.flush()
            row.id!!
        }.also { createdHotelIds += it }

    private fun insertPerDiem(country: Country, region: String? = null): Long =
        QuarkusTransaction.requiringNew().call {
            val row = PerDiemRate().apply {
                this.country = country
                this.region = region
                fullDayRate = Money.of("28.00")
                arrivalDepartureRate = Money.of("14.00")
                validFrom = LocalDate.of(2026, 1, 1)
                isActive = true
                source = "TravelRateCountrySearchE2EIT fixture"
            }
            em.persist(row)
            em.flush()
            row.id!!
        }.also { createdPerDiemIds += it }

    private fun rawCount(table: String): Long =
        (em.createNativeQuery("SELECT COUNT(*) FROM $table").singleResult as Number).toLong()

    // ── Happy path — an existing country's alpha-2 code returns exactly that country's rows ────

    @Test
    fun `mileage rate search by an existing country's alpha-2 code returns exactly that country's rows`() {
        val id = insertMileage(Country.HU)

        val results = mileageRateRepository.findAll(0, 60, "hu") // lower-case: search is case-insensitive
        assertEquals(1, results.size)
        assertEquals(id, results.single().id)
        assertEquals(Country.HU, results.single().country)
        assertEquals(1L, mileageRateRepository.count("hu"))
    }

    @Test
    fun `hotel rate search by an existing country's alpha-2 code returns exactly that country's rows`() {
        val id = insertHotel(Country.HU)

        val results = hotelRateRepository.findAll(0, 60, "HU")
        assertEquals(1, results.size)
        assertEquals(id, results.single().id)
        assertEquals(Country.HU, results.single().country)
        assertEquals(1L, hotelRateRepository.count("HU"))
    }

    @Test
    fun `per diem rate search by an existing country's alpha-2 code returns exactly that country's rows`() {
        val catchAllId = insertPerDiem(Country.HU)
        val regionId = insertPerDiem(Country.HU, region = "E2E-RD2-Debrecen")

        val results = perDiemRateRepository.findAll(0, 60, "HU")
        assertEquals(setOf(catchAllId, regionId), results.map { it.id }.toSet())
        assertEquals(2L, perDiemRateRepository.count("HU"))
    }

    // ── Border — text matching no country returns an empty result and count 0, never an error ──

    @Test
    fun `mileage rate search with a filter matching no country returns empty, not everything and not a throw`() {
        insertMileage(Country.HU)

        // The regression: before the fix this threw a PersistenceException at query-parse time
        // for ANY non-blank filter, including one that would legitimately find nothing.
        val results = mileageRateRepository.findAll(0, 60, "zz")
        assertTrue(results.isEmpty(), "no country's alpha-2 code contains 'zz'")
        assertEquals(0L, mileageRateRepository.count("zz"))
    }

    @Test
    fun `hotel rate search with a filter matching no country returns empty, not everything and not a throw`() {
        insertHotel(Country.HU)

        val results = hotelRateRepository.findAll(0, 60, "zz")
        assertTrue(results.isEmpty())
        assertEquals(0L, hotelRateRepository.count("zz"))
    }

    @Test
    fun `per diem rate search with a filter matching no country or region returns empty, not everything`() {
        insertPerDiem(Country.HU)
        insertPerDiem(Country.HU, region = "E2E-RD2-Debrecen")

        val results = perDiemRateRepository.findAll(0, 60, "zz")
        assertTrue(results.isEmpty(), "'zz' matches neither a country code nor either fixture's region")
        assertEquals(0L, perDiemRateRepository.count("zz"))
    }

    // ── Border — blank/null filter returns everything, i.e. behaves exactly like no filter ──────

    @Test
    fun `blank or null filter text returns every row, same as counting with no filter at all`() {
        insertMileage(Country.HU)
        insertHotel(Country.HU)
        insertPerDiem(Country.HU)

        assertEquals(rawCount("mileage_rate"), mileageRateRepository.count(null))
        assertEquals(rawCount("mileage_rate"), mileageRateRepository.count("   "))
        assertEquals(rawCount("hotel_rate"), hotelRateRepository.count(null))
        assertEquals(rawCount("hotel_rate"), hotelRateRepository.count(""))
        assertEquals(rawCount("per_diem_rate"), perDiemRateRepository.count(null))
        assertEquals(rawCount("per_diem_rate"), perDiemRateRepository.count("  "))
    }

    // ── Border — per-diem still matches on `region` via a real LIKE, unaffected by the fix ─────

    @Test
    fun `per diem rate search matches on region via LIKE when the text matches no country at all`() {
        val catchAllId = insertPerDiem(Country.HU)
        val regionId = insertPerDiem(Country.HU, region = "E2E-RD2-Debrecen")

        // "E2E-RD2-Debrecen" is far longer than any alpha-2 code, so Country.matching(...)
        // returns emptyList() and countryPredicate degrades to "1 = 0" — the whole result must
        // come from the OR'd region LIKE, proving that branch still works standalone.
        val results = perDiemRateRepository.findAll(0, 60, "E2E-RD2-Debrecen")
        assertEquals(listOf(regionId), results.map { it.id })
        assertTrue(catchAllId !in results.map { it.id }, "the catch-all (region-less) row must not match on region text")
        assertEquals(1L, perDiemRateRepository.count("E2E-RD2-Debrecen"))

        // A partial, lower-cased substring must match too - a real LIKE, not an equality check.
        val partial = perDiemRateRepository.findAll(0, 60, "debrecen")
        assertEquals(listOf(regionId), partial.map { it.id })
    }
}
