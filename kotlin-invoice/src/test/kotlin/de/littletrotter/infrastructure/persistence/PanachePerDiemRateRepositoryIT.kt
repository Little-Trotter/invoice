package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.repository.PerDiemRateRepository
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.TestTransaction
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
import java.time.LocalDate

/**
 * AP- [PanachePerDiemRateRepository] had no direct test before this class (0 % branch
 * coverage). V2__baseline_seed.sql seeds 21 active rows across many countries — but never
 * [Country.PT], so this fixture uses PT to stay isolated from all of them (same trick as
 * [PanacheHotelRateRepositoryIT]). It otherwise only asserts on its own distinctively-marked
 * `ZZ-AP--*` rows, the scoping convention [PanacheNumberRangeRepositoryIT] uses for the
 * same shared, uncleaned database.
 *
 * `findAll`/`count`/`countInactive` filter on `likeFilter("p.country") OR likeFilter("p.region")`
 * — `p.country`'s `lower()` call hits the same pre-existing Hibernate `lower()`-on-Integer bug
 * documented in [PanacheMileageRateRepositoryIT] (query *parsing* fails before either operand of
 * the `OR` is evaluated), reproduced, not fixed, here too.
 *
 * [PanachePerDiemRateRepository.clearAllDefaults] is an unconditional `UPDATE ... SET is_default =
 * FALSE WHERE is_default = TRUE` across the *whole* table, not scoped to this fixture's own rows —
 * exercising it as a normal, committing call would strip `is_default` from the one seeded German
 * row that [findDefault] (and other fixtures/UI tests) expect. [clearAllDefaultsRevokesTheSeededRow]
 * instead runs it under `@TestTransaction`: `clearAllDefaults` runs in the caller's
 * already-open, never-committed transaction, so the UPDATE is never visible to any
 * other connection and Quarkus rolls the whole thing back once the test method returns — the same
 * "never touch another test's data" rule [PanacheNumberRangeRepositoryIT] documents, kept intact
 * by transactional isolation instead of by leaving the method untested.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanachePerDiemRateRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: PerDiemRateRepository

    private fun newRate(
        country: Country?,
        validFrom: LocalDate?,
        validTo: LocalDate?,
        region: String?,
        active: Boolean,
        source: String,
    ) = PerDiemRate().edit {
        this.country = country
        this.fullDayRate = Money.of("40.00")
        this.arrivalDepartureRate = Money.of("27.00")
        this.inlandRules = false
        this.isDefault = false
        this.validFrom = validFrom
        this.validTo = validTo
        this.region = region
        this.isActive = active
        this.source = source
    }

    private fun persist(rate: PerDiemRate): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            em.persist(rate)
            em.flush()
            rate.id!!
        }

    /**
     * Removes this class's own rows again. Not optional politeness: sibling ITs assert *global*
     * invariants over this same shared, never-cleaned table (see TravelRateMigrationIT /
     * PaymentTermMigrationIT), so a row left behind here fails one of those instead of this
     * class. Scoped strictly to the ZZ-AP- marker so no other fixture's data is touched.
     */
    @AfterEach
    fun removeOwnRows() {
        QuarkusTransaction.requiringNew().run {
            em.createQuery("DELETE FROM PerDiemRate e WHERE e.source LIKE 'ZZ-AP-COV-4%'")
                .executeUpdate()
        }
    }

    @Test
    fun `findByCountryAndRegionAndDate returns the active match and null outside the validity window`() {
        persist(
            newRate(
                Country.PT, validFrom = LocalDate.of(2026, 1, 1), validTo = null,
                region = null, active = true, source = "ZZ-AP-COV-4-pd",
            ),
        )

        val found = repository.findByCountryAndRegionAndDate(Country.PT, null, LocalDate.of(2026, 6, 1))
        assertNotNull(found)
        assertEquals("ZZ-AP-COV-4-pd", found!!.source)

        // Before this fixture's only PT row's validFrom: no active row can possibly match,
        // exercising the "no result" branch of the inline firstOrNull().
        assertNull(repository.findByCountryAndRegionAndDate(Country.PT, null, LocalDate.of(2000, 1, 1)))
    }

    @Test
    fun `findAll returns fixture rows unfiltered, non-blank filter text hits the country type bug`() {
        val id = persist(
            newRate(
                Country.PT, validFrom = LocalDate.of(2026, 2, 1), validTo = null,
                region = "Porto", active = true, source = "ZZ-AP-COV-4-pd-findAll",
            ),
        )

        val unfiltered = repository.findAll(filterText = null)
        assertTrue(unfiltered.size >= 22, "the 21 seeded rows plus this fixture's own rows")
        assertTrue(unfiltered.any { it.id == id })

        // Regression guard: this used to throw for any non-blank filterText, because lower(p.country)
        // was applied to a Country converted to Integer. The country half of the OR now resolves the
        // text to Country values; the region half still uses a real LIKE on a String column.
        val matches = repository.findAll(0, 50, "pt")
        assertTrue(matches.any { it.id == id }, "the PT row must match the text 'pt'")
        assertTrue(repository.count("pt") >= 1)
        assertTrue(repository.findAll(0, 50, "zzz-no-country-no-region").isEmpty())
        assertEquals(0L, repository.count("zzz-no-country-no-region"))
    }

    @Test
    fun `countInactive limits to inactive rows and honours a country filter`() {
        val before = repository.countInactive(null)
        persist(
            newRate(
                Country.PT, validFrom = LocalDate.of(2026, 3, 1), validTo = null,
                region = null, active = false, source = "ZZ-AP-COV-4-pd-inactive",
            ),
        )

        assertEquals(before + 1, repository.countInactive(null))
        assertTrue(repository.countInactive("pt") >= 1)
    }

    @Test
    fun `findDefault returns the single seeded German default row`() {
        val default = repository.findDefault()
        assertNotNull(default, "V2 seeds exactly one is_default = TRUE row (Inland, DE)")
        assertTrue(default!!.isDefault)
        assertEquals(Country.DE, default.country)
    }

    @Test
    fun `save inserts a new rate then updates it via merge, delete removes it`() {
        val rate = newRate(
            Country.PT, validFrom = LocalDate.of(2026, 4, 1), validTo = null,
            region = null, active = false, source = "ZZ-AP-COV-4-pd-save-v1",
        )

        val saved = QuarkusTransaction.requiringNew().call<PerDiemRate> { repository.save(rate) }
        assertNotNull(saved.id)

        saved.edit { source = "ZZ-AP-COV-4-pd-save-v2" }
        QuarkusTransaction.requiringNew().run { repository.save(saved) }

        val reloaded = repository.findById(saved.id!!)
        assertEquals("ZZ-AP-COV-4-pd-save-v2", reloaded?.source)

        QuarkusTransaction.requiringNew().run { repository.delete(saved.id!!) }
        em.clear()
        assertNull(repository.findById(saved.id!!))

        // Deleting an id that is already gone must be a no-op, not an error — exercises the
        // `em.find(...)?.let { ... }` null-safety branch's "not found" side.
        QuarkusTransaction.requiringNew().run { repository.delete(saved.id!!) }
    }

    @Test
    @TestTransaction
    fun `boundary - clearAllDefaults revokes the seeded default row, never committed thanks to TestTransaction`() {
        val before = repository.findDefault()
        assertNotNull(before, "V2 seeds exactly one is_default = TRUE row (Inland, DE)")

        repository.clearAllDefaults()
        em.flush()
        em.clear()

        assertNull(repository.findDefault(), "clearAllDefaults must revoke every is_default flag, including the seed row")
    }
}
