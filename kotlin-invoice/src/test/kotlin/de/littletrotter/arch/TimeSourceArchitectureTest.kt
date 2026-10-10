package de.littletrotter.arch

import com.tngtech.archunit.core.domain.JavaClasses
import de.littletrotter.application.shared.TimeSourceProducer
import de.littletrotter.architecture.rules.ProductionClasses
import de.littletrotter.architecture.rules.TimeRules
import de.littletrotter.shared.time.ClockTimeSource
import org.junit.jupiter.api.Test

/**
 * "Now" has exactly one source, the injected time source: only the time source reads the clock and
 * only the producer picks the system one.
 *
 * The rules, with their negative and positive checks, live in [TimeRules]; this gate names the
 * packages it covers and the classes allowed to read the system clock.
 */
class TimeSourceArchitectureTest {

    private val productionClasses: JavaClasses = ProductionClasses.of(
        "de.littletrotter.domain",
        "de.littletrotter.application",
        "de.littletrotter.infrastructure",
        "de.littletrotter.ui",
        "de.littletrotter.spi",
    )

    @Test
    fun `only the time source reads the clock`() {
        TimeRules.clockReads(CLOCK_READERS).check(productionClasses)
    }

    @Test
    fun `no production class builds its own or a frozen time source`() {
        TimeRules.ownTimeSource(CLOCK_READERS).check(productionClasses)
    }

    @Test
    fun `no field lets Hibernate stamp it from the JVM clock`() {
        TimeRules.hibernateStamps().check(productionClasses)
    }

    @Test
    fun `calendar days are read in German time only`() {
        TimeRules.businessDay().check(productionClasses)
    }

    private companion object {
        val CLOCK_READERS: Set<String> = setOf(ClockTimeSource::class.java.name, TimeSourceProducer::class.java.name)
    }
}
