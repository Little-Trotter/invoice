package de.littletrotter.test

import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.time.TimeSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Shared fixed "now" for tests that construct a class needing a [TimeSource]/date purely as
 * incidental wiring — they exercise unrelated behavior and never assert on the exact stamp (the
 * many `object : GenerationJobResultHandler(...)` test doubles, dialog/view Karibu constructions,
 * etc.). A test that DOES care about the exact date (a year-boundary, EU-membership or
 * GB-exclusion assertion) builds its own [ClockTimeSource.fixedAt] instead of reusing this.
 */
val FIXED_INSTANT: Instant = Instant.parse("2026-06-15T10:00:00Z")
val FIXED_TIME_SOURCE: TimeSource = ClockTimeSource.fixedAt(FIXED_INSTANT)
val FIXED_TODAY: LocalDate = FIXED_TIME_SOURCE.today()
val FIXED_LOCAL_NOW: LocalDateTime = FIXED_TIME_SOURCE.localNow()
