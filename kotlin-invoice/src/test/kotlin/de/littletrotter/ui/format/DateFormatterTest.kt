package de.littletrotter.ui.format

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * R4 — [DateFormatter] had no test file, same gap [MoneyFormatterTest] closed for
 * [MoneyFormatter]: German format, `null` handling, and the [de.littletrotter.shared.GermanTime]
 * zone rule for an [OffsetDateTime] were all unexercised.
 */
class DateFormatterTest {

    @Test
    fun `a LocalDate formats in German day-month-year order`() {
        assertEquals("07.09.2026", DateFormatter.format(LocalDate.of(2026, 9, 7)))
    }

    @Test
    fun `a null LocalDate formats as the em dash placeholder`() {
        assertEquals("—", DateFormatter.format(null as LocalDate?))
    }

    @Test
    fun `a LocalDateTime formats in German day-month-year order with no zone conversion`() {
        // LocalDateTime carries no offset — it is formatted exactly as written, on the
        // assumption every producer already captured it in German wall time (e.g.
        // LocalDateTime.now(GermanTime.ZONE)).
        assertEquals("07.09.2026 14:30", DateFormatter.format(LocalDateTime.of(2026, 9, 7, 14, 30)))
    }

    @Test
    fun `a null LocalDateTime formats as the em dash placeholder`() {
        assertEquals("—", DateFormatter.format(null as LocalDateTime?))
    }

    @Test
    fun `an OffsetDateTime is converted to German wall time in CEST (summer)`() {
        val utc = OffsetDateTime.of(2026, 9, 4, 10, 0, 0, 0, ZoneOffset.UTC)

        assertEquals("04.09.2026 12:00", DateFormatter.format(utc))
    }

    @Test
    fun `an OffsetDateTime is converted to German wall time in CET (winter)`() {
        val utc = OffsetDateTime.of(2026, 1, 15, 9, 30, 0, 0, ZoneOffset.UTC)

        assertEquals("15.01.2026 10:30", DateFormatter.format(utc))
    }

    @Test
    fun `an OffsetDateTime already carrying the German offset formats unchanged`() {
        val berlinNoon = OffsetDateTime.of(2026, 9, 4, 14, 30, 0, 0, ZoneOffset.ofHours(2))

        assertEquals("04.09.2026 14:30", DateFormatter.format(berlinNoon))
    }

    @Test
    fun `a null OffsetDateTime formats as the em dash placeholder`() {
        assertEquals("—", DateFormatter.format(null as OffsetDateTime?))
    }

    // ── formatWithWeekday (per-diem overview ergonomics follow-up, PO 14.09.2026) ──────────────

    @Test
    fun `a LocalDate formats with its German weekday abbreviation, no trailing period`() {
        // Tuesday -- the format-context "EEE" pattern letter would render "Di." (with a period)
        // in German; the stand-alone "ccc" letter used here renders "Di".
        assertEquals("Di, 15.09.2026", DateFormatter.formatWithWeekday(LocalDate.of(2026, 9, 15)))
    }

    @Test
    fun `a LocalDate formats with its English weekday abbreviation`() {
        assertEquals("Tue, 15.09.2026", DateFormatter.formatWithWeekday(LocalDate.of(2026, 9, 15), locale = Locale.ENGLISH))
    }

    @Test
    fun `a null LocalDate formats as the em dash placeholder with formatWithWeekday too`() {
        assertEquals("—", DateFormatter.formatWithWeekday(null))
    }
}
