package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * P1 — verifies the party-identifier columns (`V1__baseline_schema.sql`) end to end against a
 * real Postgres (Testcontainers, full Flyway chain via
 * [PostgresTestResource]/`quarkus.flyway.migrate-at-start`): that the `sender_profile`/
 * `recipient` columns and the `invoice_party_snapshot` table exist with the shape P1 specifies,
 * that every "value and scheme travel together" CHECK constraint actually rejects a mismatched
 * pair (P1-T01/T02 at the DB level), and that a full persistence roundtrip through the JPA
 * entities (P1-T09) round-trips correctly.
 *
 * Note: unlike R1's tax rules, there is no pre-existing seed data for `sender_profile`/
 * `recipient`, so an `email -> electronic_address` backfill has no rows to act on in a fresh
 * Testcontainers database and isn't independently verifiable here.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PartyIdentifierMigrationIT {

    @Inject
    lateinit var em: EntityManager

    companion object {
        // sender_profile.invoice_prefix is UNIQUE with a shared default ('RE-'); each
        // @Transactional test method here commits (it isn't rolled back like @TestTransaction),
        // so repeated inserts across tests need distinct prefixes to avoid colliding.
        private val prefixCounter = java.util.concurrent.atomic.AtomicInteger(0)
    }

    private fun insertSender(): Long =
        (em.createNativeQuery(
            "INSERT INTO sender_profile (name, invoice_prefix, created_at, version) VALUES ('IT Sender', :prefix, now(), 0) RETURNING id"
        ).setParameter("prefix", "P${prefixCounter.incrementAndGet()}").singleResult as Number).toLong()

    private fun insertRecipient(): Long =
        (em.createNativeQuery(
            "INSERT INTO recipient (customer_number, name, created_at, version, document_language) VALUES (:num, 'IT Recipient', now(), 0, 'de') RETURNING id"
        ).setParameter("num", "KD-IT-${prefixCounter.incrementAndGet()}").singleResult as Number).toLong()

    // payment_term.name_group_id (not a plain "name" column) needs its own
    // localized_text_group + localized_text row first; chained in one native-SQL statement the
    // same way V2__baseline_seed.sql now seeds its own payment terms.
    private fun insertPaymentTerm(): Long =
        (em.createNativeQuery(
            """WITH grp AS (
                   INSERT INTO localized_text_group (purpose) VALUES (1) RETURNING id
               ), txt AS (
                   INSERT INTO localized_text (group_id, language_id, value) SELECT id, 1, 'IT Term' FROM grp
               )
               INSERT INTO payment_term (name_group_id, due_in_days, created_at) SELECT id, 14, now() FROM grp
               RETURNING id"""
        ).singleResult as Number).toLong()

    private fun insertInvoice(senderId: Long, recipientId: Long, paymentTermId: Long): Long =
        (em.createNativeQuery(
            """INSERT INTO invoice (invoice_number, status, sender_profile_id, recipient_id, payment_term_id,
                   invoice_date, payment_terms_days, reverse_charge, net_amount, vat_amount, gross_amount,
                   validation_status, type_code, created_at)
               VALUES (:num, 0, :sender, :recipient, :term, '2026-06-15', 14, false, 0.00, 0.00, 0.00, 0, 0, '2026-06-15T10:00:00Z')
               RETURNING id"""
        ).setParameter("num", "IT-${prefixCounter.incrementAndGet()}")
            .setParameter("sender", senderId).setParameter("recipient", recipientId).setParameter("term", paymentTermId)
            .singleResult as Number).toLong()

    // ── P1-T01/T02: value+scheme pair constraints on sender_profile ────────────

    @Test
    @Transactional
    fun `a seller identifier value without a scheme is rejected`() {
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "UPDATE sender_profile SET seller_identifier_value = 'DE123' WHERE id = :id"
            ).setParameter("id", insertSender()).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `a seller electronic address scheme without a value is rejected`() {
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "UPDATE sender_profile SET electronic_address_scheme_id = 'EM' WHERE id = :id"
            ).setParameter("id", insertSender()).executeUpdate()
            em.flush()
        }
    }

    // ── P1-T01/T02: value+scheme pair constraints on recipient ─────────────────

    @Test
    @Transactional
    fun `a buyer identifier value without a scheme is rejected`() {
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "UPDATE recipient SET buyer_identifier_value = '987654321' WHERE id = :id"
            ).setParameter("id", insertRecipient()).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `a legal registration identifier without a scheme is rejected`() {
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "UPDATE recipient SET legal_registration_identifier = 'HRB 1234' WHERE id = :id"
            ).setParameter("id", insertRecipient()).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `an out-of-range customer_type ordinal is rejected`() {
        // 99 is a valid smallint but outside chk_recipient_customer_type's IN-list (0,1).
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "UPDATE recipient SET customer_type = 99 WHERE id = :id"
            ).setParameter("id", insertRecipient()).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `an out-of-range vat_id_verification_status ordinal is rejected`() {
        // 99 is a valid smallint but outside chk_recipient_vat_id_verification_status's IN-list (0,1,2).
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "UPDATE recipient SET vat_id_verification_status = 99 WHERE id = :id"
            ).setParameter("id", insertRecipient()).executeUpdate()
            em.flush()
        }
    }

    // ── P1-T06 equivalent: invoice_party_snapshot constraints ──────────────────

    @Test
    @Transactional
    fun `a party snapshot identifier value without a scheme is rejected`() {
        val invoiceId = insertInvoice(insertSender(), insertRecipient(), insertPaymentTerm())
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                """INSERT INTO invoice_party_snapshot (invoice_id, party_role, name, identifier_value, created_at)
                   VALUES (:invoiceId, 0, 'X', '987654321', '2026-06-15T10:00:00Z')"""
            ).setParameter("invoiceId", invoiceId).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `a party snapshot with an out-of-range role ordinal is rejected`() {
        // 99 is a valid smallint but outside chk_invoice_party_snapshot_role's IN-list (0,1).
        val invoiceId = insertInvoice(insertSender(), insertRecipient(), insertPaymentTerm())
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                """INSERT INTO invoice_party_snapshot (invoice_id, party_role, name, created_at)
                   VALUES (:invoiceId, 99, 'X', '2026-06-15T10:00:00Z')"""
            ).setParameter("invoiceId", invoiceId).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `a buyer_reference on a SELLER row is rejected`() {
        val invoiceId = insertInvoice(insertSender(), insertRecipient(), insertPaymentTerm())
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                """INSERT INTO invoice_party_snapshot (invoice_id, party_role, name, buyer_reference, created_at)
                   VALUES (:invoiceId, 0, 'X', '991-01484-64', '2026-06-15T10:00:00Z')"""
            ).setParameter("invoiceId", invoiceId).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `a second snapshot for the same invoice and role is rejected`() {
        val invoiceId = insertInvoice(insertSender(), insertRecipient(), insertPaymentTerm())
        em.createNativeQuery(
            """INSERT INTO invoice_party_snapshot (invoice_id, party_role, name, created_at)
               VALUES (:invoiceId, 0, 'First', '2026-06-15T10:00:00Z')"""
        ).setParameter("invoiceId", invoiceId).executeUpdate()
        em.flush()

        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                """INSERT INTO invoice_party_snapshot (invoice_id, party_role, name, created_at)
                   VALUES (:invoiceId, 0, 'Second', '2026-06-15T10:00:00Z')"""
            ).setParameter("invoiceId", invoiceId).executeUpdate()
            em.flush()
        }
    }

    // ── P1-T09: full persistence roundtrip through the JPA entities ────────────

    @Test
    @Transactional
    fun `sender identifier, legal registration scheme and electronic address roundtrip through JPA`() {
        val id = (em.createNativeQuery(
            """INSERT INTO sender_profile (name, commercial_register_number, legal_registration_scheme_id,
                   seller_identifier_value, seller_identifier_scheme_id,
                   electronic_address_value, electronic_address_scheme_id, created_at, version)
               VALUES ('RT Sender', 'HRB 9999', '0002', '123456789', '0204', 'seller@example.de', 'EM', now(), 0)
               RETURNING id"""
        ).singleResult as Number).toLong()
        em.flush()
        em.clear()

        val row = em.createNativeQuery(
            "SELECT seller_identifier_value, seller_identifier_scheme_id, electronic_address_value FROM sender_profile WHERE id = :id"
        ).setParameter("id", id).singleResult as Array<Any?>
        assertEquals("123456789", row[0])
        assertEquals("0204", row[1])
        assertEquals("seller@example.de", row[2])
    }

    @Test
    @Transactional
    fun `recipient buyer identifier, legal registration and customer type roundtrip through JPA`() {
        val id = (em.createNativeQuery(
            """INSERT INTO recipient (customer_number, name, buyer_identifier_value, buyer_identifier_scheme_id,
                   legal_registration_identifier, legal_registration_scheme_id, customer_type, created_at, version, document_language)
               VALUES ('KD-RT', 'RT Recipient', '987654321', '0204', 'HRB 1234', '0002', 0, now(), 0, 'de')
               RETURNING id"""
        ).singleResult as Number).toLong()
        em.flush()
        em.clear()

        val row = em.createNativeQuery(
            "SELECT buyer_identifier_value, legal_registration_identifier, customer_type FROM recipient WHERE id = :id"
        ).setParameter("id", id).singleResult as Array<Any?>
        assertEquals("987654321", row[0])
        assertEquals("HRB 1234", row[1])
        assertEquals(0, (row[2] as Number).toInt())  // customer_type.BUSINESS = 0
    }

    @Test
    @Transactional
    fun `a fully specified party snapshot pair persists for one invoice`() {
        val invoiceId = insertInvoice(insertSender(), insertRecipient(), insertPaymentTerm())

        em.createNativeQuery(
            """INSERT INTO invoice_party_snapshot
                   (invoice_id, party_role, name, identifier_value, identifier_scheme_id, vat_id, electronic_address_value, electronic_address_scheme_id, created_at)
               VALUES (:invoiceId, 0, 'Seller GmbH', '123456789', '0204', 'DE123456789', 'seller@example.de', 'EM', '2026-06-15T10:00:00Z')"""
        ).setParameter("invoiceId", invoiceId).executeUpdate()

        em.createNativeQuery(
            """INSERT INTO invoice_party_snapshot
                   (invoice_id, party_role, name, identifier_value, identifier_scheme_id, buyer_reference, created_at)
               VALUES (:invoiceId, 1, 'Buyer AG', '987654321', '0204', '991-01484-64', '2026-06-15T10:00:00Z')"""
        ).setParameter("invoiceId", invoiceId).executeUpdate()
        em.flush()
        em.clear()

        val count = (em.createNativeQuery(
            "SELECT COUNT(*) FROM invoice_party_snapshot WHERE invoice_id = :invoiceId"
        ).setParameter("invoiceId", invoiceId).singleResult as Number).toLong()
        assertEquals(2, count)

        val buyerRef = em.createNativeQuery(
            "SELECT buyer_reference FROM invoice_party_snapshot WHERE invoice_id = :invoiceId AND party_role = 1"
        ).setParameter("invoiceId", invoiceId).singleResult as String
        assertEquals("991-01484-64", buyerRef)
    }

    // ── PO report 03.09.2026 — invoice_party_snapshot.vat_id/buyer_reference widened to match
    //    their source columns (sender_profile.vat_id/recipient.vat_id = 64, invoice.buyer_reference
    //    = 100) ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    fun `a party snapshot pair with a 64-char vatId and a 100-char buyerReference roundtrips intact`() {
        val invoiceId = insertInvoice(insertSender(), insertRecipient(), insertPaymentTerm())
        val vatId64 = "LU" + "1".repeat(62)
        val buyerReference100 = "R".repeat(100)
        assertEquals(64, vatId64.length)
        assertEquals(100, buyerReference100.length)

        em.createNativeQuery(
            """INSERT INTO invoice_party_snapshot (invoice_id, party_role, name, vat_id, created_at)
               VALUES (:invoiceId, 0, 'Seller GmbH', :vatId, '2026-06-15T10:00:00Z')"""
        ).setParameter("invoiceId", invoiceId).setParameter("vatId", vatId64).executeUpdate()

        em.createNativeQuery(
            """INSERT INTO invoice_party_snapshot (invoice_id, party_role, name, vat_id, buyer_reference, created_at)
               VALUES (:invoiceId, 1, 'Buyer AG', :vatId, :buyerReference, '2026-06-15T10:00:00Z')"""
        ).setParameter("invoiceId", invoiceId).setParameter("vatId", vatId64).setParameter("buyerReference", buyerReference100)
            .executeUpdate()
        em.flush()
        em.clear()

        val sellerRow = em.createNativeQuery(
            "SELECT vat_id, buyer_reference FROM invoice_party_snapshot WHERE invoice_id = :invoiceId AND party_role = 0"
        ).setParameter("invoiceId", invoiceId).singleResult as Array<Any?>
        assertEquals(vatId64, sellerRow[0])
        assertEquals(null, sellerRow[1])

        val buyerRow = em.createNativeQuery(
            "SELECT vat_id, buyer_reference FROM invoice_party_snapshot WHERE invoice_id = :invoiceId AND party_role = 1"
        ).setParameter("invoiceId", invoiceId).singleResult as Array<Any?>
        assertEquals(vatId64, buyerRow[0])
        assertEquals(buyerReference100, buyerRow[1])
    }

    // ── Width guard — every invoice_party_snapshot column that is copied from a source column
    //    must stay at least as wide as its widest source, so a future narrowing (either side)
    //    fails here instead of on a customer insert like the 03.09.2026 PO report ──────────────

    private fun columnWidth(table: String, column: String): Int =
        (em.createNativeQuery(
            "SELECT character_maximum_length FROM information_schema.columns WHERE table_name = :t AND column_name = :c"
        ).setParameter("t", table).setParameter("c", column).singleResult as Number).toInt()

    @Test
    fun `every invoice_party_snapshot column is at least as wide as every source column it is copied from`() {
        val snapshot = "invoice_party_snapshot"

        // vat_id — sender_profile.snapshotAsSeller() / recipient.snapshotAsBuyer()
        assertTrue(columnWidth(snapshot, "vat_id") >= columnWidth("sender_profile", "vat_id"), "vat_id vs sender_profile.vat_id")
        assertTrue(columnWidth(snapshot, "vat_id") >= columnWidth("recipient", "vat_id"), "vat_id vs recipient.vat_id")

        // buyer_reference (BT-10) — invoice.buyer_reference override, falling back to recipient.routing_id
        assertTrue(columnWidth(snapshot, "buyer_reference") >= columnWidth("invoice", "buyer_reference"), "buyer_reference vs invoice.buyer_reference")
        assertTrue(columnWidth(snapshot, "buyer_reference") >= columnWidth("recipient", "routing_id"), "buyer_reference vs recipient.routing_id")

        // tax_number — sender_profile.snapshotAsSeller() only (recipient carries no tax_number)
        assertTrue(columnWidth(snapshot, "tax_number") >= columnWidth("sender_profile", "tax_number"), "tax_number vs sender_profile.tax_number")

        // name
        assertTrue(columnWidth(snapshot, "name") >= columnWidth("sender_profile", "name"), "name vs sender_profile.name")
        assertTrue(columnWidth(snapshot, "name") >= columnWidth("recipient", "name"), "name vs recipient.name")

        // identifier_value/identifier_scheme_id — sender_profile.seller_identifier_*, recipient.buyer_identifier_*
        assertTrue(
            columnWidth(snapshot, "identifier_value") >= columnWidth("sender_profile", "seller_identifier_value"),
            "identifier_value vs sender_profile.seller_identifier_value",
        )
        assertTrue(
            columnWidth(snapshot, "identifier_value") >= columnWidth("recipient", "buyer_identifier_value"),
            "identifier_value vs recipient.buyer_identifier_value",
        )
        assertTrue(
            columnWidth(snapshot, "identifier_scheme_id") >= columnWidth("sender_profile", "seller_identifier_scheme_id"),
            "identifier_scheme_id vs sender_profile.seller_identifier_scheme_id",
        )
        assertTrue(
            columnWidth(snapshot, "identifier_scheme_id") >= columnWidth("recipient", "buyer_identifier_scheme_id"),
            "identifier_scheme_id vs recipient.buyer_identifier_scheme_id",
        )

        // legal_registration_identifier/legal_registration_scheme_id — sender_profile.commercial_register_number,
        // recipient.legal_registration_identifier
        assertTrue(
            columnWidth(snapshot, "legal_registration_identifier") >= columnWidth("sender_profile", "commercial_register_number"),
            "legal_registration_identifier vs sender_profile.commercial_register_number",
        )
        assertTrue(
            columnWidth(snapshot, "legal_registration_identifier") >= columnWidth("recipient", "legal_registration_identifier"),
            "legal_registration_identifier vs recipient.legal_registration_identifier",
        )
        assertTrue(
            columnWidth(snapshot, "legal_registration_scheme_id") >= columnWidth("sender_profile", "legal_registration_scheme_id"),
            "legal_registration_scheme_id vs sender_profile.legal_registration_scheme_id",
        )
        assertTrue(
            columnWidth(snapshot, "legal_registration_scheme_id") >= columnWidth("recipient", "legal_registration_scheme_id"),
            "legal_registration_scheme_id vs recipient.legal_registration_scheme_id",
        )

        // electronic_address_value/electronic_address_scheme_id — both sender_profile and recipient
        assertTrue(
            columnWidth(snapshot, "electronic_address_value") >= columnWidth("sender_profile", "electronic_address_value"),
            "electronic_address_value vs sender_profile.electronic_address_value",
        )
        assertTrue(
            columnWidth(snapshot, "electronic_address_value") >= columnWidth("recipient", "electronic_address_value"),
            "electronic_address_value vs recipient.electronic_address_value",
        )
        assertTrue(
            columnWidth(snapshot, "electronic_address_scheme_id") >= columnWidth("sender_profile", "electronic_address_scheme_id"),
            "electronic_address_scheme_id vs sender_profile.electronic_address_scheme_id",
        )
        assertTrue(
            columnWidth(snapshot, "electronic_address_scheme_id") >= columnWidth("recipient", "electronic_address_scheme_id"),
            "electronic_address_scheme_id vs recipient.electronic_address_scheme_id",
        )
    }
}
