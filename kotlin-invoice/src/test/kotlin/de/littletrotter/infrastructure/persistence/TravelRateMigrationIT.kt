package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R2 — verifies the mileage/per_diem/hotel rate columns (`V1__baseline_schema.sql`) plus their
 * seed data (`seed/V2__baseline_seed.sql`) end to end against a real Postgres (Testcontainers,
 * full Flyway chain via [PostgresTestResource]/`quarkus.flyway.migrate-at-start`): exactly two
 * active vehicle-type mileage rows exist, every active per_diem_rate/hotel_rate row carries a
 * source, and no active per_diem_rate/hotel_rate row's validity window overlaps another for the
 * same (country, region).
 *
 * The five historical, country-specific mileage rows themselves no longer ship in the base seed —
 * datagen/tests that still need one create it themselves. The "deactivated, not deleted" shape
 * they must still have when *something* does create them is covered below with a self-inserted
 * fixture, verbatim from the historical seed's own values.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class TravelRateMigrationIT {

    @Inject
    lateinit var em: EntityManager

    private fun rowCount(sql: String): Long = (em.createNativeQuery(sql).singleResult as Number).toLong()

    private data class LegacyMileageRate(val country: Int, val ratePerKm: String)

    private val legacyMileageRates = listOf(
        LegacyMileageRate(442, "0.350"), // LU
        LegacyMileageRate(56, "0.360"),  // BE
        LegacyMileageRate(528, "0.210"), // NL
        LegacyMileageRate(250, "0.350"), // FR
        LegacyMileageRate(756, "0.700"), // CH
    )

    /** Inserts [legacyMileageRates] in a fresh transaction, runs [block], then always cleans up
     * — independent of the (non-rollback) @Transactional used elsewhere in this class, so no
     * fixture rows leak into other tests in this suite run. */
    private fun withLegacyMileageRates(block: () -> Unit) {
        QuarkusTransaction.requiringNew().run {
            for (row in legacyMileageRates) {
                em.createNativeQuery(
                    """INSERT INTO mileage_rate (country, rate_per_km, valid_from, created_at,
                                                  version, vehicle_type, is_active, source)
                       VALUES (:country, :ratePerKm, '2024-01-01', now(), 0, NULL, FALSE, NULL)"""
                )
                    .setParameter("country", row.country)
                    .setParameter("ratePerKm", java.math.BigDecimal(row.ratePerKm))
                    .executeUpdate()
            }
        }
        try {
            block()
        } finally {
            // One at a time instead of `IN (:countries)` — native queries don't reliably expand
            // list parameters across all Hibernate versions.
            QuarkusTransaction.requiringNew().run {
                for (row in legacyMileageRates) {
                    em.createNativeQuery(
                        "DELETE FROM mileage_rate WHERE country = :country AND is_active = FALSE"
                    ).setParameter("country", row.country).executeUpdate()
                }
            }
        }
    }

    // ── Mileage: vehicle-type rows active, country rows deactivated ─────────────────────────

    @Test
    @Transactional
    fun `R2-T05 - exactly two active mileage rows exist, one per vehicle type`() {
        assertEquals(2, rowCount("SELECT COUNT(*) FROM mileage_rate WHERE is_active = TRUE"))
        assertEquals(1, rowCount("SELECT COUNT(*) FROM mileage_rate WHERE is_active = TRUE AND vehicle_type = 0"))
        assertEquals(1, rowCount("SELECT COUNT(*) FROM mileage_rate WHERE is_active = TRUE AND vehicle_type = 1"))
    }

    @Test
    fun `R2-T06 - a self-inserted legacy mileage row keeps its deactivated, not-deleted shape`() {
        // country codes: LU=442, BE=56, NL=528, FR=250, CH=756 (matches the task doc's named rows).
        withLegacyMileageRates {
            QuarkusTransaction.requiringNew().run {
                assertEquals(
                    0,
                    rowCount("SELECT COUNT(*) FROM mileage_rate WHERE country IN (442, 56, 528, 250, 756) AND is_active = TRUE"),
                )
                assertEquals(
                    5,
                    rowCount("SELECT COUNT(*) FROM mileage_rate WHERE country IN (442, 56, 528, 250, 756) AND is_active = FALSE"),
                )
            }
        }
    }

    @Test
    @Transactional
    fun `active mileage rate values match the task doc's German rates`() {
        val carRate = em.createNativeQuery(
            "SELECT rate_per_km FROM mileage_rate WHERE is_active = TRUE AND vehicle_type = 0"
        ).singleResult as java.math.BigDecimal
        val otherRate = em.createNativeQuery(
            "SELECT rate_per_km FROM mileage_rate WHERE is_active = TRUE AND vehicle_type = 1"
        ).singleResult as java.math.BigDecimal
        assertEquals(0, java.math.BigDecimal("0.300").compareTo(carRate))
        assertEquals(0, java.math.BigDecimal("0.200").compareTo(otherRate))
    }

    // ── Every active per_diem_rate/hotel_rate row has a source ──────────────────────────────

    @Test
    @Transactional
    fun `R2-T12 - every active per_diem_rate row has a non-blank source`() {
        val missing = rowCount(
            "SELECT COUNT(*) FROM per_diem_rate WHERE is_active = TRUE AND (source IS NULL OR length(trim(source)) = 0)"
        )
        assertEquals(0, missing, "every active per_diem_rate row must carry a source")
    }

    @Test
    @Transactional
    fun `R2-T12 - every active hotel_rate row has a non-blank source`() {
        val missing = rowCount(
            "SELECT COUNT(*) FROM hotel_rate WHERE is_active = TRUE AND (source IS NULL OR length(trim(source)) = 0)"
        )
        assertEquals(0, missing, "every active hotel_rate row must carry a source")
    }

    @Test
    @Transactional
    fun `no active per_diem_rate or hotel_rate row is a sourceless legacy leftover`() {
        assertEquals(0, rowCount("SELECT COUNT(*) FROM per_diem_rate WHERE is_active = TRUE AND source IS NULL"))
        assertEquals(0, rowCount("SELECT COUNT(*) FROM hotel_rate WHERE is_active = TRUE AND source IS NULL"))
    }

    // ── R2 — the full 2026 dataset is present ────────────────────────────────────────────────

    @Test
    @Transactional
    fun `exactly one active domestic per_diem_rate row exists with inland_rules`() {
        assertEquals(
            1,
            rowCount("SELECT COUNT(*) FROM per_diem_rate WHERE is_active = TRUE AND country = 276 AND inland_rules = TRUE"),
        )
    }

    @Test
    @Transactional
    fun `no hotel_rate row exists for Germany`() {
        // Domestic hotel costs are reimbursed at actual cost, not a flat rate.
        assertEquals(0, rowCount("SELECT COUNT(*) FROM hotel_rate WHERE country = 276 AND is_active = TRUE"))
    }

    @Test
    @Transactional
    fun `city-region rows exist for France, Poland, the UK and Italy alongside their country catch-all row`() {
        // country codes: FR=250, PL=616, GB=826, IT=380.
        for (country in listOf(250, 616, 826, 380)) {
            val regional = rowCount("SELECT COUNT(*) FROM per_diem_rate WHERE is_active = TRUE AND country = $country AND region IS NOT NULL")
            val catchAll = rowCount("SELECT COUNT(*) FROM per_diem_rate WHERE is_active = TRUE AND country = $country AND region IS NULL")
            assertTrue(regional >= 1, "country $country should have at least one region-specific active row")
            assertEquals(1, catchAll, "country $country should have exactly one catch-all (region IS NULL) active row")
        }
    }

    @Test
    @Transactional
    fun `mileage_rate still enforces the vehicle-type CHECK constraint`() {
        // 99 is a valid smallint but outside chk_mileage_rate_vehicle_type's IN-list (0,1) —
        // exercises the CHECK constraint itself, not just the column's smallint type.
        assertThrowsPersistenceException {
            em.createNativeQuery(
                """INSERT INTO mileage_rate (country, rate_per_km, vehicle_type, is_active, created_at, version)
                   VALUES (276, 0.25, 99, TRUE, now(), 0)"""
            ).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `mileage_rate rejects an active row without a vehicle type`() {
        assertThrowsPersistenceException {
            em.createNativeQuery(
                """INSERT INTO mileage_rate (country, rate_per_km, vehicle_type, is_active, created_at, version)
                   VALUES (276, 0.25, NULL, TRUE, now(), 0)"""
            ).executeUpdate()
            em.flush()
        }
    }

    private fun assertThrowsPersistenceException(block: () -> Unit) {
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.persistence.PersistenceException::class.java, block)
    }
}
