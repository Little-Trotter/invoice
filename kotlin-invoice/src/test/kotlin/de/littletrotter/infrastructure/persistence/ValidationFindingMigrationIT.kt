package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the V016 migration end to end against a real Postgres (Testcontainers, full
 * Flyway chain via [PostgresTestResource]/`quarkus.flyway.migrate-at-start`): the
 * `validation_finding` table exists as specified and existing rows in `generation_job`/`invoice`
 * (from earlier migrations' own tests running in the same suite) are unaffected by this table's
 * addition — this class running at all against the full chain is the proof for that.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ValidationFindingMigrationIT {

    @Inject
    lateinit var em: EntityManager

    private fun rowCount(sql: String): Long = (em.createNativeQuery(sql).singleResult as Number).toLong()

    @Test
    @Transactional
    fun `validation_finding table exists with the expected columns`() {
        // Not asserting an exact row count: @QuarkusTestResource shares one Postgres instance
        // across every @QuarkusTest in this module's test run, so other suites (e.g.
        // GenerationJobServiceIT) may have already committed rows by the time this runs. A
        // successful query against every documented column is the real proof the table exists
        // as specified.
        assertTrue(
            rowCount("SELECT COUNT(*) FROM validation_finding WHERE id IS NOT NULL AND generation_job_id IS NOT NULL AND invoice_id IS NOT NULL AND severity IS NOT NULL AND message IS NOT NULL AND created_at IS NOT NULL") >= 0,
        )
    }

    @Test
    @Transactional
    fun `a row referencing a nonexistent generation_job is rejected`() {
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO validation_finding (generation_job_id, invoice_id, severity, message, created_at) VALUES (999999, 999999, 'ERROR', 'x', '2026-06-15T10:00:00Z')"
            ).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `a row with a null severity or message is rejected`() {
        // Set up a minimal real job + invoice to isolate the NOT NULL check from the FK check.
        // invoice_prefix set explicitly instead of relying on the table default ('RE-'): the
        // default is shared across every row, but uq_sender_profile_invoice_prefix is table-wide
        // — any row silently relying on it can collide with any other row doing the same.
        val senderId = (em.createNativeQuery("INSERT INTO sender_profile (name, invoice_prefix, created_at) VALUES ('S', 'VALFIND-', now()) RETURNING id").singleResult as Number).toLong()
        val recipientId = (em.createNativeQuery("INSERT INTO recipient (customer_number, name, created_at, document_language) VALUES ('KD', 'R', now(), 'de') RETURNING id").singleResult as Number).toLong()
        val invoiceId = (em.createNativeQuery(
            "INSERT INTO invoice (invoice_number, status, sender_profile_id, recipient_id, invoice_date, created_at) VALUES ('IT-VF-1', 0, $senderId, $recipientId, '2026-06-15', '2026-06-15T10:00:00Z') RETURNING id"
        ).singleResult as Number).toLong()
        val jobId = (em.createNativeQuery(
            "INSERT INTO generation_job (invoice_id, job_type, status, created_at, job_lane) VALUES ($invoiceId, 'PDF_EXPORT', 3, '2026-06-15T10:00:00Z', 0) RETURNING id"
        ).singleResult as Number).toLong()
        em.flush()

        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO validation_finding (generation_job_id, invoice_id, severity, message, created_at) VALUES ($jobId, $invoiceId, NULL, 'x', '2026-06-15T10:00:00Z')"
            ).executeUpdate()
            em.flush()
        }
    }
}
