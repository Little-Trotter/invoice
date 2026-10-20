package de.littletrotter.fixture

import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.shared.b2b.B2bTerm

/**
 * Test-only write access to a [B2bProfileField] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun B2bProfileField.edit(block: B2bProfileFieldEditor.() -> Unit): B2bProfileField = also { B2bProfileFieldEditor(it).block() }

/** Property-style access to every persisted field of a [B2bProfileField]. */
class B2bProfileFieldEditor internal constructor(internal val entity: B2bProfileField) {
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assign(entity, "id", value) }

    var profile: B2bProfile?
        get() = entity.profile
        set(value) { PersistenceEmulation.assign(entity, "profile", value) }

    var term: B2bTerm?
        get() = entity.term
        set(value) { PersistenceEmulation.assign(entity, "term", value) }

    var businessDefinition: String
        get() = entity.businessDefinition
        set(value) { PersistenceEmulation.assign(entity, "businessDefinition", value) }

    var requirement: B2bFieldRequirement
        get() = entity.requirement
        set(value) { PersistenceEmulation.assign(entity, "requirement", value) }

    var conditionTerm: B2bTerm?
        get() = entity.conditionTerm
        set(value) { PersistenceEmulation.assign(entity, "conditionTerm", value) }

    var placement: B2bPlacement
        get() = entity.placement
        set(value) { PersistenceEmulation.assign(entity, "placement", value) }

    var sortOrder: Int
        get() = entity.sortOrder
        set(value) { PersistenceEmulation.assign(entity, "sortOrder", value) }

    var defaultValue: String?
        get() = entity.defaultValue
        set(value) { PersistenceEmulation.assign(entity, "defaultValue", value) }

    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assign(entity, "version", value) }
}
