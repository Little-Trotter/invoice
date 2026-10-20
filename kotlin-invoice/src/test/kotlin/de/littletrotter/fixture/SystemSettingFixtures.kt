package de.littletrotter.fixture

import de.littletrotter.domain.settings.SystemSetting
import java.time.OffsetDateTime

/**
 * Test-only write access to a [SystemSetting] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun SystemSetting.edit(block: SystemSettingEditor.() -> Unit): SystemSetting = also { SystemSettingEditor(it).block() }

/** Property-style access to every persisted field of a [SystemSetting]. */
class SystemSettingEditor internal constructor(internal val entity: SystemSetting) {
    var key: String
        get() = entity.key
        set(value) { PersistenceEmulation.assign(entity, "key", value) }

    var value: String
        get() = entity.value
        set(value) { PersistenceEmulation.assign(entity, "value", value) }

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
