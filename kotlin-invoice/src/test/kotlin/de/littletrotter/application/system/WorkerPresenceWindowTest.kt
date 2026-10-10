package de.littletrotter.application.system

import de.littletrotter.shared.time.TimeSource
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The 60s "worker seen" window, unit-tested with a mutable [TimeSource] stub so time can be
 * advanced deterministically without an actual sleep.
 */
class WorkerPresenceWindowTest {

    /** A [TimeSource] whose [instant] is whatever [current] is set to, so a test can advance time
     *  by assigning it directly instead of waiting on the real clock. [WorkerPresenceWindow] only
     *  ever calls [instant]; the other three methods are never exercised here. */
    private class MutableClock(var current: Instant) : TimeSource {
        override fun instant(): Instant = current
        override fun nowUtc(): OffsetDateTime = OffsetDateTime.ofInstant(current, ZoneOffset.UTC)
        override fun today(): LocalDate = LocalDate.ofInstant(current, ZoneOffset.UTC)
        override fun localNow(): LocalDateTime = LocalDateTime.ofInstant(current, ZoneOffset.UTC)
    }

    @Test
    fun `reports connected while the raw signal is active`() {
        val window = WorkerPresenceWindow(MutableClock(Instant.parse("2026-09-18T12:00:00Z")))

        assertTrue(window.observe(active = true))
    }

    @Test
    fun `reports disconnected when the raw signal has never been active`() {
        val window = WorkerPresenceWindow(MutableClock(Instant.parse("2026-09-18T12:00:00Z")))

        assertFalse(window.observe(active = false))
    }

    @Test
    fun `does not flicker to disconnected for a gap shorter than the 60s window`() {
        val clock = MutableClock(Instant.parse("2026-09-18T12:00:00Z"))
        val window = WorkerPresenceWindow(clock)

        assertTrue(window.observe(active = true))
        clock.current = clock.current.plusSeconds(59)

        assertTrue(window.observe(active = false), "a 59s gap between two pulls must still read as connected")
    }

    @Test
    fun `reports connected at exactly the 60s boundary`() {
        val clock = MutableClock(Instant.parse("2026-09-18T12:00:00Z"))
        val window = WorkerPresenceWindow(clock)

        window.observe(active = true)
        clock.current = clock.current.plusSeconds(60)

        assertTrue(window.observe(active = false), "exactly 60s must still count as connected (<=, not <)")
    }

    @Test
    fun `reports disconnected once the gap exceeds the 60s window`() {
        val clock = MutableClock(Instant.parse("2026-09-18T12:00:00Z"))
        val window = WorkerPresenceWindow(clock)

        window.observe(active = true)
        clock.current = clock.current.plusSeconds(61)

        assertFalse(window.observe(active = false))
    }

    @Test
    fun `a later active observation renews the window`() {
        val clock = MutableClock(Instant.parse("2026-09-18T12:00:00Z"))
        val window = WorkerPresenceWindow(clock)

        window.observe(active = true)
        clock.current = clock.current.plusSeconds(65)
        assertFalse(window.observe(active = false), "sanity check: the first window must have already expired")

        window.observe(active = true)
        clock.current = clock.current.plusSeconds(30)
        assertTrue(window.observe(active = false), "a fresh active observation must restart the 60s window")
    }
}
