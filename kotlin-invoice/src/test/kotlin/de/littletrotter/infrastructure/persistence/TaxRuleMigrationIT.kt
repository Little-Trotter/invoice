package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * R1 — verifies the V017/V904 migration end to end against a real Postgres (Testcontainers,
 * full Flyway chain via [PostgresTestResource]/`quarkus.flyway.migrate-at-start`): that the
 * fresh-install ordering actually works (this class running at all, with V017
 * before V901 before V904 in Flyway's numeric order, is the proof), that the CHECK constraints
 * reject the mis-shaped rows R1 is meant to make impossible, and that the six MVP
 * rules look exactly as specified.
 *
 * Every rule carries a category (tax_rate.category is NOT NULL).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class TaxRuleMigrationIT {

    @Inject
    lateinit var em: EntityManager

    // ── CHECK constraints reject mis-shaped rows ──────────────────

    @Test
    @Transactional
    fun `AE category with a non-zero rate is rejected`() {
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                """INSERT INTO tax_rate (name, rate, country, code, category, exemption_reason_code, created_at, version)
                   VALUES ('bad AE', 19.00, 276, 'BAD_AE_RATE', 'AE', 'VATEX-EU-AE', now(), 0)"""
            ).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `AE category without VATEX-EU-AE is rejected`() {
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                """INSERT INTO tax_rate (name, rate, country, code, category, created_at, version)
                   VALUES ('bad AE reason', 0.00, 276, 'BAD_AE_REASON', 'AE', now(), 0)"""
            ).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `E category without exemption reason text is rejected`() {
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                """INSERT INTO tax_rate (name, rate, country, code, category, created_at, version)
                   VALUES ('bad E', 0.00, 276, 'BAD_E_NO_TEXT', 'E', now(), 0)"""
            ).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `a second row with an already-used code is rejected`() {
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                """INSERT INTO tax_rate (name, rate, country, code, category, created_at, version)
                   VALUES ('duplicate code', 19.00, 276, 'DE_STANDARD_19', 'S', now(), 0)"""
            ).executeUpdate()
            em.flush()
        }
    }

    // ── seed content ────────────────────────────────────────────────

    @Test
    @Transactional
    fun `the six MVP rules plus V2's and task11-V8's FR-BE-NL-LU reference rates are selectable`() {
        // V2 (V907) adds seven selectable foreign-country reference rates alongside R1's
        // original six MVP rules; V8 (V909) adds six more — the FR/BE/NL reduced rates
        // needed for the automatic seller-VAT-registration derivation's "craftsman scenario"
        // (V8 §3). LU's reduced tiers already existed since
        // V2. This list grows again once a future task needs further categories or another
        // country goes live.
        val codes = em.createNativeQuery("SELECT code FROM tax_rate WHERE is_selectable = TRUE ORDER BY code")
            .resultList.map { it as String }
        // Other ITs in the shared database commit their own selectable fixture rules, so the
        // assertion covers the seeded set only; the code column is unique, so presence suffices.
        val seeded =
            listOf(
                "BE_REDUCED_12", "BE_REDUCED_6", "BE_STANDARD_21",
                "DE_EXEMPT_SERVICE", "DE_REDUCED_7", "DE_STANDARD_19",
                "EU_B2B_RC_SERVICE", "EU_B2C_DE_STANDARD_19",
                "FR_REDUCED_10", "FR_REDUCED_2_1", "FR_REDUCED_5_5", "FR_STANDARD_20",
                "LU_INTERMEDIATE_14", "LU_REDUCED_8", "LU_STANDARD_17", "LU_SUPER_REDUCED_3",
                "NL_REDUCED_9", "NL_STANDARD_21",
                "OUTSIDE_SCOPE_SERVICE",
            )
        assertEquals(emptyList<String>(), seeded - codes.toSet(), "every seeded rule must be selectable")
    }

    // ── V2: FR/BE/NL/LU reference rates ─────

    @Test
    @Transactional
    fun `V2 rates carry the expected country, rate and category, and none is the global default`() {
        val rows = em.createNativeQuery(
            """SELECT code, country, rate, category, is_default FROM tax_rate
               WHERE code IN ('FR_STANDARD_20', 'BE_STANDARD_21', 'NL_STANDARD_21',
                               'LU_STANDARD_17', 'LU_INTERMEDIATE_14', 'LU_REDUCED_8', 'LU_SUPER_REDUCED_3')
               ORDER BY code"""
        ).resultList.map { it as Array<Any?> }
        assertEquals(7, rows.size, "all seven V2 reference rates must exist exactly once")

        val byCode = rows.associate { (it[0] as String) to it }
        val expected = mapOf(
            "BE_STANDARD_21" to Pair(56, "21.00"),
            "FR_STANDARD_20" to Pair(250, "20.00"),
            "LU_INTERMEDIATE_14" to Pair(442, "14.00"),
            "LU_REDUCED_8" to Pair(442, "8.00"),
            "LU_STANDARD_17" to Pair(442, "17.00"),
            "LU_SUPER_REDUCED_3" to Pair(442, "3.00"),
            "NL_STANDARD_21" to Pair(528, "21.00"),
        )
        expected.forEach { (code, countryAndRate) ->
            val row = byCode.getValue(code)
            assertEquals(countryAndRate.first, row[1], "$code country")
            assertEquals(0, (row[2] as java.math.BigDecimal).compareTo(java.math.BigDecimal(countryAndRate.second)), "$code rate")
            assertEquals("S", row[3], "$code category")
            assertFalse(row[4] as Boolean, "$code must not be the global default — only DE_STANDARD_19 is")
        }
    }

    // ── V8 (V909): FR/BE/NL reduced rates ─────

    @Test
    @Transactional
    fun `task11-V8 reduced rates carry the expected country, rate and category, and none is the global default`() {
        val rows = em.createNativeQuery(
            """SELECT code, country, rate, category, is_default FROM tax_rate
               WHERE code IN ('FR_REDUCED_10', 'FR_REDUCED_5_5', 'FR_REDUCED_2_1',
                               'BE_REDUCED_12', 'BE_REDUCED_6', 'NL_REDUCED_9')
               ORDER BY code"""
        ).resultList.map { it as Array<Any?> }
        assertEquals(6, rows.size, "all six task11/V8 reduced rates must exist exactly once")

        val byCode = rows.associate { (it[0] as String) to it }
        val expected = mapOf(
            "BE_REDUCED_12" to Pair(56, "12.00"),
            "BE_REDUCED_6" to Pair(56, "6.00"),
            "FR_REDUCED_10" to Pair(250, "10.00"),
            "FR_REDUCED_2_1" to Pair(250, "2.10"),
            "FR_REDUCED_5_5" to Pair(250, "5.50"),
            "NL_REDUCED_9" to Pair(528, "9.00"),
        )
        expected.forEach { (code, countryAndRate) ->
            val row = byCode.getValue(code)
            assertEquals(countryAndRate.first, row[1], "$code country")
            assertEquals(0, (row[2] as java.math.BigDecimal).compareTo(java.math.BigDecimal(countryAndRate.second)), "$code rate")
            assertEquals("S", row[3], "$code category")
            assertFalse(row[4] as Boolean, "$code must not be the global default — only DE_STANDARD_19 is")
        }
    }

    @Test
    @Transactional
    fun `a rule without a category is rejected`() {
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                """INSERT INTO tax_rate (name, rate, country, code, created_at, version)
                   VALUES ('no category', 19.00, 276, 'NO_CATEGORY_RATE', now(), 0)"""
            ).executeUpdate()
            em.flush()
        }
    }
}
