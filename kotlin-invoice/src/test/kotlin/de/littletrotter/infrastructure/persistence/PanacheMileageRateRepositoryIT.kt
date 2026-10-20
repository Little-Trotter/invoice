package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.MileageRate
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.domain.repository.MileageRateRepository
import de.littletrotter.domain.shared.SortDirection
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * [PanacheMileageRateRepository] had no direct test before this class (0 % branch
 * coverage). V2__baseline_seed.sql seeds one active CAR and one active OTHER_MOTOR_VEHICLE row,
 * both `country = DE`, `validFrom = 2024-01-01`, `validTo = NULL` — this fixture never touches
 * that seed data, it only asserts on its own distinctively-marked rows (see the `source` literal
 * used below), the same scoping convention [PanacheNumberRangeRepositoryIT] uses for the same
 * shared, uncleaned database.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheMileageRateRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: MileageRateRepository

    private fun newRate(
        country: Country?,
        ratePerKm: BigDecimal,
        vehicleType: VehicleType,
        active: Boolean,
        validFrom: LocalDate?,
        source: String,
    ) = MileageRate().edit {
        this.country = country
        this.ratePerKm = ratePerKm
        this.vehicleType = vehicleType
        this.isActive = active
        this.validFrom = validFrom
        this.source = source
    }

    /**
     * Removes this class's own rows again. Not optional politeness: `TravelRateMigrationIT`
     * asserts a *global* invariant ("exactly two active mileage rows exist, one per vehicle
     * type") against the same shared, never-cleaned database, so every active row left behind
     * here fails that test instead of this one. Scoped strictly to this fixture's own `source`
     * marker prefix so no other fixture's data is touched.
     */
    @AfterEach
    fun removeOwnRows() {
        QuarkusTransaction.requiringNew().run {
            em.createQuery("DELETE FROM MileageRate m WHERE m.source LIKE 'ZZ-AP-COV-4-%'")
                .executeUpdate()
        }
    }

    private fun persist(rate: MileageRate): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            em.persist(rate)
            em.flush()
            rate.id!!
        }

    @Test
    fun `findByVehicleTypeAndDate returns the most recent active match and null when none applies`() {
        // A far-future validFrom guarantees this row sorts first (ORDER BY validFrom DESC)
        // ahead of the seeded 2024-01-01 rows, regardless of what other fixtures add.
        persist(
            newRate(
                Country.DE, BigDecimal("0.4000"), VehicleType.OTHER_MOTOR_VEHICLE,
                active = true, validFrom = LocalDate.of(2098, 1, 1), source = "ZZ-AP-COV-4-vt",
            ),
        )

        val found = repository.findByVehicleTypeAndDate(VehicleType.OTHER_MOTOR_VEHICLE, LocalDate.of(2098, 6, 1))
        assertNotNull(found)
        assertEquals("ZZ-AP-COV-4-vt", found!!.source)

        // Before any seeded or fixture row's validFrom (earliest is 2024-01-01): no active row
        // can possibly match, exercising the "no result" branch of the inline firstOrNull().
        assertNull(repository.findByVehicleTypeAndDate(VehicleType.CAR, LocalDate.of(2000, 1, 1)))
    }

    @Test
    fun `findAll returns fixture rows unfiltered, resolveSortExpr falls back for an unknown sort field`() {
        val id = persist(
            newRate(
                Country.LU, BigDecimal("0.1234"), VehicleType.CAR,
                active = false, validFrom = LocalDate.of(2026, 1, 1), source = "ZZ-AP-COV-4-filter",
            ),
        )

        val unfiltered = repository.findAll(filterText = null)
        assertTrue(unfiltered.size >= 2, "at least the two seeded rows plus this fixture's own row")
        assertTrue(unfiltered.any { it.id == id })

        // sortField = "country" is in the allowlist, an unknown one falls back to the default —
        // both sides of resolveSortExpr's `allowed[sortField] ?: default` elvis. filterText stays
        // null here: see the bug documented below.
        val knownSort = repository.findAll(0, 50, null, sortField = "ratePerKm", sortDir = SortDirection.ASC)
        assertTrue(knownSort.any { it.id == id })
        val unknownSort = repository.findAll(0, 50, null, sortField = "no-such-field", sortDir = SortDirection.DESC)
        assertTrue(unknownSort.any { it.id == id })
    }

    @Test
    fun `non-blank filter text resolves to countries and filters instead of throwing`() {
        // Regression guard. This used to throw for ANY non-blank filterText: likeFilter("m.country")
        // built `lower(m.country) LIKE ...`, but country is a Country converted to Integer, and
        // Hibernate rejects lower() on an integer argument while parsing the query. The filter now
        // resolves the text to Country values and compares with IN.
        val luId = persist(
            newRate(
                Country.LU, BigDecimal("0.3000"), VehicleType.CAR,
                active = true, validFrom = LocalDate.of(2097, 1, 1), source = "ZZ-AP-COV-4-filter",
            ),
        )

        val matches = repository.findAll(0, 50, "lu")
        assertTrue(matches.any { it.id == luId }, "the LU row must match the text 'lu'")
        assertTrue(matches.all { it.country == Country.LU }, "only LU rows may match")
        assertTrue(repository.count("lu") >= 1)

        // Text matching no country at all yields no rows rather than an error or everything.
        assertTrue(repository.findAll(0, 50, "zzz").isEmpty())
        assertEquals(0L, repository.count("zzz"))
    }

    @Test
    fun `countInactive limits to inactive rows and honours a country filter`() {
        val before = repository.countInactive(null)
        persist(
            newRate(
                Country.IE, BigDecimal("0.5000"), VehicleType.CAR,
                active = false, validFrom = LocalDate.of(2026, 2, 1), source = "ZZ-AP-COV-4-inactive",
            ),
        )

        assertEquals(before + 1, repository.countInactive(null))

        // Same filter path as findAll/count: resolves "ie" to Country.IE instead of throwing.
        assertEquals(1L, repository.countInactive("ie"))
        assertEquals(0L, repository.countInactive("zzz"))
    }

    @Test
    fun `save inserts a new rate then updates it via merge, delete removes it`() {
        val rate = newRate(
            Country.AT, BigDecimal("0.2500"), VehicleType.CAR,
            active = false, validFrom = LocalDate.of(2026, 3, 1), source = "ZZ-AP-COV-4-save-v1",
        )

        val saved = QuarkusTransaction.requiringNew().call<MileageRate> { repository.save(rate) }
        assertNotNull(saved.id)

        saved.edit { source = "ZZ-AP-COV-4-save-v2" }
        QuarkusTransaction.requiringNew().run { repository.save(saved) }

        val reloaded = repository.findById(saved.id!!)
        assertEquals("ZZ-AP-COV-4-save-v2", reloaded?.source)

        QuarkusTransaction.requiringNew().run { repository.delete(saved.id!!) }
        em.clear()
        assertNull(repository.findById(saved.id!!))

        // Deleting an id that is already gone must be a no-op, not an error — exercises the
        // `em.find(...)?.let { ... }` null-safety branch's "not found" side.
        QuarkusTransaction.requiringNew().run { repository.delete(saved.id!!) }
    }
}
