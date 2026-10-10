package de.littletrotter.domain.shared

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.MappedSuperclass
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

@MappedSuperclass
@EntityListeners(AuditTimestampListener::class)
abstract class AuditableEntity {

    @Column(name = "created_at", updatable = false)
    var createdAt: OffsetDateTime? = null

    @Column(name = "updated_at")
    var updatedAt: OffsetDateTime? = null

    /** ST-B3 — `@Size` mirrors `created_by`/`updated_by`'s `VARCHAR(255)` shape, shared by every
     *  table this mapped superclass contributes to (`V1__baseline_schema.sql`); one bound here
     *  covers all of them instead of repeating it per entity. */
    @field:Size(max = 255)
    @Column(name = "created_by", updatable = false, length = 255)
    var createdBy: String? = null

    @field:Size(max = 255)
    @Column(name = "updated_by", length = 255)
    var updatedBy: String? = null

    fun stampCreatedBy(actor: String) {
        createdBy = actor
        updatedBy = actor
    }

    fun stampUpdatedBy(actor: String) {
        updatedBy = actor
    }

    /** Called by [AuditTimestampListener] right before the first insert. */
    fun stampPersisted(now: OffsetDateTime) {
        createdAt = now
        updatedAt = now
        if (createdBy.isNullOrBlank()) createdBy = "system"
        if (updatedBy.isNullOrBlank()) updatedBy = createdBy
    }

    /** Called by [AuditTimestampListener] right before every update. */
    fun stampUpdated(now: OffsetDateTime) {
        updatedAt = now
        if (updatedBy.isNullOrBlank()) {
            updatedBy = createdBy ?: "system"
        }
    }
}

/** Consolidated: first-vs-subsequent-save distinction belongs to entity, not caller. */
fun AuditableEntity.stampForPersist(actor: String) {
    if (createdAt == null) stampCreatedBy(actor) else stampUpdatedBy(actor)
}
