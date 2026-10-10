package de.littletrotter.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * [PostgresInvoiceAuditJournalRepository.toEntry]/`toGroup`'s `toOffsetDateTime` decodes
 * whichever type the JDBC driver hands back for `occurred_at` (`TIMESTAMPTZ`). Which one that is
 * is fixed for a given Testcontainers/driver setup, so `PostgresInvoiceAuditJournalRepositoryIT`'s
 * real reads only ever exercise one branch; this drives all three accepted types plus the
 * defensive `else` directly — the same pattern as
 * [PostgresNumberJournalRepositoryToOffsetDateTimeTest].
 */
class PostgresInvoiceAuditJournalRepositoryToOffsetDateTimeTest {

    private val repository = PostgresInvoiceAuditJournalRepository(mock())

    @Test
    fun `happy path - an OffsetDateTime cell is returned unchanged`() {
        val value = OffsetDateTime.parse("2026-01-01T08:00:00Z")

        assertEquals(value, repository.toOffsetDateTime(value))
    }

    @Test
    fun `boundary - an Instant cell is converted to UTC`() {
        val value = Instant.parse("2026-01-02T09:00:00Z")

        assertEquals(value.atOffset(ZoneOffset.UTC), repository.toOffsetDateTime(value))
    }

    @Test
    fun `boundary - a java-sql-Timestamp cell is converted to UTC`() {
        val value = Timestamp.from(Instant.parse("2026-01-03T10:00:00Z"))

        assertEquals(value.toInstant().atOffset(ZoneOffset.UTC), repository.toOffsetDateTime(value))
    }

    @Test
    fun `boundary - an unexpected cell type fails loudly instead of silently defaulting`() {
        val ex = assertThrows(IllegalStateException::class.java) { repository.toOffsetDateTime("not-a-timestamp") }

        assertEquals("Unexpected time type from invoice_audit_journal.occurred_at: class java.lang.String", ex.message)
    }

    @Test
    fun `boundary - a null cell also fails loudly, naming null rather than throwing an NPE on javaClass`() {
        val ex = assertThrows(IllegalStateException::class.java) { repository.toOffsetDateTime(null) }

        assertEquals("Unexpected time type from invoice_audit_journal.occurred_at: null", ex.message)
    }
}
