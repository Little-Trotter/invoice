package de.littletrotter.application.shared

import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * Wires the one [TimeSource] every service, view and entity listener reads "now" from. Production
 * always gets the system clock in UTC with Europe/Berlin as business zone; nothing here depends on
 * the host or container timezone. Application-scoped, so a test can swap the whole application's
 * notion of "now" for one test with `QuarkusMock.installMockForType`.
 */
@ApplicationScoped
class TimeSourceProducer {

    @Produces
    @ApplicationScoped
    fun timeSource(): TimeSource = ClockTimeSource(Clock.systemUTC())
}
