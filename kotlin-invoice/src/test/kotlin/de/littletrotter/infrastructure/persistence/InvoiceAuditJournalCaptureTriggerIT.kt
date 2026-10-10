package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalChain
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.test.FIXED_INSTANT
import de.littletrotter.test.withAppNow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime

/**
 * (konzept-aenderungsjournal.md) — `capture_invoice_audit_event()` and
 * `invoice_audit_journal` tested directly against real PostgreSQL, without the Quarkus stack —
 * same shape as [InvoiceFreezeTriggerIT]/[NumberJournalIT]. Mirrors
 * `V1__baseline_schema.sql` section 13 exactly, against a minimal standalone
 * `invoice`/`invoice_item`/`invoice_document` schema carrying only the columns the trigger itself
 * touches or that these tests exercise. Runs as an ordinary failsafe IT (name ends in `IT`, no
 * exclusion applies) whenever Docker is available.
 */
@Testcontainers
class InvoiceAuditJournalCaptureTriggerIT {

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
        setup.createStatement().execute("CREATE EXTENSION IF NOT EXISTS pgcrypto")

        setup.createStatement().execute("DROP TABLE IF EXISTS invoice_audit_journal")
        setup.createStatement().execute("DROP SEQUENCE IF EXISTS invoice_audit_journal_seq")
        setup.createStatement().execute("DROP TABLE IF EXISTS invoice_document")
        setup.createStatement().execute("DROP TABLE IF EXISTS invoice_item")
        setup.createStatement().execute("DROP TABLE IF EXISTS invoice")

        // Minimal invoice/invoice_item/invoice_document — only the columns the trigger reads
        // (id/invoice_id/invoice_number/sha256_checksum/invoice_item.position_id, the latter for
        // the `_row` identity) plus a couple of ordinary columns (free_text, description)
        // to exercise the UPDATE diff, and the three bookkeeping columns (version/updated_at/
        // updated_by) capture_invoice_audit_event() strips from `detail`.
        setup.createStatement().execute(
            """
            CREATE TABLE invoice (
                id             BIGSERIAL    PRIMARY KEY,
                invoice_number VARCHAR(255) NOT NULL DEFAULT '',
                free_text      TEXT,
                version        BIGINT       DEFAULT 0,
                updated_at     TIMESTAMPTZ,
                updated_by     VARCHAR(255)
            )
            """,
        )
        setup.createStatement().execute(
            """
            CREATE TABLE invoice_item (
                id          BIGSERIAL     PRIMARY KEY,
                invoice_id  BIGINT        NOT NULL,
                position_id VARCHAR(255)  NOT NULL DEFAULT '',
                description TEXT          NOT NULL DEFAULT '',
                quantity    NUMERIC(14,4) NOT NULL DEFAULT 1,
                version     BIGINT        DEFAULT 0
            )
            """,
        )
        setup.createStatement().execute(
            """
            CREATE TABLE invoice_document (
                id              BIGSERIAL    PRIMARY KEY,
                invoice_id      BIGINT       NOT NULL,
                sha256_checksum VARCHAR(64)  NOT NULL,
                version         BIGINT       DEFAULT 0
            )
            """,
        )

        // mirrors V1__baseline_schema.sql section 13 exactly (capture_invoice_audit_event(),
        // the invoice_audit_journal table/sequence, the three capture triggers, and the same
        // reject_append_only_mutation() append-only guard /number_journal uses).
        setup.createStatement().execute("CREATE SEQUENCE invoice_audit_journal_seq")
        setup.createStatement().execute(
            """
            CREATE TABLE invoice_audit_journal (
                seq             BIGINT PRIMARY KEY DEFAULT nextval('invoice_audit_journal_seq'),
                invoice_id      BIGINT       NOT NULL,
                invoice_number  VARCHAR(255),
                actor           VARCHAR(128) NOT NULL,
                actor_name      VARCHAR(128),
                occurred_at     TIMESTAMPTZ  NOT NULL,
                event           SMALLINT     NOT NULL,
                action          SMALLINT     NOT NULL,
                business_tx     BIGINT       NOT NULL,
                detail          JSONB        NOT NULL,
                document_sha256 VARCHAR(64),
                prev_hash       VARCHAR(64)  NOT NULL,
                entry_hash      VARCHAR(64)  NOT NULL,
                CONSTRAINT chk_invoice_audit_journal_action CHECK (action >= 0 AND action <= 17)
            )
            """,
        )
        setup.createStatement().execute(
            """
            CREATE OR REPLACE FUNCTION capture_invoice_audit_event() RETURNS TRIGGER AS $$
            DECLARE
                v_invoice_id      BIGINT;
                v_invoice_number  VARCHAR(255);
                v_event           SMALLINT;
                v_document_sha256 VARCHAR(64);
                v_bookkeeping     TEXT[] := ARRAY['version', 'updated_at', 'updated_by'];
                v_row_new         JSONB;
                v_row_old         JSONB;
                v_detail          JSONB;
                v_diff            JSONB;
                v_key             TEXT;
                v_actor           VARCHAR(128);
                v_actor_name      VARCHAR(128);
                v_seq             BIGINT;
                v_prev_hash       VARCHAR(64);
                v_entry_hash      VARCHAR(64);
                v_action          SMALLINT;
                v_business_tx     BIGINT;
                v_occurred_at     TIMESTAMPTZ;
            BEGIN
                IF TG_TABLE_NAME = 'invoice' THEN
                    v_invoice_id := COALESCE(NEW.id, OLD.id);
                    v_invoice_number := NULLIF(COALESCE(NEW.invoice_number, OLD.invoice_number), '');
                    v_event := CASE TG_OP WHEN 'INSERT' THEN 0 WHEN 'UPDATE' THEN 1 ELSE 2 END;
                ELSIF TG_TABLE_NAME = 'invoice_item' THEN
                    v_invoice_id := COALESCE(NEW.invoice_id, OLD.invoice_id);
                    SELECT NULLIF(invoice_number, '') INTO v_invoice_number FROM invoice WHERE id = v_invoice_id;
                    v_event := CASE TG_OP WHEN 'INSERT' THEN 3 WHEN 'UPDATE' THEN 4 ELSE 5 END;
                ELSIF TG_TABLE_NAME = 'invoice_document' THEN
                    v_invoice_id := COALESCE(NEW.invoice_id, OLD.invoice_id);
                    SELECT NULLIF(invoice_number, '') INTO v_invoice_number FROM invoice WHERE id = v_invoice_id;
                    v_event := CASE TG_OP WHEN 'INSERT' THEN 6 WHEN 'UPDATE' THEN 7 ELSE 8 END;
                    v_document_sha256 := COALESCE(NEW.sha256_checksum, OLD.sha256_checksum);
                ELSE
                    RAISE EXCEPTION 'capture_invoice_audit_event() attached to unexpected table %', TG_TABLE_NAME;
                END IF;

                IF TG_OP = 'UPDATE' THEN
                    v_row_new := to_jsonb(NEW) - v_bookkeeping;
                    v_row_old := to_jsonb(OLD) - v_bookkeeping;
                    v_diff := '{}'::jsonb;
                    FOR v_key IN SELECT jsonb_object_keys(v_row_new) LOOP
                        IF v_row_new -> v_key IS DISTINCT FROM v_row_old -> v_key THEN
                            v_diff := v_diff || jsonb_build_object(v_key, jsonb_build_object('old', v_row_old -> v_key, 'new', v_row_new -> v_key));
                        END IF;
                    END LOOP;
                    IF TG_TABLE_NAME = 'invoice_item' THEN
                        v_diff := v_diff || jsonb_build_object('_row', jsonb_build_object('id', NEW.id, 'position_id', NEW.position_id));
                    ELSE
                        v_diff := v_diff || jsonb_build_object('_row', jsonb_build_object('id', NEW.id));
                    END IF;
                    v_detail := v_diff;
                ELSIF TG_OP = 'INSERT' THEN
                    v_detail := to_jsonb(NEW) - v_bookkeeping;
                ELSE
                    v_detail := to_jsonb(OLD) - v_bookkeeping;
                END IF;

                v_actor := COALESCE(NULLIF(current_setting('app.actor', true), ''), session_user);
                v_actor_name := NULLIF(current_setting('app.actor_name', true), '');
                v_action := COALESCE(NULLIF(current_setting('app.audit_action', true), ''), '0')::SMALLINT;
                v_business_tx := txid_current();
                v_occurred_at := NULLIF(current_setting('app.now', true), '')::TIMESTAMPTZ;
                IF v_occurred_at IS NULL THEN
                    RAISE EXCEPTION 'app.now is not set: a write to % must carry the application time', TG_TABLE_NAME
                        USING ERRCODE = 'not_null_violation',
                              HINT = 'SELECT set_config(''app.now'', <timestamptz>, true) at the start of the transaction';
                END IF;

                PERFORM pg_advisory_xact_lock(1308);

                v_seq := nextval('invoice_audit_journal_seq');
                SELECT entry_hash INTO v_prev_hash FROM invoice_audit_journal ORDER BY seq DESC LIMIT 1;
                IF v_prev_hash IS NULL THEN
                    v_prev_hash := encode(digest('invoice_audit_journal-genesis', 'sha256'), 'hex');
                END IF;

                v_entry_hash := encode(
                    digest(
                        v_prev_hash || chr(31) || v_seq::text || chr(31) || v_invoice_id::text || chr(31) ||
                        COALESCE(v_invoice_number, chr(1)) || chr(31) || v_actor || chr(31) ||
                        COALESCE(v_actor_name, chr(1)) || chr(31) || v_event::text || chr(31) ||
                        v_detail::text || chr(31) || COALESCE(v_document_sha256, chr(1)) || chr(31) ||
                        v_action::text || chr(31) || v_business_tx::text,
                        'sha256'
                    ),
                    'hex'
                );

                INSERT INTO invoice_audit_journal
                    (seq, invoice_id, invoice_number, actor, actor_name, occurred_at, event, action, business_tx, detail, document_sha256, prev_hash, entry_hash)
                VALUES
                    (v_seq, v_invoice_id, v_invoice_number, v_actor, v_actor_name, v_occurred_at, v_event, v_action, v_business_tx, v_detail, v_document_sha256, v_prev_hash, v_entry_hash);

                IF TG_OP = 'DELETE' THEN
                    RETURN OLD;
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """,
        )
        setup.createStatement().execute(
            "CREATE TRIGGER trg_invoice_audit_capture AFTER INSERT OR UPDATE OR DELETE ON invoice " +
                "FOR EACH ROW EXECUTE FUNCTION capture_invoice_audit_event()",
        )
        setup.createStatement().execute(
            "CREATE TRIGGER trg_invoice_item_audit_capture AFTER INSERT OR UPDATE OR DELETE ON invoice_item " +
                "FOR EACH ROW EXECUTE FUNCTION capture_invoice_audit_event()",
        )
        setup.createStatement().execute(
            "CREATE TRIGGER trg_invoice_document_audit_capture AFTER INSERT OR UPDATE OR DELETE ON invoice_document " +
                "FOR EACH ROW EXECUTE FUNCTION capture_invoice_audit_event()",
        )

        // 's reject_append_only_mutation() — identical body to the baseline/NumberJournalIT's own copy.
        setup.createStatement().execute(
            """
            CREATE OR REPLACE FUNCTION reject_append_only_mutation() RETURNS TRIGGER AS $$
            BEGIN
                RAISE EXCEPTION '% is append-only; % is not permitted', TG_TABLE_NAME, TG_OP
                    USING ERRCODE = '23001';
            END;
            $$ LANGUAGE plpgsql
            """,
        )
        setup.createStatement().execute(
            "CREATE TRIGGER trg_invoice_audit_journal_append_only BEFORE UPDATE OR DELETE ON invoice_audit_journal " +
                "FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation()",
        )
    }

    @AfterEach
    fun close() = setup.close()

    private fun newConnection(autoCommit: Boolean = true): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            .apply { this.autoCommit = autoCommit }
            .withAppNow()

    private fun insertInvoice(conn: Connection, invoiceNumber: String = ""): Long =
        conn.prepareStatement("INSERT INTO invoice (invoice_number) VALUES (?) RETURNING id").use { stmt ->
            stmt.setString(1, invoiceNumber)
            stmt.executeQuery().let { it.next(); it.getLong(1) }
        }

    private fun latestJournalRow(conn: Connection): Map<String, Any?> {
        val rs = conn.createStatement().executeQuery(
            "SELECT seq, invoice_id, invoice_number, actor, actor_name, event, action, business_tx, detail, document_sha256, prev_hash, entry_hash " +
                "FROM invoice_audit_journal ORDER BY seq DESC LIMIT 1",
        )
        rs.next()
        return mapOf(
            "seq" to rs.getLong("seq"),
            "invoice_id" to rs.getLong("invoice_id"),
            "invoice_number" to rs.getString("invoice_number"),
            "actor" to rs.getString("actor"),
            "actor_name" to rs.getString("actor_name"),
            "event" to rs.getInt("event"),
            "action" to rs.getInt("action"),
            "business_tx" to rs.getLong("business_tx"),
            "detail" to rs.getString("detail"),
            "document_sha256" to rs.getString("document_sha256"),
            "prev_hash" to rs.getString("prev_hash"),
            "entry_hash" to rs.getString("entry_hash"),
        )
    }

    @Test
    fun `RS-8 - INSERT on invoice writes an INVOICE_CREATED row with session_user as actor when no GUC is set`() {
        newConnection().use { conn ->
            insertInvoice(conn, "RE-2026-0001")
            val row = latestJournalRow(conn)
            assertEquals(0, row["event"]) // INVOICE_CREATED
            assertEquals("RE-2026-0001", row["invoice_number"])
            assertEquals(postgres.username, row["actor"]) // no app.actor GUC set -> session_user fallback
            assertNull(row["actor_name"])
        }
    }

    @Test
    fun `RS-8 - a transaction that sets app_actor and app_actor_name attributes the row to that identity`() {
        newConnection(autoCommit = false).use { conn ->
            conn.createStatement().execute("SELECT set_config('app.actor', 'oidc-sub-123', true), set_config('app.actor_name', 'alice', true)")
            insertInvoice(conn, "RE-2026-0002")
            conn.commit()

            newConnection().use { readConn ->
                val row = latestJournalRow(readConn)
                assertEquals("oidc-sub-123", row["actor"])
                assertEquals("alice", row["actor_name"])
            }
        }
    }

    @Test
    fun `RS-8 - UPDATE on invoice captures only the changed columns in detail, not the untouched ones`() {
        newConnection().use { conn ->
            val id = insertInvoice(conn, "RE-2026-0003")
            conn.createStatement().executeUpdate("UPDATE invoice SET free_text = 'geaendert' WHERE id = $id")

            val row = latestJournalRow(conn)
            assertEquals(1, row["event"]) // INVOICE_UPDATED
            val detail = row["detail"] as String
            assertTrue(detail.contains("free_text"), "expected the changed column in detail, got: $detail")
            assertTrue(!detail.contains("invoice_number"), "expected the UNCHANGED column left out of detail, got: $detail")
        }
    }

    @Test
    fun `RS-8 - DELETE on invoice writes an INVOICE_DELETED row using the OLD values`() {
        newConnection().use { conn ->
            val id = insertInvoice(conn, "RE-2026-0004")
            conn.createStatement().executeUpdate("DELETE FROM invoice WHERE id = $id")

            val row = latestJournalRow(conn)
            assertEquals(2, row["event"]) // INVOICE_DELETED
            assertEquals("RE-2026-0004", row["invoice_number"])
            assertEquals(id, row["invoice_id"])
        }
    }

    @Test
    fun `RS-8 - invoice_item INSERT resolves invoice_number from the parent invoice`() {
        newConnection().use { conn ->
            val id = insertInvoice(conn, "RE-2026-0005")
            conn.createStatement().executeUpdate("INSERT INTO invoice_item (invoice_id, description) VALUES ($id, 'Beratung')")

            val row = latestJournalRow(conn)
            assertEquals(3, row["event"]) // INVOICE_ITEM_CREATED
            assertEquals("RE-2026-0005", row["invoice_number"])
            assertEquals(id, row["invoice_id"])
        }
    }

    @Test
    fun `RS-8 - invoice_document INSERT carries document_sha256`() {
        newConnection().use { conn ->
            val id = insertInvoice(conn, "RE-2026-0006")
            val sha = "a".repeat(64)
            conn.createStatement().executeUpdate("INSERT INTO invoice_document (invoice_id, sha256_checksum) VALUES ($id, '$sha')")

            val row = latestJournalRow(conn)
            assertEquals(6, row["event"]) // INVOICE_DOCUMENT_CREATED
            assertEquals(sha, row["document_sha256"])
        }
    }

    @Test
    fun `RS-8 - the chain links prev_hash of the second entry to entry_hash of the first`() {
        newConnection().use { conn ->
            insertInvoice(conn, "RE-2026-0007")
            val first = latestJournalRow(conn)
            insertInvoice(conn, "RE-2026-0008")
            val second = latestJournalRow(conn)

            assertEquals(first["entry_hash"], second["prev_hash"])
            assertTrue((first["prev_hash"] as String).isNotBlank(), "the very first row still needs a (genesis) prev_hash")
        }
    }

    @Test
    fun `RS-8 - UPDATE on invoice_audit_journal is rejected instead of silently no-op`() {
        newConnection().use { conn ->
            insertInvoice(conn, "RE-2026-0009")
            val ex = assertThrows(SQLException::class.java) {
                conn.createStatement().executeUpdate("UPDATE invoice_audit_journal SET actor = 'tampered' WHERE seq = 1")
            }
            assertTrue(ex.message?.contains("append-only") == true, "expected the guard's own message, got: ${ex.message}")
        }
    }

    @Test
    fun `RS-8 - DELETE on invoice_audit_journal is rejected instead of silently no-op`() {
        newConnection().use { conn ->
            insertInvoice(conn, "RE-2026-0010")
            assertThrows(SQLException::class.java) {
                conn.createStatement().executeUpdate("DELETE FROM invoice_audit_journal WHERE seq = 1")
            }
        }
    }

    @Test
    fun `RS-8 - a full lifecycle (create, edit, add item, generate document, delete item) keeps the chain valid`() {
        // E2E-shaped smoke test — the actual E2E/Playwright coverage is out of scope per this
        // AP's own DoD note ("Playwright: not applicable, no UI yet"); this exercises the same
        // sequence at the trigger level.
        newConnection().use { conn ->
            val id = insertInvoice(conn, "RE-2026-0011")
            conn.createStatement().executeUpdate("UPDATE invoice SET free_text = 'Notiz' WHERE id = $id")
            conn.createStatement().executeUpdate("INSERT INTO invoice_item (invoice_id, description) VALUES ($id, 'Position')")
            val sha = "b".repeat(64)
            conn.createStatement().executeUpdate("INSERT INTO invoice_document (invoice_id, sha256_checksum) VALUES ($id, '$sha')")
            conn.createStatement().executeUpdate("DELETE FROM invoice_item WHERE invoice_id = $id")

            val rs = conn.createStatement().executeQuery(
                "SELECT seq, prev_hash, entry_hash FROM invoice_audit_journal ORDER BY seq",
            )
            var previousEntryHash: String? = null
            var rows = 0
            while (rs.next()) {
                rows++
                val prevHash = rs.getString("prev_hash")
                if (previousEntryHash != null) {
                    assertEquals(previousEntryHash, prevHash, "chain break at seq ${rs.getLong("seq")}")
                }
                previousEntryHash = rs.getString("entry_hash")
            }
            assertEquals(5, rows) // CREATED, UPDATED, ITEM_CREATED, DOCUMENT_CREATED, ITEM_DELETED
        }
    }

    @Test
    fun `RS-8 - a direct SQL write to invoice (as the connecting DB role) is journaled with that role as actor`() {
        // J1 (PO 2026-09-03) — the whole point of the trigger-based design: it must catch a write
        // that bypasses the application entirely, not only ones that went through it.
        newConnection().use { conn ->
            insertInvoice(conn, "RE-2026-0012")
            val row = latestJournalRow(conn)
            assertEquals(postgres.username, row["actor"])
            assertNotNull(row["entry_hash"])
        }
    }

    // ─── action/business_tx ───────────────────────────────────────────────────

    @Test
    fun `II4-A - INSERT with no app_audit_action set journals action 0 (UNSPECIFIED)`() {
        newConnection().use { conn ->
            insertInvoice(conn, "RE-2026-II4-0001")
            val row = latestJournalRow(conn)
            assertEquals(0, row["action"])
        }
    }

    @Test
    fun `II4-A - a transaction that sets app_audit_action attributes every row in it to that action`() {
        newConnection(autoCommit = false).use { conn ->
            conn.createStatement().execute("SELECT set_config('app.audit_action', '4', true)") // ITEM_ADDED
            val id = insertInvoice(conn, "RE-2026-II4-0002")
            conn.createStatement().executeUpdate("INSERT INTO invoice_item (invoice_id, description) VALUES ($id, 'Pos')")
            conn.commit()

            newConnection().use { readConn ->
                val rs = readConn.createStatement().executeQuery(
                    "SELECT action, business_tx FROM invoice_audit_journal WHERE invoice_id = $id ORDER BY seq",
                )
                var rows = 0
                var businessTx: Long? = null
                while (rs.next()) {
                    rows++
                    assertEquals(4, rs.getInt("action"))
                    if (businessTx == null) businessTx = rs.getLong("business_tx")
                    else assertEquals(businessTx, rs.getLong("business_tx"), "both rows of one transaction must share business_tx")
                }
                assertEquals(2, rows)
            }
        }
    }

    /**
     * Outermost wins: simulates [de.littletrotter.application.shared.AuditGucInterceptor]'s
     * own `CASE WHEN COALESCE(current_setting(...), '') = '' THEN set_config(...) ELSE
     * current_setting(...) END` idiom directly against Postgres, the same statement shape the
     * interceptor issues, proving the trigger only ever sees the FIRST action set in a transaction
     * even when a nested call tries to set a different one afterwards.
     */
    @Test
    fun `II4-A - outermost-wins - a second set_config attempt within the same transaction never overwrites the first action`() {
        newConnection(autoCommit = false).use { conn ->
            conn.createStatement().execute(
                "SELECT CASE WHEN COALESCE(current_setting('app.audit_action', true), '') = '' " +
                    "THEN set_config('app.audit_action', '4', true) " + // outer: ITEM_ADDED
                    "ELSE current_setting('app.audit_action', true) END",
            )
            // A nested call (e.g. an internal recalculateTotals()) attempting to set RECALCULATED (7).
            conn.createStatement().execute(
                "SELECT CASE WHEN COALESCE(current_setting('app.audit_action', true), '') = '' " +
                    "THEN set_config('app.audit_action', '7', true) " +
                    "ELSE current_setting('app.audit_action', true) END",
            )
            val id = insertInvoice(conn, "RE-2026-II4-0003")
            conn.commit()

            newConnection().use { readConn ->
                val row = latestJournalRow(readConn)
                assertEquals(id, row["invoice_id"])
                assertEquals(4, row["action"], "the outer ITEM_ADDED action must win over the nested RECALCULATED attempt")
            }
        }
    }

    @Test
    fun `II4-A - business_tx is txid_current, shared within one transaction and different across transactions`() {
        newConnection(autoCommit = false).use { conn ->
            val id = insertInvoice(conn, "RE-2026-II4-0004")
            conn.createStatement().executeUpdate("UPDATE invoice SET free_text = 'x' WHERE id = $id")
            conn.commit()
        }
        newConnection().use { conn ->
            conn.createStatement().executeUpdate("UPDATE invoice SET free_text = 'y' WHERE invoice_number = 'RE-2026-II4-0004'")
        }

        newConnection().use { readConn ->
            val rs = readConn.createStatement().executeQuery(
                "SELECT seq, business_tx FROM invoice_audit_journal WHERE invoice_number = 'RE-2026-II4-0004' ORDER BY seq",
            )
            val businessTxBySeq = mutableMapOf<Long, Long>()
            while (rs.next()) businessTxBySeq[rs.getLong("seq")] = rs.getLong("business_tx")
            assertEquals(3, businessTxBySeq.size) // INSERT, UPDATE (same tx), UPDATE (separate tx)
            val values = businessTxBySeq.values.toList()
            assertEquals(values[0], values[1], "the INSERT and the first UPDATE ran in the same transaction, same business_tx")
            assertTrue(values[1] != values[2], "the second UPDATE ran in a separate transaction, a different business_tx")
        }
    }

    // ─── row identity (`_row`) ─────────────────────────────────────────────────

    @Test
    fun `II4-B - an UPDATE of an invoice_item row journals _row_id and _row_position_id, and the Kotlin chain verifies it`() {
        newConnection().use { conn ->
            val invoiceId = insertInvoice(conn, "RE-2026-II4B-0001")
            conn.createStatement().executeUpdate(
                "INSERT INTO invoice_item (invoice_id, position_id, description, quantity) VALUES ($invoiceId, '3', 'Kabelkanal', 5)",
            )
            val itemId = conn.createStatement().executeQuery("SELECT id FROM invoice_item WHERE invoice_id = $invoiceId")
                .let { it.next(); it.getLong(1) }

            // A quantity edit never touches position_id itself — exactly the case that used to
            // leave a reader unable to tell WHICH item changed.
            conn.createStatement().executeUpdate("UPDATE invoice_item SET quantity = 8 WHERE id = $itemId")

            val row = latestJournalRow(conn)
            assertEquals(4, row["event"]) // INVOICE_ITEM_UPDATED
            val detail = row["detail"] as String
            assertTrue(detail.contains("\"_row\""), "expected the row identity in detail, got: $detail")
            assertTrue(detail.contains(itemId.toString()), "expected the item's own id in _row, got: $detail")
            assertTrue(detail.contains("\"position_id\""), "expected position_id inside _row, got: $detail")
            assertTrue(detail.contains("\"3\""), "expected the unchanged position number 3 in _row, got: $detail")

            // Kotlin-side chain recomputation (extended here to _row's own detail bytes)
            // must agree with the trigger's own entry_hash — a chain the DB writes one way and
            // Kotlin verifies another is the failure mode this proves against.
            val entry = InvoiceAuditJournalEntry(
                seq = row["seq"] as Long,
                businessTx = row["business_tx"] as Long,
                invoiceId = row["invoice_id"] as Long,
                invoiceNumber = row["invoice_number"] as String?,
                actor = row["actor"] as String,
                actorName = row["actor_name"] as String?,
                occurredAt = OffsetDateTime.now(),
                event = InvoiceAuditEvent.fromOrdinal(row["event"] as Int),
                action = InvoiceAuditAction.fromOrdinal(row["action"] as Int),
                detail = detail,
                documentSha256 = row["document_sha256"] as String?,
                prevHash = row["prev_hash"] as String,
                entryHash = row["entry_hash"] as String,
            )
            val recomputed = InvoiceAuditJournalChain.computeEntryHash(entry.prevHash, entry)
            assertEquals(entry.entryHash, recomputed, "Kotlin chain recomputation must match the trigger's own entry_hash")
        }
    }

    @Test
    fun `II4-A - action out of the 0 to 17 range is rejected by chk_invoice_audit_journal_action`() {
        newConnection().use { conn ->
            val ex = assertThrows(SQLException::class.java) {
                conn.createStatement().executeUpdate(
                    "INSERT INTO invoice_audit_journal (invoice_id, actor, occurred_at, event, action, business_tx, detail, prev_hash, entry_hash) " +
                        "VALUES (1, 'test', now(), 0, 18, 1, '{}', 'p', 'h')",
                )
            }
            assertTrue(ex.message?.contains("chk") == true || ex.sqlState == "23514", "expected a CHECK violation, got: ${ex.message}")
        }
    }

    // ─── app.now — the database never invents a time ─────────────────────────────

    @Test
    fun `a write to invoice with no app_now set is rejected and leaves no journal row`() {
        // Deliberately bypasses newConnection()'s own withAppNow() default: this is the one case
        // that must reproduce a raw JDBC connection exactly as DriverManager hands it out, with
        // nobody having told the database what time it is.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val before = conn.createStatement().executeQuery("SELECT COUNT(*) FROM invoice_audit_journal")
                .let { it.next(); it.getLong(1) }

            val ex = assertThrows(SQLException::class.java) { insertInvoice(conn, "RE-2026-APPNOW-0001") }
            assertTrue(
                ex.message?.contains("app.now is not set") == true,
                "expected the trigger's own app.now guard message, got: ${ex.message}",
            )

            val after = conn.createStatement().executeQuery("SELECT COUNT(*) FROM invoice_audit_journal")
                .let { it.next(); it.getLong(1) }
            assertEquals(before, after, "the rejected INSERT must not have left a journal row behind")
        }
    }

    @Test
    fun `a write to invoice with app_now set journals occurred_at exactly equal to that instant`() {
        newConnection().use { conn ->
            insertInvoice(conn, "RE-2026-APPNOW-0002")

            val occurredAt = conn.createStatement()
                .executeQuery("SELECT occurred_at FROM invoice_audit_journal ORDER BY seq DESC LIMIT 1")
                .let { it.next(); it.getObject(1, OffsetDateTime::class.java) }
            assertEquals(FIXED_INSTANT, occurredAt.toInstant())
        }
    }
}
