package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.HotelRate
import de.littletrotter.domain.repository.HotelRateRepository
import de.littletrotter.shared.Money
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
import java.time.LocalDate

/**
 * AP- [PanacheHotelRateRepository] had no direct test before this class (0 % branch
 * coverage). V2__baseline_seed.sql seeds five active rows (AT, BE, NL, LU, FR) — this fixture
 * uses [Country.PT], which none of them use, so [findByCountryAndDate] assertions need no
 * far-future-date trick to stay isolated. It otherwise only asserts on its own
 * distinctively-marked `ZZ-AP--*` rows, the same scoping convention
 * [PanacheNumberRangeRepositoryIT] uses for the same shared, uncleaned database.
 *
 * Like [PanacheMileageRateRepository], `findAll`/`count`/`countInactive` filter on
 * `likeFilter("h.country")`, which hits the same pre-existing Hibernate `lower()`-on-Integer bug
 * documented in [PanacheMileageRateRepositoryIT] (`country` is `Country`, converted to `Integer`
 * by `CountryAttributeConverter`) — reproduced, not fixed, here too.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheHotelRateRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: HotelRateRepository

    private fun newRate(
        country: Country?,
        validFrom: LocalDate?,
        validTo: LocalDate?,
        region: String?,
        active: Boolean,
        source: String,
    ) = HotelRate().apply {
        this.country = country
        this.ratePerNight = Money.of("150.00")
        this.validFrom = validFrom
        this.validTo = validTo
        this.region = region
        this.isActive = active
        this.source = source
    }

    private fun persist(rate: HotelRate): Long =
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
            em.createQuery("DELETE FROM HotelRate e WHERE e.source LIKE 'ZZ-AP-COV-4%'")
                .executeUpdate()
        }
    }

    @Test
    fun `findByCountryAndDate returns the active match and null outside the validity window`() {
        persist(
            newRate(
                Country.PT, validFrom = LocalDate.of(2026, 1, 1), validTo = null,
                region = null, active = true, source = "ZZ-AP-COV-4-hr",
            ),
        )

        val found = repository.findByCountryAndDate(Country.PT, LocalDate.of(2026, 6, 1))
        assertNotNull(found)
        assertEquals("ZZ-AP-COV-4-hr", found!!.source)

        // Before this fixture's only PT row's validFrom: no active row can possibly match,
        // exercising the "no result" branch of the inline firstOrNull().
        assertNull(repository.findByCountryAndDate(Country.PT, LocalDate.of(2000, 1, 1)))
    }

    @Test
    fun `findAll returns fixture rows unfiltered, non-blank filter text hits the country type bug`() {
        val id = persist(
            newRate(
                Country.PT, validFrom = LocalDate.of(2026, 2, 1), validTo = null,
                region = "Lissabon", active = true, source = "ZZ-AP-COV-4-hr-findAll",
            ),
        )

        val unfiltered = repository.findAll(filterText = null)
        assertTrue(unfiltered.size >= 6, "the five seeded rows plus this fixture's own rows")
        assertTrue(unfiltered.any { it.id == id })

        // Regression guard: this used to throw for any non-blank filterText, because
        // lower(h.country) was applied to a Country converted to Integer. The filter now resolves
        // the text to Country values and compares with IN.
        val matches = repository.findAll(0, 50, "pt")
        assertTrue(matches.any { it.id == id }, "the PT row must match the text 'pt'")
        assertTrue(matches.all { it.country == Country.PT }, "only PT rows may match")
        assertTrue(repository.count("pt") >= 1)
        assertTrue(repository.findAll(0, 50, "zzz").isEmpty(), "text matching no country yields no rows")
        assertEquals(0L, repository.count("zzz"))
    }

    @Test
    fun `countInactive limits to inactive rows and honours a country filter`() {
        val before = repository.countInactive(null)
        persist(
            newRate(
                Country.PT, validFrom = LocalDate.of(2026, 3, 1), validTo = null,
                region = null, active = false, source = "ZZ-AP-COV-4-hr-inactive",
            ),
        )

        assertEquals(before + 1, repository.countInactive(null))
        assertTrue(repository.countInactive("pt") >= 1)
        assertEquals(0L, repository.countInactive("zzz"))
    }

    @Test
    fun `save inserts a new rate then updates it via merge, delete removes it`() {
        val rate = newRate(
            Country.PT, validFrom = LocalDate.of(2026, 4, 1), validTo = null,
            region = null, active = false, source = "ZZ-AP-COV-4-hr-save-v1",
        )

        val saved = QuarkusTransaction.requiringNew().call<HotelRate> { repository.save(rate) }
        assertNotNull(saved.id)

        saved.source = "ZZ-AP-COV-4-hr-save-v2"
        QuarkusTransaction.requiringNew().run { repository.save(saved) }

        val reloaded = repository.findById(saved.id!!)
        assertEquals("ZZ-AP-COV-4-hr-save-v2", reloaded?.source)

        QuarkusTransaction.requiringNew().run { repository.delete(saved.id!!) }
        em.clear()
        assertNull(repository.findById(saved.id!!))

        // Deleting an id that is already gone must be a no-op, not an error — exercises the
        // `em.find(...)?.let { ... }` null-safety branch's "not found" side.
        QuarkusTransaction.requiringNew().run { repository.delete(saved.id!!) }
    }
}
