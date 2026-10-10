package de.littletrotter.shared.time

import de.littletrotter.shared.GermanTime
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The application's only source of "now".
 *
 * Two readings of the same moment, kept apart on purpose:
 * - **Points in time** ([instant], [nowUtc]) are UTC. Everything that is stored, compared or
 *   measured uses them, so the result never depends on the zone the process runs in.
 * - **Calendar dates** ([today], [localNow]) are those of the business zone, Europe/Berlin —
 *   where the user works. The invoice date and the year a number range counts in are calendar
 *   dates: an invoice sent at 00:30 on 1 January in Berlin belongs to the new year, although it is
 *   still 31 December in UTC.
 *
 * An interface so tests can replace it — with a mock, or with [ClockTimeSource] over a fixed
 * clock — and walk the application across a year boundary deterministically.
 */
interface TimeSource {

    /** The current point in time. */
    fun instant(): Instant

    /** The current point in time with UTC offset — the form every timestamp is persisted in. */
    fun nowUtc(): OffsetDateTime

    /** Today's date in the business zone. */
    fun today(): LocalDate

    /** The business-zone wall-clock time — only for values the user reads as local time. */
    fun localNow(): LocalDateTime
}

/** [TimeSource] over a [Clock]; production uses the system clock, tests a fixed or adjustable one. */
class ClockTimeSource(
    private val clock: Clock,
    private val businessZone: ZoneId = GermanTime.ZONE,
) : TimeSource {

    override fun instant(): Instant = clock.instant()

    override fun nowUtc(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    override fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), businessZone)

    override fun localNow(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), businessZone)

    companion object {
        /** Frozen at [instant] — for tests that need a known "now". */
        fun fixedAt(instant: Instant): ClockTimeSource = ClockTimeSource(Clock.fixed(instant, ZoneOffset.UTC))

        /** Frozen at the given business-zone wall-clock time, e.g. 31 December 23:59:59 in Berlin. */
        fun fixedAtLocal(localDateTime: LocalDateTime): ClockTimeSource =
            fixedAt(localDateTime.atZone(GermanTime.ZONE).toInstant())
    }
}
