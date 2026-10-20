package de.littletrotter.architecture.rules

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaAnnotation
import com.tngtech.archunit.core.domain.JavaCall
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields

/**
 * "Now" has exactly one source, the injected time source, and a business day is the German day.
 *
 * A direct `LocalDate.now()` silently takes the date from whatever zone the process runs in, and
 * no test can move it across a year boundary. So no production class may call any `now(...)`
 * overload of a `java.time` type or ask for a system clock, with or without a zone or clock
 * argument: a zone passed at the call site is a second, competing notion of the business zone.
 * Epoch milliseconds, the nano counter and Hibernate's generated timestamps are the same clock by
 * other names.
 *
 * Every module checks its own production classes against these rules and names the few classes
 * allowed to do otherwise (its producer of the system time source, the time source itself). The
 * allowed classes are passed by fully qualified name; nested classes of an allowed class are
 * allowed with it.
 */
object TimeRules {

    /** The one time source implementation; only it reads a clock and builds fixed ones. */
    const val CLOCK_TIME_SOURCE = "de.littletrotter.shared.time.ClockTimeSource"

    private val timeTypes = setOf(
        "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime",
        "java.time.OffsetDateTime", "java.time.ZonedDateTime", "java.time.Year", "java.time.YearMonth",
    )
    private const val CLOCK = "java.time.Clock"
    private val systemClocks = setOf("systemUTC", "systemDefaultZone", "system")
    private val derivedClocks = setOf("fixed", "offset", "tick")
    private val systemCounters = setOf("currentTimeMillis", "nanoTime")
    private val hibernateStampAnnotations = setOf(
        "org.hibernate.annotations.CreationTimestamp",
        "org.hibernate.annotations.UpdateTimestamp",
        "org.hibernate.annotations.CurrentTimestamp",
    )

    private val readsTheClock = object : DescribedPredicate<JavaMethodCall>("reads the current time past the TimeSource") {
        override fun test(call: JavaMethodCall): Boolean {
            val owner = call.target.owner.name
            val name = call.target.name
            return (owner in timeTypes && name == "now") ||
                (owner == CLOCK && name in systemClocks) ||
                (owner == "java.lang.System" && name in systemCounters)
        }
    }

    /** A frozen time source outside tests is a bug waiting for its date: a dialog built with a
     *  fixed default would show the same "today" forever. */
    private val freezesTheClock = object : DescribedPredicate<JavaCall<*>>("creates its own time source or a fixed clock") {
        override fun test(call: JavaCall<*>): Boolean {
            val owner = call.target.owner.name
            return owner == CLOCK_TIME_SOURCE ||
                owner == $$"$$CLOCK_TIME_SOURCE$Companion" ||
                (owner == CLOCK && call.target.name in derivedClocks)
        }
    }

    /** Stored moments are UTC and the container zone is arbitrary, so the offset's own day and the
     *  process zone's day are both the wrong day. */
    private val readsDayOutsideGermanTime = object : DescribedPredicate<JavaMethodCall>("reads a calendar day outside GermanTime") {
        override fun test(call: JavaMethodCall): Boolean {
            val owner = call.target.owner.name
            val name = call.target.name
            return (owner == "java.time.OffsetDateTime" && name == "toLocalDate") ||
                (owner == "java.time.ZoneId" && name == "systemDefault")
        }
    }

    private val hibernateStamp = object : DescribedPredicate<JavaAnnotation<*>>("a Hibernate generated timestamp") {
        override fun test(annotation: JavaAnnotation<*>): Boolean = annotation.rawType.name in hibernateStampAnnotations
    }

    /** Only the [allowed] classes (typically the time source and the producer that hands out the
     *  system one) may read the clock. */
    fun clockReads(allowed: Set<String>): ArchRule = noClasses()
        .that(areNoneOf(allowed))
        .should().callMethodWhere(readsTheClock)
        .because("the business date and every timestamp come from the injected TimeSource")

    /** Production code receives the one time source; only the [allowed] classes build one. */
    fun ownTimeSource(allowed: Set<String>): ArchRule = noClasses()
        .that(areNoneOf(allowed))
        .should().callCodeUnitWhere(freezesTheClock)
        .because("production code receives the one TimeSource by injection or as a parameter, never a default of its own")

    /** Hibernate's generated timestamps read the JVM clock on flush; created/updated stamps come
     *  from the time source instead. */
    fun hibernateStamps(): ArchRule = noFields()
        .should().beAnnotatedWith(hibernateStamp)
        .because("created/updated stamps come from the TimeSource, not from Hibernate reading the JVM clock")

    /** Calendar days are read in German time; only the [allowed] classes may take a day another way. */
    fun businessDay(allowed: Set<String> = emptySet()): ArchRule = noClasses()
        .that(areNoneOf(allowed))
        .should().callMethodWhere(readsDayOutsideGermanTime)
        .because("stored moments are UTC and the container zone is arbitrary; a business day is the German day, read via GermanTime.dayOf")

    private fun areNoneOf(allowed: Set<String>) = object : DescribedPredicate<JavaClass>("are none of $allowed") {
        override fun test(javaClass: JavaClass): Boolean {
            val name = javaClass.name
            return allowed.none { name == it || name.startsWith("$it$") }
        }
    }
}
