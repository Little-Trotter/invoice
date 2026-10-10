package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

class GermanTimeTest {

    @Test
    fun `ZONE is Europe Berlin`() {
        assertEquals("Europe/Berlin", GermanTime.ZONE.id)
    }

    @Test
    fun `half past eleven UTC in winter is already the next German day`() {
        assertEquals(LocalDate.parse("2026-01-02"), GermanTime.dayOf(OffsetDateTime.parse("2026-01-01T23:30:00Z")))
    }

    @Test
    fun `half past ten UTC in summer is already the next German day`() {
        assertEquals(LocalDate.parse("2026-07-02"), GermanTime.dayOf(OffsetDateTime.parse("2026-07-01T22:30:00Z")))
    }

    @Test
    fun `the day turns exactly at German midnight on the day summer time ends`() {
        // 2026-10-25 ends summer time: German midnight before that day is still 22:00 UTC of the 24th.
        assertEquals(LocalDate.parse("2026-10-24"), GermanTime.dayOf(Instant.parse("2026-10-24T21:59:59Z")))
        assertEquals(LocalDate.parse("2026-10-25"), GermanTime.dayOf(Instant.parse("2026-10-24T22:00:00Z")))
    }

    @Test
    fun `the offset a moment carries does not change its German day`() {
        val utc = OffsetDateTime.parse("2026-10-07T22:30:00Z")
        val berlin = OffsetDateTime.parse("2026-10-08T00:30:00+02:00")
        assertEquals(GermanTime.dayOf(utc), GermanTime.dayOf(berlin))
        assertEquals(LocalDate.parse("2026-10-08"), GermanTime.dayOf(utc.toInstant()))
    }
}
