package de.littletrotter.infrastructure.persistence

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * (M3, beratung-manipulationsschutz.md) — the invoice freeze trigger tested directly
 * against real PostgreSQL, without the Quarkus stack (same shape as [NumberJournalIT]). Mirrors
 * `V1__baseline_schema.sql`'s `guard_invoice_freeze()`/`trg_invoice_freeze_issued` exactly,
 * against a minimal standalone `invoice` table carrying only the columns the trigger itself
 * touches (`id`/`status`, the frozen columns, and `validation_status` as the "stays writable"
 * control) — the real 60+ column table with all its FKs/CHECKs is not needed to exercise the
 * trigger in isolation.
 */
@Testcontainers
class InvoiceFreezeTriggerIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18")
    }

    private lateinit var setup: Connection

    @BeforeEach
    fun createSchema() {
        setup = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        setup.autoCommit = true
        setup.createStatement().execute("DROP TABLE IF EXISTS invoice")
        setup.createStatement().execute(
            """
            CREATE TABLE invoice (
                id                                      BIGSERIAL     PRIMARY KEY,
                status                                   INTEGER       NOT NULL DEFAULT 0,
                invoice_number                          VARCHAR(255)  NOT NULL DEFAULT '',
                invoice_number_prefix                   VARCHAR(30),
                invoice_number_sequence                 BIGINT,
                invoice_date                            DATE          NOT NULL DEFAULT CURRENT_DATE,
                type_code                                INTEGER       NOT NULL DEFAULT 0,
                net_amount                               NUMERIC(14,2) NOT NULL DEFAULT 0.00,
                vat_amount                               NUMERIC(14,2) NOT NULL DEFAULT 0.00,
                gross_amount                             NUMERIC(14,2) NOT NULL DEFAULT 0.00,
                payment_term_snapshot_name              VARCHAR(150),
                payment_term_snapshot_due_in_days       INT,
                payment_term_snapshot_discount_percent  NUMERIC(5, 2),
                payment_term_snapshot_discount_days     INT,
                sender_profile_id                        BIGINT,
                recipient_id                             BIGINT,
                validation_status                        INTEGER       NOT NULL DEFAULT 4
            )
            """,
        )
        // mirrors V1__baseline_schema.sql's guard_invoice_freeze()/trg_invoice_freeze_issued.
        setup.createStatement().execute(
            """
            CREATE OR REPLACE FUNCTION guard_invoice_freeze() RETURNS TRIGGER AS $$
            BEGIN
                IF OLD.status IN (2, 4) THEN
                    IF (OLD.invoice_number <> '' AND NEW.invoice_number IS DISTINCT FROM OLD.invoice_number)
                        OR (OLD.invoice_number_prefix IS NOT NULL AND NEW.invoice_number_prefix IS DISTINCT FROM OLD.invoice_number_prefix)
                        OR (OLD.invoice_number_sequence IS NOT NULL AND NEW.invoice_number_sequence IS DISTINCT FROM OLD.invoice_number_sequence)
                        OR NEW.invoice_date IS DISTINCT FROM OLD.invoice_date
                        OR NEW.type_code IS DISTINCT FROM OLD.type_code
                        OR NEW.net_amount IS DISTINCT FROM OLD.net_amount
                        OR NEW.vat_amount IS DISTINCT FROM OLD.vat_amount
                        OR NEW.gross_amount IS DISTINCT FROM OLD.gross_amount
                        OR NEW.payment_term_snapshot_name IS DISTINCT FROM OLD.payment_term_snapshot_name
                        OR NEW.payment_term_snapshot_due_in_days IS DISTINCT FROM OLD.payment_term_snapshot_due_in_days
                        OR NEW.payment_term_snapshot_discount_percent IS DISTINCT FROM OLD.payment_term_snapshot_discount_percent
                        OR NEW.payment_term_snapshot_discount_days IS DISTINCT FROM OLD.payment_term_snapshot_discount_days
                        OR NEW.sender_profile_id IS DISTINCT FROM OLD.sender_profile_id
                        OR NEW.recipient_id IS DISTINCT FROM OLD.recipient_id
                    THEN
                        RAISE EXCEPTION 'invoice % is issued (status %) and its frozen columns cannot be changed', OLD.id, OLD.status
                            USING ERRCODE = '23001';
                    END IF;
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """,
        )
        setup.createStatement().execute(
            """
            CREATE TRIGGER trg_invoice_freeze_issued
                BEFORE UPDATE ON invoice
                FOR EACH ROW
                EXECUTE FUNCTION guard_invoice_freeze()
            """,
        )
    }

    @AfterEach
    fun close() = setup.close()

    private fun newConnection(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).apply { autoCommit = true }

    private fun insertInvoice(conn: Connection, status: Int, grossAmount: BigDecimal): Long {
        val stmt = conn.prepareStatement("INSERT INTO invoice (status, gross_amount) VALUES (?, ?) RETURNING id")
        stmt.setInt(1, status)
        stmt.setBigDecimal(2, grossAmount)
        val rs = stmt.executeQuery()
        rs.next()
        return rs.getLong(1)
    }

    @Test
    fun `RS-11 - direct UPDATE of a frozen column on an ISSUED invoice fails`() {
        newConnection().use { conn ->
            val id = insertInvoice(conn, status = 2, grossAmount = BigDecimal("100.00")) // ISSUED
            val ex = assertThrows(SQLException::class.java) {
                conn.createStatement().executeUpdate("UPDATE invoice SET gross_amount = 999.99 WHERE id = $id")
            }
            assertEquals(true, ex.message?.contains("frozen") == true, "expected the guard's own message, got: ${ex.message}")

            val gross = conn.createStatement().executeQuery("SELECT gross_amount FROM invoice WHERE id = $id")
                .let { it.next(); it.getBigDecimal(1) }
            assertEquals(0, BigDecimal("100.00").compareTo(gross), "the rejected UPDATE must not have changed the row")
        }
    }

    @Test
    fun `RS-11 - direct UPDATE of a frozen column on a CANCELLED invoice fails`() {
        newConnection().use { conn ->
            val id = insertInvoice(conn, status = 4, grossAmount = BigDecimal("100.00")) // CANCELLED
            assertThrows(SQLException::class.java) {
                conn.createStatement().executeUpdate("UPDATE invoice SET net_amount = 42.00 WHERE id = $id")
            }
        }
    }

    @Test
    fun `RS-11 - the same UPDATE on a DRAFT invoice succeeds`() {
        newConnection().use { conn ->
            val id = insertInvoice(conn, status = 0, grossAmount = BigDecimal("100.00")) // DRAFT
            conn.createStatement().executeUpdate("UPDATE invoice SET gross_amount = 999.99 WHERE id = $id")

            val gross = conn.createStatement().executeQuery("SELECT gross_amount FROM invoice WHERE id = $id")
                .let { it.next(); it.getBigDecimal(1) }
            assertEquals(0, BigDecimal("999.99").compareTo(gross))
        }
    }

    @Test
    fun `RS-11 - status transition ISSUED to CANCELLED on the same row succeeds`() {
        // Mirrors InvoiceCancellationService.createCreditNote: only `status` changes on the original —
        // "keeps everything else it had" (that class's own KDoc) — so this must never trip the
        // freeze guard even though OLD.status is already ISSUED (frozen-eligible) when it fires.
        newConnection().use { conn ->
            val id = insertInvoice(conn, status = 2, grossAmount = BigDecimal("100.00")) // ISSUED
            conn.createStatement().executeUpdate("UPDATE invoice SET status = 4 WHERE id = $id")

            val status = conn.createStatement().executeQuery("SELECT status FROM invoice WHERE id = $id")
                .let { it.next(); it.getInt(1) }
            assertEquals(4, status)
        }
    }

    @Test
    fun `RS-11 - assigning a number for the first time on an already-issued row succeeds`() {
        // InvoiceLifecycleService's ISSUED transition and InvoiceCancellationService.createCreditNote
        // both persist status=ISSUED/CANCELLED in one flush and the drawn invoice_number in a
        // LATER, separate flush (numberAssigner.assignIfNeeded's own native counter query forces
        // an intermediate auto-flush — see both classes' KDoc). This is the established,
        // legitimate ordering the trigger must not break — found via a live-Postgres test run
        // (2026-09-04) that the naive "any change while issued" version of this guard did.
        newConnection().use { conn ->
            val id = insertInvoice(conn, status = 2, grossAmount = BigDecimal("100.00")) // ISSUED, unnumbered
            conn.createStatement().executeUpdate(
                "UPDATE invoice SET invoice_number = 'RE-2026-00001', invoice_number_prefix = 'RE', " +
                    "invoice_number_sequence = 1 WHERE id = $id",
            )

            val number = conn.createStatement().executeQuery("SELECT invoice_number FROM invoice WHERE id = $id")
                .let { it.next(); it.getString(1) }
            assertEquals("RE-2026-00001", number)
        }
    }

    @Test
    fun `RS-11 - changing an already-assigned number on an issued invoice still fails`() {
        // The narrowing above must not become a loophole: once a real number is on the row, it
        // is frozen exactly as before.
        newConnection().use { conn ->
            val id = insertInvoice(conn, status = 2, grossAmount = BigDecimal("100.00")) // ISSUED
            conn.createStatement().executeUpdate(
                "UPDATE invoice SET invoice_number = 'RE-2026-00001', invoice_number_prefix = 'RE', " +
                    "invoice_number_sequence = 1 WHERE id = $id",
            )

            val ex = assertThrows(SQLException::class.java) {
                conn.createStatement().executeUpdate("UPDATE invoice SET invoice_number = 'RE-2026-99999' WHERE id = $id")
            }
            assertEquals(true, ex.message?.contains("frozen") == true, "expected the guard's own message, got: ${ex.message}")

            val number = conn.createStatement().executeQuery("SELECT invoice_number FROM invoice WHERE id = $id")
                .let { it.next(); it.getString(1) }
            assertEquals("RE-2026-00001", number, "the rejected UPDATE must not have changed the row")
        }
    }

    @Test
    fun `RS-11 - IN-4 - direct UPDATE of the surviving payment_term_snapshot_name column on an ISSUED invoice fails`() {
        // (PO decision 2 of 06.09.2026) — payment_term_snapshot_english_name was dropped
        // from the schema; payment_term_snapshot_name (now the text as printed, resolved in the
        // invoice's own document_language) must still be exactly as frozen as before.
        newConnection().use { conn ->
            val id = insertInvoice(conn, status = 0, grossAmount = BigDecimal("100.00")) // DRAFT
            // The real -> ISSUED transition sets status and freezes the snapshot in the SAME
            // UPDATE (InvoiceLifecycleService.transition / Invoice.freezePaymentTermSnapshot) —
            // OLD.status is still DRAFT (0) here, so the guard does not fire for this first write,
            // exactly like invoice_number's own "first assignment" carve-out above.
            conn.createStatement().executeUpdate(
                "UPDATE invoice SET status = 2, payment_term_snapshot_name = 'Netto 30' WHERE id = $id",
            )

            val ex = assertThrows(SQLException::class.java) {
                conn.createStatement().executeUpdate("UPDATE invoice SET payment_term_snapshot_name = 'Netto 45' WHERE id = $id")
            }
            assertEquals(true, ex.message?.contains("frozen") == true, "expected the guard's own message, got: ${ex.message}")

            val name = conn.createStatement().executeQuery("SELECT payment_term_snapshot_name FROM invoice WHERE id = $id")
                .let { it.next(); it.getString(1) }
            assertEquals("Netto 30", name, "the rejected UPDATE must not have changed the row")
        }
    }

    @Test
    fun `RS-11 - validation_status stays writable on an issued invoice`() {
        // Mirrors PanacheInvoiceRepository.updateValidationStatus's unconditional bulk UPDATE,
        // which legitimately flips this on an ISSUED/CANCELLED invoice every time its (single,
        // -gated) e-invoice/PDF job runs — the trigger must never block it.
        newConnection().use { conn ->
            val id = insertInvoice(conn, status = 2, grossAmount = BigDecimal("100.00")) // ISSUED
            conn.createStatement().executeUpdate("UPDATE invoice SET validation_status = 0 WHERE id = $id")

            val validationStatus = conn.createStatement().executeQuery("SELECT validation_status FROM invoice WHERE id = $id")
                .let { it.next(); it.getInt(1) }
            assertEquals(0, validationStatus)
        }
    }
}
