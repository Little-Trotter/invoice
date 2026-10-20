package de.littletrotter.architecture.rules

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import de.littletrotter.architecture.rules.specimen.AllowedProducer
import de.littletrotter.architecture.rules.specimen.PermittedTimeUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Each rule must reject every forbidden form (negative specimens) and leave the look-alike calls
 * alone (positive specimens); otherwise a module's green gate would mean nothing.
 */
class TimeRulesTest {

    private val specimens: JavaClasses = ClassFileImporter().importPackages("de.littletrotter.architecture.rules.specimen")
    private val producer = AllowedProducer::class.java.name

    private fun violators(rule: ArchRule): List<String> = rule.evaluate(specimens).failureReport.details

    /** Report lines name members as `<package.Class.member>`, so the trailing dot keeps
     *  `AllowedProducer` apart from `AllowedProducerLookalike` and `AllowedProducer${'$'}Nested`. */
    private fun List<String>.mentions(simpleName: String) = any { it.contains("specimen.$simpleName.") }

    @Test
    fun `clock reads past the time source are rejected`() {
        val report = violators(TimeRules.clockReads(setOf(producer)))
        listOf("ViolatingTodayReader", "ViolatingSystemClockReader", "ViolatingMillisReader", "ViolatingNanoReader", "AllowedProducerLookalike")
            .forEach { assertTrue(report.mentions(it), "$it must be rejected") }
        assertFalse(report.mentions("PermittedTimeUser"), "a clock passed in is not a clock read")
        assertFalse(report.mentions("AllowedProducer"), "the allowed producer reads the clock")
        assertFalse(report.any { it.contains("AllowedProducer\$Nested") }, "nested classes belong to the allowed producer")
    }

    @Test
    fun `without an allowed class even the producer is rejected`() {
        assertTrue(violators(TimeRules.clockReads(emptySet())).mentions("AllowedProducer"))
    }

    @Test
    fun `own and frozen time sources are rejected`() {
        val report = violators(TimeRules.ownTimeSource(setOf(producer)))
        listOf("ViolatingFrozenDefault", "ViolatingOwnTimeSourceBuilder", "ViolatingFixedClock")
            .forEach { assertTrue(report.mentions(it), "$it must be rejected") }
        assertFalse(report.mentions("PermittedTimeUser"), "changing the zone of a given clock builds no time source")
        assertFalse(report.mentions("AllowedProducer"), "the allowed producer builds the one time source")
    }

    @Test
    fun `Hibernate generated timestamps are rejected`() {
        val report = violators(TimeRules.hibernateStamps())
        listOf("ViolatingCreationTimestamp", "ViolatingUpdateTimestamp", "ViolatingCurrentTimestamp")
            .forEach { assertTrue(report.mentions(it), "$it must be rejected") }
        assertFalse(report.mentions("PermittedTimeUser"), "other field annotations are fine")
    }

    @Test
    fun `days read from UTC or the process zone are rejected`() {
        val report = violators(TimeRules.businessDay(setOf(producer)))
        listOf("ViolatingOffsetDayReader", "ViolatingProcessZoneDayReader")
            .forEach { assertTrue(report.mentions(it), "$it must be rejected") }
        assertFalse(report.mentions("PermittedTimeUser"), "a zoned date's day and a named zone are fine")
        assertFalse(report.any { it.contains("AllowedProducer\$Nested") }, "allowed classes may read a day directly")
    }

    @Test
    fun `by default no class may read a day outside German time`() {
        assertTrue(violators(TimeRules.businessDay()).any { it.contains("AllowedProducer\$Nested") })
    }

    @Test
    fun `the positive specimen passes every rule`() {
        val permitted = ClassFileImporter().importClasses(PermittedTimeUser::class.java)
        listOf(
            TimeRules.clockReads(emptySet()),
            TimeRules.ownTimeSource(emptySet()),
            TimeRules.hibernateStamps(),
            TimeRules.businessDay(),
        ).forEach { rule -> assertEquals(emptyList<String>(), rule.evaluate(permitted).failureReport.details, rule.description) }
    }
}
