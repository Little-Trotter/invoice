package de.littletrotter.xpdf

import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * Wires the one [TimeSource] this module's beans read "now" from — the same system-UTC clock
 * kotlin-invoice's own producer wires, kept as its own bean here since kotlin-xpdf is a
 * standalone deployable with no CDI dependency on kotlin-invoice. Application-scoped, so a test
 * can swap the whole worker's notion of "now" for one test with `QuarkusMock.installMockForType`.
 */
@ApplicationScoped
class TimeSourceProducer {

    @Produces
    @ApplicationScoped
    fun timeSource(): TimeSource = ClockTimeSource(Clock.systemUTC())
}
