package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import io.quarkus.narayana.jta.QuarkusTransaction
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
import java.math.BigDecimal

/**
 * R1 — verifies the V017/V904 migration end to end against a real Postgres (Testcontainers,
 * full Flyway chain via [PostgresTestResource]/`quarkus.flyway.migrate-at-start`): that the
 * fresh-install ordering actually works (this class running at all, with V017
 * before V901 before V904 in Flyway's numeric order, is the proof), that the CHECK constraints
 * reject the mis-shaped rows R1 is meant to make impossible, and that the six MVP
 * rules look exactly as specified.
 *
 * The eight stilled LEGACY_* rows V901 originally backfilled no longer ship in
 * the base seed — V908 removes them, so datagen/tests that still need one create it themselves
 * (see V908's header comment). The shape those rows must still have when *something* does
 * create them (is_selectable = FALSE, DE's ambiguous 0 % row never guessed as AE) is covered
 * below with a self-inserted fixture, verbatim from V901's own values.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class TaxRuleMigrationIT {

    @Inject
    lateinit var em: EntityManager

    private fun rowCount(sql: String): Long =
        (em.createNativeQuery(sql).singleResult as Number).toLong()

    // Verbatim from V901's tax_rate LEGACY_* block — see V908, which removes them from the base
    // seed.
    private data class LegacyTaxRate(val name: String, val rate: String, val country: Int, val code: String)

    private val legacyTaxRates = listOf(
        LegacyTaxRate("USt 0%", "0.00", 276, "LEGACY_DE_ZERO_AMBIGUOUS"),
        LegacyTaxRate("USt LU 17%", "17.00", 442, "LEGACY_LU_17"),
        LegacyTaxRate("USt LU 8%", "8.00", 442, "LEGACY_LU_8"),
        LegacyTaxRate("USt FR 20%", "20.00", 250, "LEGACY_FR_20"),
        LegacyTaxRate("USt FR 10%", "10.00", 250, "LEGACY_FR_10"),
        LegacyTaxRate("USt BE 21%", "21.00", 56, "LEGACY_BE_21"),
        LegacyTaxRate("USt NL 21%", "21.00", 528, "LEGACY_NL_21"),
        LegacyTaxRate("USt GB 20%", "20.00", 826, "LEGACY_GB_20"),
    )

    /** Inserts [legacyTaxRates] in a fresh transaction, runs [block], then always cleans up —
     * independent of the (non-rollback) @Transactional used elsewhere in this class, so no
     * fixture rows leak into other tests in this suite run. */
    private fun withLegacyTaxRates(block: () -> Unit) {
        QuarkusTransaction.requiringNew().run {
            for (row in legacyTaxRates) {
                em.createNativeQuery(
                    """INSERT INTO tax_rate (name, rate, country, valid_from, is_default,
                                              is_hotel_rate, created_at, version, code,
                                              is_active, is_selectable)
                       VALUES (:name, :rate, :country, '2024-01-01', FALSE, FALSE, now(), 0,
                               :code, TRUE, FALSE)"""
                )
                    .setParameter("name", row.name)
                    .setParameter("rate", BigDecimal(row.rate))
                    .setParameter("country", row.country)
                    .setParameter("code", row.code)
                    .executeUpdate()
            }
        }
        try {
            block()
        } finally {
            QuarkusTransaction.requiringNew().run {
                // One at a time instead of `IN (:codes)` — native queries don't reliably expand
                // list parameters across all Hibernate versions.
                for (row in legacyTaxRates) {
                    em.createNativeQuery("DELETE FROM tax_rate WHERE code = :code")
                        .setParameter("code", row.code)
                        .executeUpdate()
                }
            }
        }
    }

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
    fun `exactly the six MVP rules plus V2's and task11-V8's FR-BE-NL-LU reference rates are selectable`() {
        // V2 (V907) adds seven selectable foreign-country reference rates alongside R1's
        // original six MVP rules; V8 (V909) adds six more — the FR/BE/NL reduced rates
        // needed for the automatic seller-VAT-registration derivation's "craftsman scenario"
        // (V8 §3). LU's reduced tiers already existed since
        // V2. This list grows again once a future task needs further categories or another
        // country goes live.
        val codes = em.createNativeQuery("SELECT code FROM tax_rate WHERE is_selectable = TRUE ORDER BY code")
            .resultList.map { it as String }
        assertEquals(
            listOf(
                "BE_REDUCED_12", "BE_REDUCED_6", "BE_STANDARD_21",
                "DE_EXEMPT_SERVICE", "DE_REDUCED_7", "DE_STANDARD_19",
                "EU_B2B_RC_SERVICE", "EU_B2C_DE_STANDARD_19",
                "FR_REDUCED_10", "FR_REDUCED_2_1", "FR_REDUCED_5_5", "FR_STANDARD_20",
                "LU_INTERMEDIATE_14", "LU_REDUCED_8", "LU_STANDARD_17", "LU_SUPER_REDUCED_3",
                "NL_REDUCED_9", "NL_STANDARD_21",
                "OUTSIDE_SCOPE_SERVICE",
            ),
            codes,
        )
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
    fun `foreign local rates and the ambiguous legacy zero rate exist but are not selectable`() {
        withLegacyTaxRates {
            QuarkusTransaction.requiringNew().run {
                val legacyCount = rowCount(
                    "SELECT COUNT(*) FROM tax_rate WHERE code LIKE 'LEGACY_%' AND is_selectable = TRUE"
                )
                assertEquals(0, legacyCount, "no LEGACY_* row should be selectable")

                val foreignCount = rowCount(
                    "SELECT COUNT(*) FROM tax_rate WHERE country IN (442, 250, 56, 528, 826) AND is_selectable = FALSE"
                )
                assertEquals(7, foreignCount, "LU(2)/FR(2)/BE(1)/NL(1)/GB(1) = 7 foreign rows, all stilled")

                val ambiguousZero = em.createNativeQuery(
                    "SELECT code, category FROM tax_rate WHERE country = 276 AND rate = 0.00 AND code LIKE 'LEGACY%'"
                ).resultList as List<Array<Any?>>
                assertEquals(1, ambiguousZero.size)
                assertEquals(null, ambiguousZero[0][1], "the ambiguous legacy DE zero row must stay unclassified, not guessed")
            }
        }
    }

    // ── shape of the (now self-inserted) pre-existing V901 rows ────

    @Test
    @Transactional
    fun `the pre-existing 19pct DE row was backfilled to DE_STANDARD_19`() {
        // country/rate alone aren't selective: EU_B2C_DE_STANDARD_19 (a new V904 row) also
        // has country=276/rate=19.00 by design (same rate, deliberately distinct code/use
        // case) — so this asserts on V901's actual seed name instead.
        val row = em.createNativeQuery(
            "SELECT code, category FROM tax_rate WHERE name = 'USt 19%'"
        ).singleResult as Array<Any?>
        assertEquals("DE_STANDARD_19", row[0])
        assertEquals("S", row[1])
    }

    @Test
    fun `the pre-existing 0pct DE row is a legacy code, never AE`() {
        withLegacyTaxRates {
            QuarkusTransaction.requiringNew().run {
                val row = em.createNativeQuery(
                    "SELECT code, category FROM tax_rate WHERE code = 'LEGACY_DE_ZERO_AMBIGUOUS'"
                ).singleResult as Array<Any?>
                assertEquals("LEGACY_DE_ZERO_AMBIGUOUS", row[0])
                assertFalse(row[1] == "AE", "a pre-existing ambiguous 0% row must never be retroactively classified as Reverse Charge")
            }
        }
    }
}
