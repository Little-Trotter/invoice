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
 * [PostgresNumberJournalRepository.mapRow]'s `toOffsetDateTime` decodes whichever type
 * the JDBC driver hands back for `occurred_at` (`TIMESTAMPTZ`). Which one that is is fixed for a
 * given Testcontainers/driver setup, so `NumberJournalIT`'s real reads only ever exercise one
 * branch; this drives all three accepted types plus the defensive `else` directly, the same style
 * as `PostgresDatabaseMaintenanceServiceTest`.
 */
class PostgresNumberJournalRepositoryToOffsetDateTimeTest {

    private val repository = PostgresNumberJournalRepository(mock())

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

        assertEquals("Unerwarteter Zeittyp aus number_journal.occurred_at: class java.lang.String", ex.message)
    }
}
