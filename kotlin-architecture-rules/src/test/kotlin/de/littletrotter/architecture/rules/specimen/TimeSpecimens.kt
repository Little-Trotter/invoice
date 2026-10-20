package de.littletrotter.architecture.rules.specimen

import de.littletrotter.shared.time.ClockTimeSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Takes today from the process zone. */
object ViolatingTodayReader {
    fun today(): LocalDate = LocalDate.now()
}

/** Passing a clock does not make it acceptable: the business zone belongs to the time source. */
object ViolatingSystemClockReader {
    fun now(): OffsetDateTime = OffsetDateTime.now(Clock.systemUTC())
}

/** Epoch milliseconds are "now" too, just without a type that says so. */
object ViolatingMillisReader {
    fun deadline(timeoutMs: Long): Long = System.currentTimeMillis() + timeoutMs
}

/** A monotonic counter measures elapsed time the time source cannot step through in a test. */
object ViolatingNanoReader {
    fun started(): Long = System.nanoTime()
}

/** Falls back to a frozen time source when nobody passes one in. */
class ViolatingFrozenDefault(
    val time: ClockTimeSource = ClockTimeSource.fixedAt(Instant.EPOCH),
)

/** Builds its own time source around a clock it was handed. */
class ViolatingOwnTimeSourceBuilder(clock: Clock) {
    val time = ClockTimeSource(clock)
}

/** A clock frozen at a call site is a time source of its own. */
object ViolatingFixedClock {
    fun frozen(): Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
}

class ViolatingCreationTimestamp {
    @org.hibernate.annotations.CreationTimestamp
    var createdAt: OffsetDateTime? = null
}

class ViolatingUpdateTimestamp {
    @org.hibernate.annotations.UpdateTimestamp
    var updatedAt: OffsetDateTime? = null
}

class ViolatingCurrentTimestamp {
    @org.hibernate.annotations.CurrentTimestamp
    var stampedAt: OffsetDateTime? = null
}

/** Takes the calendar day from the moment's own offset, which is UTC for every stored timestamp. */
class ViolatingOffsetDayReader {
    fun day(at: OffsetDateTime): LocalDate = at.toLocalDate()
}

/** Reads a calendar day in the process zone, which depends on the container, not on the product. */
class ViolatingProcessZoneDayReader {
    fun day(at: Instant): LocalDate = at.atZone(ZoneId.systemDefault()).toLocalDate()
}

/**
 * Uses the same types and owners as the violations without reading the clock or a wrong day:
 * every rule must leave it alone.
 */
class PermittedTimeUser(private val clock: Clock) {
    @Transient
    var plainField: OffsetDateTime? = null

    fun moment(): Instant = clock.instant()
    fun day(year: Int): LocalDate = LocalDate.of(year, 1, 1)
    fun instant(at: OffsetDateTime): Instant = at.toInstant()
    fun zone(): ZoneId = ZoneId.of("Europe/Berlin")
    fun germanDay(at: ZonedDateTime): LocalDate = at.toLocalDate()
    fun clockZone(): ZoneId = clock.zone
    fun utc(): Clock = clock.withZone(ZoneOffset.UTC)
    fun separator(): String = System.lineSeparator()
}

/** Stands for a module's producer: allowed to read the system clock, and so are its nested classes. */
object AllowedProducer {
    fun produce(): ClockTimeSource = ClockTimeSource(Clock.systemUTC())

    object Nested {
        fun now(): Instant = Instant.now()
        fun day(at: OffsetDateTime): LocalDate = at.toLocalDate()
    }
}

/** Shares the producer's name as a prefix but is not nested in it, so it stays covered. */
object AllowedProducerLookalike {
    fun now(): Instant = Instant.now()
}
