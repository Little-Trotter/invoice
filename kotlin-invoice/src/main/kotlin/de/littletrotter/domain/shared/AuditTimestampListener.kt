package de.littletrotter.domain.shared

import jakarta.inject.Inject
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import de.littletrotter.shared.time.TimeSource

/**
 * Stamps [AuditableEntity]'s created/updated timestamps from the application's [TimeSource].
 * Quarkus runs entity listeners as CDI beans, so the entity itself stays free of any clock and
 * a test that fixes the time source also fixes these stamps. No scope annotation: Quarkus gives
 * entity listeners the dependent scope itself, which keeps the domain free of CDI scopes.
 */
class AuditTimestampListener @Inject constructor(private val timeSource: TimeSource) {

    @PrePersist
    fun beforeInsert(entity: AuditableEntity) = entity.stampPersisted(timeSource.nowUtc())

    @PreUpdate
    fun beforeUpdate(entity: AuditableEntity) = entity.stampUpdated(timeSource.nowUtc())
}
