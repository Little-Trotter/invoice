package de.littletrotter.arch

import com.tngtech.archunit.core.domain.JavaClasses
import de.littletrotter.architecture.rules.ProductionClasses
import de.littletrotter.architecture.rules.TimeRules
import de.littletrotter.xpdf.TimeSourceProducer
import org.junit.jupiter.api.Test

/**
 * "Now" in kotlin-xpdf comes from the injected time source; only its own CDI producer reads the
 * system clock to build it.
 *
 * The rules, with their negative and positive checks, live in [TimeRules]; this gate names the
 * packages it covers and the classes allowed to read the system clock.
 */
class TimeSourceArchitectureTest {

    private val productionClasses: JavaClasses = ProductionClasses.of("de.littletrotter.xpdf")

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
        val CLOCK_READERS: Set<String> = setOf(TimeSourceProducer::class.java.name)
    }
}
