package de.littletrotter.domain.shared

import jakarta.inject.Inject
import jakarta.persistence.PrePersist
import java.time.OffsetDateTime
import java.time.ZoneOffset
import de.littletrotter.shared.time.TimeSource

/**
 * An entity whose creation moment is part of its record (a job's creation, a message's occurrence,
 * a receipt's reading). Its creator usually sets the moment explicitly; when it does not, the
 * [CreationStampListener] fills it in on the first insert, so no record is ever stored with a
 * made-up date.
 */
fun interface StampedOnCreation {

    /** Sets the creation moment to [now] unless the creator already set one. */
    fun stampCreationIfUnset(now: OffsetDateTime)

    companion object {
        /** The value of a creation moment nobody has set yet. A JPA entity needs a no-argument
         *  constructor, and a domain class may not read the clock, so the field starts here. */
        val UNSET: OffsetDateTime = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
    }
}

/**
 * Stamps a [StampedOnCreation] entity from the application's [TimeSource] right before its first
 * insert. Quarkus runs entity listeners as dependent CDI beans, so the domain stays clock-free.
 */
class CreationStampListener @Inject constructor(private val timeSource: TimeSource) {

    @PrePersist
    fun beforeInsert(entity: Any) {
        (entity as? StampedOnCreation)?.stampCreationIfUnset(timeSource.nowUtc())
    }
}
