package de.littletrotter.fixture

import de.littletrotter.domain.reference.ValidationSetting
import java.time.OffsetDateTime

/**
 * Test-only write access to a [ValidationSetting] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun ValidationSetting.edit(block: ValidationSettingEditor.() -> Unit): ValidationSetting = also { ValidationSettingEditor(it).block() }

/** Property-style access to every persisted field of a [ValidationSetting]. */
class ValidationSettingEditor internal constructor(internal val entity: ValidationSetting) {
    var capabilityId: String
        get() = entity.capabilityId
        set(value) { PersistenceEmulation.assign(entity, "capabilityId", value) }

    var enabled: Boolean
        get() = entity.enabled
        set(value) { PersistenceEmulation.assign(entity, "enabled", value) }

    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assign(entity, "version", value) }

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
