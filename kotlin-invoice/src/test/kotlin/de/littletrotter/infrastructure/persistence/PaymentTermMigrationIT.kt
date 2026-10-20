package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * R9 — verifies the V906 migration end to end against a real Postgres (Testcontainers, full
 * Flyway chain via [PostgresTestResource]/`quarkus.flyway.migrate-at-start`): exactly four
 * active standard terms exist with an English name and the corrected "Zahlbar sofort" = 0 days.
 *
 * The four pre-existing V901 legacy rows themselves no longer ship in the base seed — a later
 * migration (V908) removes them, so datagen/tests that still need one create it themselves
 * instead of relying on the base seed carrying it (see V908's header comment). The
 * "deactivated, not deleted" shape they must still have when *something* does create them is
 * covered below with a self-inserted fixture.
 *
 * `payment_term.name`/`english_name` no longer exist as plain columns; every query here
 * joins through `localized_text_group`/`localized_text` instead ([GERMAN_NAME_JOIN]), the same
 * resolution [de.littletrotter.domain.masterdata.PaymentTerm.nameFor] does at the entity level.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PaymentTermMigrationIT {

    @Inject
    lateinit var em: EntityManager

    private fun rowCount(sql: String): Long = (em.createNativeQuery(sql).singleResult as Number).toLong()

    companion object {
        /** joins `payment_term p` to its fallback-language (German, `language_id = 1`)
         *  name text; every query below that used to filter/select on the plain `name` column now
         *  goes through this join instead. */
        private const val GERMAN_NAME_JOIN =
            "JOIN localized_text glt ON glt.group_id = p.name_group_id AND glt.language_id = 1"
    }

    /**
     * The four standard terms created by V906 — matched by name, not counted globally.
     *
     * A global `COUNT(*) WHERE is_active = TRUE` over the **whole** table would depend on
     * execution order, since other test classes also create payment terms elsewhere in the
     * suite. Matching by name stays exactly as sharp: a missing, renamed, or extra seed row
     * still gets caught.
     */
    private val v906Namen = listOf(
        "Zahlbar sofort", "14 Tage netto", "30 Tage netto", "30 Tage netto, 2 % Skonto in 7 Tagen",
    )

    private fun inV906Namen(): String = v906Namen.joinToString(", ") { "'${it.replace("'", "''")}'" }

    @Test
    @Transactional
    fun `exactly four active standard payment terms exist`() {
        assertEquals(
            4,
            rowCount("SELECT COUNT(*) FROM payment_term p $GERMAN_NAME_JOIN WHERE p.is_active = TRUE AND glt.value IN (${inV906Namen()})"),
            "die vier Standardsätze aus V906 müssen aktiv sein",
        )
    }

    @Test
    @Transactional
    fun `every active payment term has an english_name`() {
        // Queried by the **German** name and the English one checked — not the other way
        // around: otherwise the assertion would be circular and could never catch an English
        // name V906 forgot to set.
        val missing = rowCount(
            "SELECT COUNT(*) FROM payment_term p $GERMAN_NAME_JOIN WHERE p.is_active = TRUE AND glt.value IN (${inV906Namen()}) " +
                "AND NOT EXISTS (SELECT 1 FROM localized_text elt WHERE elt.group_id = p.name_group_id " +
                "AND elt.language_id = 2 AND length(trim(elt.value)) > 0)",
        )
        assertEquals(0, missing, "jeder Standardsatz aus V906 muss einen englischen Namen tragen")
    }

    @Test
    @Transactional
    fun `Zahlbar sofort is modeled as zero days among the active rows`() {
        val dueInDays = (em.createNativeQuery(
            "SELECT p.due_in_days FROM payment_term p $GERMAN_NAME_JOIN " +
                "WHERE p.is_active = TRUE AND glt.value = 'Zahlbar sofort'"
        ).singleResult as Number).toInt()
        assertEquals(0, dueInDays)
    }

    // The four V901 legacy rows this test used to find already-seeded, verbatim (name,
    // description, due_in_days, discount_percent, discount_days) — see V901's own header and
    // V908, which removes them from the base seed.
    private val legacyRows = listOf(
        Quadruple("Sofort fällig (14 Tage netto)", "Zahlbar innerhalb von 14 Tagen ohne Abzug.", 14, null),
        Quadruple("30 Tage netto", "Zahlbar innerhalb von 30 Tagen ohne Abzug.", 30, null),
        Quadruple("30 Tage netto, 2% Skonto in 7 Tagen", "Zahlbar innerhalb von 30 Tagen, 2% Skonto bei Zahlung innerhalb von 7 Tagen.", 30, 7),
        Quadruple("14 Tage netto, 2% Skonto in 7 Tagen", "Zahlbar innerhalb von 14 Tagen, 2% Skonto bei Zahlung innerhalb von 7 Tagen.", 14, 7),
    )

    private data class Quadruple(val name: String, val description: String, val dueInDays: Int, val discountDays: Int?)

    @Test
    fun `a self-inserted legacy row keeps the deactivated, not-default shape V901 always gave it`() {
        // Base-seed reality after V908: none of these rows exist unless something (datagen, or
        // this test) creates them. Inserted/removed via a fresh transaction, independent of the
        // (non-rollback) @Transactional used elsewhere in this class, so no fixture rows leak
        // into other tests in this suite run.
        //
        // each insert is its own localized_text_group (purpose=1=PAYMENT_TERM_NAME) plus a
        // German localized_text row, chained in one native-SQL statement (same shape
        // V2__baseline_seed.sql now uses); RETURNING the group id so verify/cleanup below never
        // needs to look a row up by name again.
        val insertedGroupIds = QuarkusTransaction.requiringNew().call<List<Long>> {
            legacyRows.map { row ->
                (em.createNativeQuery(
                    """WITH grp AS (
                           INSERT INTO localized_text_group (purpose) VALUES (1) RETURNING id
                       ), txt AS (
                           INSERT INTO localized_text (group_id, language_id, value) SELECT id, 1, :name FROM grp
                       )
                       INSERT INTO payment_term
                           (name_group_id, description, due_in_days, discount_percent, discount_days,
                            is_default, is_active, created_at, version)
                       SELECT id, :description, :dueInDays, :discountPercent, :discountDays, FALSE, FALSE, now(), 0 FROM grp
                       RETURNING name_group_id"""
                )
                    .setParameter("name", row.name)
                    .setParameter("description", row.description)
                    .setParameter("dueInDays", row.dueInDays)
                    .setParameter("discountPercent", if (row.discountDays == null) java.math.BigDecimal("0.00") else java.math.BigDecimal("2.00"))
                    .setParameter("discountDays", row.discountDays)
                    .singleResult as Number).toLong()
            }
        }
        try {
            QuarkusTransaction.requiringNew().run {
                for (groupId in insertedGroupIds) {
                    val isDefault = em.createNativeQuery(
                        "SELECT is_default FROM payment_term WHERE name_group_id = :gid"
                    ).setParameter("gid", groupId).singleResult as Boolean
                    assertFalse(isDefault, "legacy row (name_group_id=$groupId) must not be the default")
                }
            }
        } finally {
            // payment_term first (it holds the FK to the group), then the now-orphaned group —
            // ON DELETE CASCADE takes its localized_text row with it.
            QuarkusTransaction.requiringNew().run {
                for (groupId in insertedGroupIds) {
                    em.createNativeQuery("DELETE FROM payment_term WHERE name_group_id = :gid").setParameter("gid", groupId).executeUpdate()
                    em.createNativeQuery("DELETE FROM localized_text_group WHERE id = :gid").setParameter("gid", groupId).executeUpdate()
                }
            }
        }
    }

    @Test
    @Transactional
    fun `exactly one active row is the default`() {
        assertEquals(1, rowCount("SELECT COUNT(*) FROM payment_term WHERE is_active = TRUE AND is_default = TRUE"))
        val defaultName = em.createNativeQuery(
            "SELECT glt.value FROM payment_term p $GERMAN_NAME_JOIN WHERE p.is_active = TRUE AND p.is_default = TRUE"
        ).singleResult as String
        assertEquals("14 Tage netto", defaultName)
    }

    @Test
    @Transactional
    fun `payment_term still enforces the V001 discount pairing constraint`() {
        // Defense-in-depth check: V906 only adds columns, it must not have loosened the
        // pre-existing chk_payment_term_discount_combination constraint from V001. name_group_id
        // borrows an existing active row's group ( NOT NULL FK, but this INSERT is expected
        // to fail on the CHECK constraint before ever committing, so the reused id is never
        // actually shared by two live rows).
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.persistence.PersistenceException::class.java) {
            em.createNativeQuery(
                """INSERT INTO payment_term (name_group_id, due_in_days, discount_percent, discount_days, created_at, version)
                   VALUES ((SELECT name_group_id FROM payment_term WHERE is_active = TRUE LIMIT 1), 30, 2.00, NULL, now(), 0)"""
            ).executeUpdate()
            em.flush()
        }
    }
}
