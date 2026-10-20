package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * belege-improve A4 — verifies the V023 migration end to end against a real Postgres
 * (Testcontainers, full Flyway chain via [PostgresTestResource]/`quarkus.flyway.migrate-at-start`),
 * following the same pattern as the other `*MigrationIT`s.
 *
 * What the assertions protect: `invoice_receipt` is the invoice-level counterpart of
 * `invoice_item_receipt`. Its composite primary key is what keeps a receipt from being attached to
 * the same invoice twice, its foreign keys are what keep a link from outliving either side, and
 * the index on `receipt_id` is what keeps the usage/delete-guard lookup (which starts at the
 * receipt, not at the invoice) from turning into a sequential scan.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceReceiptMigrationIT {

    @Inject
    lateinit var em: EntityManager

    private fun count(sql: String): Long = (em.createNativeQuery(sql).singleResult as Number).toLong()

    @Test
    @Transactional
    fun `invoice_receipt exists with both id columns NOT NULL`() {
        val columns = em.createNativeQuery(
            """SELECT column_name, is_nullable, data_type FROM information_schema.columns
               WHERE table_name = 'invoice_receipt' ORDER BY column_name""",
        ).resultList.map { row ->
            val arr = row as Array<*>
            Triple(arr[0] as String, arr[1] as String, arr[2] as String)
        }

        assertEquals(listOf("invoice_id", "receipt_id"), columns.map { it.first })
        assertTrue(columns.all { it.second == "NO" }, "both columns must be NOT NULL, was: $columns")
        assertTrue(columns.all { it.third == "bigint" }, "both columns must be bigint, was: $columns")
    }

    @Test
    @Transactional
    fun `the primary key spans both columns so one receipt cannot be linked twice`() {
        val pkColumns = em.createNativeQuery(
            """SELECT kcu.column_name
               FROM information_schema.table_constraints tc
               JOIN information_schema.key_column_usage kcu
                 ON kcu.constraint_name = tc.constraint_name
               WHERE tc.table_name = 'invoice_receipt' AND tc.constraint_type = 'PRIMARY KEY'
               ORDER BY kcu.column_name""",
        ).resultList.map { it as String }

        assertEquals(listOf("invoice_id", "receipt_id"), pkColumns)
    }

    @Test
    @Transactional
    fun `foreign keys point at invoice and receipt`() {
        // information_schema.constraint_column_usage filters by
        // `pg_has_role(tblowner, 'USAGE')`: it only ever shows rows to the table's OWNER (or a
        // member of that role), never to a role that merely has SELECT/INSERT/UPDATE/DELETE via
        // GRANT. Since this connection runs as lt_app (DML-only; lt_migrator owns the
        // schema), so that view genuinely returns nothing here — not a missing grant, and not
        // something a GRANT could ever fix, since the view's filter isn't privilege-based at all.
        // pg_constraint is plain catalog metadata with no such owner-only filter, so it stays
        // usable from lt_app. Found via a live-Postgres test run (2026-09-04) — see the
        // Revisionssicherheit program's register (L22) for the report that traced it here.
        val fks = em.createNativeQuery(
            """SELECT DISTINCT confrelid::regclass::text
               FROM pg_constraint
               WHERE conrelid = 'invoice_receipt'::regclass AND contype = 'f'
               ORDER BY 1""",
        ).resultList.map { it as String }

        assertEquals(listOf("invoice", "receipt"), fks)
    }

    @Test
    @Transactional
    fun `receipt_id is indexed for the usage and delete-guard lookup`() {
        val indexed = count(
            """SELECT COUNT(*) FROM pg_indexes
               WHERE tablename = 'invoice_receipt' AND indexdef LIKE '%receipt_id%'""",
        )
        assertTrue(indexed > 0, "V023 must index invoice_receipt.receipt_id")
    }

    @Test
    @Transactional
    fun `invoice_item_receipt is untouched by V023`() {
        // A4 is additive: the position-level links from V4 keep their own table and shape.
        val itemLinkTable = count(
            """SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'invoice_item_receipt'""",
        )
        assertEquals(1, itemLinkTable, "the position-level join table must still exist")
    }
}
