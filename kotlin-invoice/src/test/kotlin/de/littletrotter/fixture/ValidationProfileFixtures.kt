package de.littletrotter.fixture

import de.littletrotter.domain.compliance.ValidationProfile
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Test-only write access to a [ValidationProfile] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun ValidationProfile.edit(block: ValidationProfileEditor.() -> Unit): ValidationProfile = also { ValidationProfileEditor(it).block() }

/** Property-style access to every persisted field of a [ValidationProfile]. */
class ValidationProfileEditor internal constructor(internal val entity: ValidationProfile) {
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assign(entity, "id", value) }

    var profileId: String
        get() = entity.profileId
        set(value) { PersistenceEmulation.assign(entity, "profileId", value) }

    var specVersion: String
        get() = entity.specVersion
        set(value) { PersistenceEmulation.assign(entity, "specVersion", value) }

    var syntax: String?
        get() = entity.syntax
        set(value) { PersistenceEmulation.assign(entity, "syntax", value) }

    var state: String
        get() = entity.state
        set(value) { PersistenceEmulation.assign(entity, "state", value) }

    var validFrom: LocalDate?
        get() = entity.validFrom
        set(value) { PersistenceEmulation.assign(entity, "validFrom", value) }

    var validTo: LocalDate?
        get() = entity.validTo
        set(value) { PersistenceEmulation.assign(entity, "validTo", value) }

    var createdAt: OffsetDateTime?
        get() = entity.createdAt
        set(value) { PersistenceEmulation.assign(entity, "createdAt", value) }

    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assign(entity, "version", value) }
}
