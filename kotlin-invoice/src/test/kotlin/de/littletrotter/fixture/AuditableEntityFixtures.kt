package de.littletrotter.fixture

import de.littletrotter.domain.shared.AuditableEntity
import java.time.OffsetDateTime

/**
 * Test-only write access to the audit stamps of any [AuditableEntity]. Production sets them only
 * through the persistence callbacks; tests arrange rows as if those callbacks had already run.
 */
fun <T : AuditableEntity> T.editAudit(block: AuditStampEditor.() -> Unit): T = also { AuditStampEditor(it).block() }

/** Property-style access to the four audit stamps of an [AuditableEntity]. */
class AuditStampEditor internal constructor(internal val entity: AuditableEntity) {
    var createdAt: OffsetDateTime?
        get() = entity.createdAt
        set(value) { PersistenceEmulation.assign(entity, "createdAt", value) }

    var updatedAt: OffsetDateTime?
        get() = entity.updatedAt
        set(value) { PersistenceEmulation.assign(entity, "updatedAt", value) }

    var createdBy: String?
        get() = entity.createdBy
        set(value) { PersistenceEmulation.assign(entity, "createdBy", value) }

    var updatedBy: String?
        get() = entity.updatedBy
        set(value) { PersistenceEmulation.assign(entity, "updatedBy", value) }
}
