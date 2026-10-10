package de.littletrotter.infrastructure.persistence

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * fix-receipt-editable-after-invoice-issued — `receipt_freeze_when_issued()`/
 * `trg_receipt_freeze_when_issued`, tested against real PostgreSQL (same shape as
 * [InvoiceFreezeTriggerIT]), with a minimal standalone schema.
 */
@Testcontainers
class ReceiptFreezeTriggerIT {

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
        val stmt = setup.createStatement()
        stmt.execute("DROP TABLE IF EXISTS invoice_item_receipt, invoice_receipt, receipt, invoice_item, invoice CASCADE")
        stmt.execute("CREATE TABLE invoice (id BIGSERIAL PRIMARY KEY, status INTEGER NOT NULL)")
        stmt.execute("CREATE TABLE invoice_item (id BIGSERIAL PRIMARY KEY, invoice_id BIGINT NOT NULL REFERENCES invoice(id))")
        stmt.execute(
            """
            CREATE TABLE receipt (
                id              BIGSERIAL PRIMARY KEY,
                file_name       VARCHAR(255) NOT NULL,
                description     TEXT NOT NULL,
                object_key      VARCHAR(255) NOT NULL,
                size_bytes      BIGINT,
                sha256_checksum VARCHAR(64),
                uploaded_at     TIMESTAMPTZ,
                document_date   DATE NOT NULL
            )
            """,
        )
        stmt.execute(
            "CREATE TABLE invoice_item_receipt (invoice_item_id BIGINT NOT NULL REFERENCES invoice_item(id), " +
                "receipt_id BIGINT NOT NULL REFERENCES receipt(id), PRIMARY KEY (invoice_item_id, receipt_id))",
        )
        stmt.execute(
            "CREATE TABLE invoice_receipt (invoice_id BIGINT NOT NULL REFERENCES invoice(id), " +
                "receipt_id BIGINT NOT NULL REFERENCES receipt(id), PRIMARY KEY (invoice_id, receipt_id))",
        )
        // Mirrors V1__baseline_schema.sql's receipt_freeze_when_issued()/trg_receipt_freeze_when_issued.
        stmt.execute(
            """
            CREATE OR REPLACE FUNCTION receipt_freeze_when_issued() RETURNS TRIGGER AS $$
            DECLARE
                v_issued BOOLEAN;
            BEGIN
                IF NEW.file_name IS DISTINCT FROM OLD.file_name
                    OR NEW.object_key IS DISTINCT FROM OLD.object_key
                    OR NEW.sha256_checksum IS DISTINCT FROM OLD.sha256_checksum
                    OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes
                    OR NEW.uploaded_at IS DISTINCT FROM OLD.uploaded_at
                    OR NEW.description IS DISTINCT FROM OLD.description
                    OR NEW.document_date IS DISTINCT FROM OLD.document_date THEN
                    SELECT EXISTS (
                        SELECT 1 FROM invoice_item_receipt iir
                        JOIN invoice_item ii ON ii.id = iir.invoice_item_id
                        JOIN invoice i ON i.id = ii.invoice_id
                        WHERE iir.receipt_id = OLD.id AND i.status IN (2, 4)
                        UNION ALL
                        SELECT 1 FROM invoice_receipt ir
                        JOIN invoice i ON i.id = ir.invoice_id
                        WHERE ir.receipt_id = OLD.id AND i.status IN (2, 4)
                    ) INTO v_issued;
                    IF v_issued THEN
                        RAISE EXCEPTION 'receipt % is attached to an issued invoice and cannot be changed', OLD.id
                            USING ERRCODE = '23001';
                    END IF;
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """,
        )
        stmt.execute(
            "CREATE TRIGGER trg_receipt_freeze_when_issued BEFORE UPDATE ON receipt " +
                "FOR EACH ROW EXECUTE FUNCTION receipt_freeze_when_issued()",
        )
    }

    @AfterEach
    fun close() = setup.close()

    private fun newConnection(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).apply { autoCommit = true }

    private fun insertInvoice(conn: Connection, status: Int): Long {
        val rs = conn.createStatement().executeQuery("INSERT INTO invoice (status) VALUES ($status) RETURNING id")
        rs.next()
        return rs.getLong(1)
    }

    private fun insertItem(conn: Connection, invoiceId: Long): Long {
        val rs = conn.createStatement().executeQuery("INSERT INTO invoice_item (invoice_id) VALUES ($invoiceId) RETURNING id")
        rs.next()
        return rs.getLong(1)
    }

    private fun insertReceipt(conn: Connection, description: String = "Beleg"): Long {
        val rs = conn.createStatement().executeQuery(
            "INSERT INTO receipt (file_name, description, object_key, document_date) " +
                "VALUES ('a.pdf', '$description', 'k-${System.nanoTime()}', '2026-03-14') RETURNING id",
        )
        rs.next()
        return rs.getLong(1)
    }

    @Test
    fun `description update on a receipt attached to a SENT invoice item is rejected`() {
        newConnection().use { conn ->
            val invoiceId = insertInvoice(conn, status = 2) // SENT
            val itemId = insertItem(conn, invoiceId)
            val receiptId = insertReceipt(conn)
            conn.createStatement().execute("INSERT INTO invoice_item_receipt VALUES ($itemId, $receiptId)")

            val ex = assertThrows(SQLException::class.java) {
                conn.createStatement().executeUpdate("UPDATE receipt SET description = 'new' WHERE id = $receiptId")
            }
            assertEquals(true, ex.message?.contains("issued invoice") == true, ex.message)
        }
    }

    @Test
    fun `description update on a receipt attached only to a DRAFT invoice succeeds`() {
        newConnection().use { conn ->
            val invoiceId = insertInvoice(conn, status = 0) // DRAFT
            val itemId = insertItem(conn, invoiceId)
            val receiptId = insertReceipt(conn)
            conn.createStatement().execute("INSERT INTO invoice_item_receipt VALUES ($itemId, $receiptId)")

            conn.createStatement().executeUpdate("UPDATE receipt SET description = 'new' WHERE id = $receiptId")

            val description = conn.createStatement().executeQuery("SELECT description FROM receipt WHERE id = $receiptId")
                .let { it.next(); it.getString(1) }
            assertEquals("new", description)
        }
    }

    @Test
    fun `document date update on a receipt attached at invoice level to a CANCELLED invoice is rejected`() {
        newConnection().use { conn ->
            val invoiceId = insertInvoice(conn, status = 4) // CANCELLED
            val receiptId = insertReceipt(conn)
            conn.createStatement().execute("INSERT INTO invoice_receipt VALUES ($invoiceId, $receiptId)")

            assertThrows(SQLException::class.java) {
                conn.createStatement().executeUpdate("UPDATE receipt SET document_date = '2026-09-01' WHERE id = $receiptId")
            }
        }
    }

    @Test
    fun `receipt attached to one draft and one issued invoice is still locked`() {
        newConnection().use { conn ->
            val draftInvoice = insertInvoice(conn, status = 0)
            val sentInvoice = insertInvoice(conn, status = 2)
            val draftItem = insertItem(conn, draftInvoice)
            val sentItem = insertItem(conn, sentInvoice)
            val receiptId = insertReceipt(conn)
            conn.createStatement().execute("INSERT INTO invoice_item_receipt VALUES ($draftItem, $receiptId)")
            conn.createStatement().execute("INSERT INTO invoice_item_receipt VALUES ($sentItem, $receiptId)")

            assertThrows(SQLException::class.java) {
                conn.createStatement().executeUpdate("UPDATE receipt SET description = 'new' WHERE id = $receiptId")
            }
        }
    }

    @Test
    fun `identity field update on a receipt attached to a SENT invoice is rejected`() {
        newConnection().use { conn ->
            val invoiceId = insertInvoice(conn, status = 2) // SENT
            val itemId = insertItem(conn, invoiceId)
            val receiptId = insertReceipt(conn)
            conn.createStatement().execute("INSERT INTO invoice_item_receipt VALUES ($itemId, $receiptId)")

            val ex = assertThrows(SQLException::class.java) {
                conn.createStatement().executeUpdate("UPDATE receipt SET file_name = 'renamed.pdf' WHERE id = $receiptId")
            }
            assertEquals(true, ex.message?.contains("issued invoice") == true, ex.message)
        }
    }

    @Test
    fun `identity field update on an unassigned receipt succeeds`() {
        newConnection().use { conn ->
            val receiptId = insertReceipt(conn)

            conn.createStatement().executeUpdate("UPDATE receipt SET file_name = 'renamed.pdf' WHERE id = $receiptId")

            val fileName = conn.createStatement().executeQuery("SELECT file_name FROM receipt WHERE id = $receiptId")
                .let { it.next(); it.getString(1) }
            assertEquals("renamed.pdf", fileName)
        }
    }

    @Test
    fun `description update on an unassigned receipt succeeds`() {
        newConnection().use { conn ->
            val receiptId = insertReceipt(conn)

            conn.createStatement().executeUpdate("UPDATE receipt SET description = 'new' WHERE id = $receiptId")

            val description = conn.createStatement().executeQuery("SELECT description FROM receipt WHERE id = $receiptId")
                .let { it.next(); it.getString(1) }
            assertEquals("new", description)
        }
    }
}
