package de.littletrotter.fixture

import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldValue
import de.littletrotter.shared.b2b.B2bTerm

/**
 * Test-only write access to a [B2bFieldValue] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun B2bFieldValue.edit(block: B2bFieldValueEditor.() -> Unit): B2bFieldValue = also { B2bFieldValueEditor(it).block() }

/** Property-style access to every persisted field of a [B2bFieldValue]. */
class B2bFieldValueEditor internal constructor(internal val entity: B2bFieldValue) {
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assign(entity, "id", value) }

    var ownerType: B2bFieldOwnerType
        get() = entity.ownerType
        set(value) { PersistenceEmulation.assign(entity, "ownerType", value) }

    var ownerId: Long
        get() = entity.ownerId
        set(value) { PersistenceEmulation.assign(entity, "ownerId", value) }

    var term: B2bTerm?
        get() = entity.term
        set(value) { PersistenceEmulation.assign(entity, "term", value) }

    var value: String?
        get() = entity.value
        set(value) { PersistenceEmulation.assign(entity, "value", value) }

    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assign(entity, "version", value) }
}
