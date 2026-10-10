package de.littletrotter.shared.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

class ClockTimeSourceTest {

    @Test
    fun `just after midnight on new year's day in Berlin it is already the new year, while UTC is still in the old one`() {
        val time = ClockTimeSource.fixedAtLocal(LocalDateTime.of(2027, 1, 1, 0, 30))

        assertEquals(LocalDate.of(2027, 1, 1), time.today())
        assertEquals(LocalDateTime.of(2027, 1, 1, 0, 30), time.localNow())
        assertEquals(OffsetDateTime.parse("2026-12-31T23:30:00Z"), time.nowUtc())
        assertEquals(Instant.parse("2026-12-31T23:30:00Z"), time.instant())
    }

    @Test
    fun `the last second of the year in Berlin still belongs to the old year`() {
        val time = ClockTimeSource.fixedAtLocal(LocalDateTime.of(2026, 12, 31, 23, 59, 59))

        assertEquals(LocalDate.of(2026, 12, 31), time.today())
        assertEquals(OffsetDateTime.parse("2026-12-31T22:59:59Z"), time.nowUtc())
    }

    @Test
    fun `in summer the business zone is two hours ahead of UTC`() {
        val time = ClockTimeSource.fixedAt(Instant.parse("2026-07-31T22:30:00Z"))

        assertEquals(LocalDate.of(2026, 8, 1), time.today())
        assertEquals(LocalDateTime.of(2026, 8, 1, 0, 30), time.localNow())
    }

    @Test
    fun `timestamps always carry the UTC offset`() {
        val time = ClockTimeSource.fixedAt(Instant.parse("2026-03-01T12:00:00Z"))

        assertEquals(0, time.nowUtc().offset.totalSeconds)
    }
}
