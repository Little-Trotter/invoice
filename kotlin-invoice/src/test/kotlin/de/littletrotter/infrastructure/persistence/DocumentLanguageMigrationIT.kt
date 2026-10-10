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
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies `invoice.document_language` end to end against a real Postgres
 * (Testcontainers, full Flyway chain via [PostgresTestResource]): the column exists with a `'de'`
 * default. There is no pre-existing `invoice` seed data in this project (same limitation noted in
 * `PartyIdentifierMigrationIT`), so the historic `reverse_charge → en` backfill rule itself has no
 * rows to act on in a fresh Testcontainers database and isn't independently verifiable here.
 *
 * the column used to carry a `CHECK (document_language IN ('de', 'en'))`; it now carries
 * `fk_invoice_document_language` onto the `language` table instead (an unknown code is still
 * rejected, just by a foreign-key violation rather than a CHECK violation).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class DocumentLanguageMigrationIT {

    companion object {
        /**
         * Shared across all test instances, so it really does count up. It replaces
         * `System.nanoTime() % 100000`, which only looked unique: 100 000 possible values, so two
         * calls could land on the same number and fail with `duplicate key value violates unique
         * constraint`. Same fix and same reasoning as
         * [de.littletrotter.application.SenderProfileEditLoadIT]; the time component is dropped
         * outright rather than combined, because it never contributed uniqueness.
         */
        private val uniqueCounter = AtomicInteger(0)
    }

    @Inject
    lateinit var em: EntityManager

    private fun insertSender(): Long =
        (em.createNativeQuery(
            "INSERT INTO sender_profile (name, invoice_prefix, created_at, version) VALUES ('IT Sender', :prefix, now(), 0) RETURNING id"
        ).setParameter("prefix", "L${uniqueCounter.incrementAndGet()}-").singleResult as Number).toLong()

    private fun insertRecipient(): Long =
        (em.createNativeQuery(
            "INSERT INTO recipient (customer_number, name, created_at, version, document_language) VALUES (:num, 'IT Recipient', now(), 0, 'de') RETURNING id"
        ).setParameter("num", "KD-L${uniqueCounter.incrementAndGet()}").singleResult as Number).toLong()

    private fun insertInvoice(reverseCharge: Boolean): Long {
        val senderId = insertSender()
        val recipientId = insertRecipient()
        return (em.createNativeQuery(
            """INSERT INTO invoice (invoice_number, status, sender_profile_id, recipient_id, invoice_date,
                   payment_terms_days, reverse_charge, net_amount, vat_amount, gross_amount, validation_status,
                   type_code, document_language, created_at)
               VALUES (:num, 0, :sender, :recipient, '2026-06-15', 14, :rc, 0.00, 0.00, 0.00, 0, 0, 'de', '2026-06-15T10:00:00Z')
               RETURNING id"""
        ).setParameter("num", "L-${System.nanoTime()}").setParameter("sender", senderId)
            .setParameter("recipient", recipientId).setParameter("rc", reverseCharge)
            .singleResult as Number).toLong()
    }

    @Test
    @Transactional
    fun `document_language is foreign-keyed to language - an unknown code is rejected`() {
        assertThrows(PersistenceException::class.java) {
            val id = insertInvoice(reverseCharge = false)
            em.createNativeQuery("UPDATE invoice SET document_language = 'fr' WHERE id = :id")
                .setParameter("id", id).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `a freshly inserted invoice defaults document_language to de`() {
        val id = (em.createNativeQuery(
            """INSERT INTO invoice (invoice_number, status, sender_profile_id, recipient_id, invoice_date,
                   payment_terms_days, reverse_charge, net_amount, vat_amount, gross_amount, validation_status,
                   type_code, created_at)
               VALUES (:num, 0, :sender, :recipient, '2026-06-15', 14, false, 0.00, 0.00, 0.00, 0, 0, '2026-06-15T10:00:00Z')
               RETURNING id"""
        ).setParameter("num", "LDEF-2026-01-01-${"%09d".format(System.nanoTime() % 1_000_000_000)}").setParameter("sender", insertSender())
            .setParameter("recipient", insertRecipient()).singleResult as Number).toLong()

        val language = em.createNativeQuery("SELECT document_language FROM invoice WHERE id = :id")
            .setParameter("id", id).singleResult as String
        assertEquals("de", language)
    }
}
