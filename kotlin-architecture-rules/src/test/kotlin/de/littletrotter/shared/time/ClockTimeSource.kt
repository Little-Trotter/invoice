package de.littletrotter.shared.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Stand-in carrying the real time source's name, so the rules are tested against exactly the
 * name they match in the modules. The rules module deliberately depends on no application module.
 */
class ClockTimeSource(private val clock: Clock) {
    fun now(): Instant = Instant.now(clock)

    companion object {
        fun fixedAt(instant: Instant): ClockTimeSource = ClockTimeSource(Clock.fixed(instant, ZoneOffset.UTC))
    }
}
