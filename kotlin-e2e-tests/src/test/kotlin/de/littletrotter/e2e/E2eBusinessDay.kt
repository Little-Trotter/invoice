package de.littletrotter.e2e

import de.littletrotter.shared.time.ClockTimeSource
import java.time.Clock
import java.time.LocalDate

/**
 * Today's date as the application reads it: the business-zone calendar day, not the day of the
 * JVM's default zone. Between midnight and 02:00 in Berlin the two differ, and an offer or order
 * date taken from the default zone then lies a day before the date the application stamps itself.
 */
object E2eBusinessDay {
    fun today(): LocalDate = ClockTimeSource(Clock.systemUTC()).today()
}
