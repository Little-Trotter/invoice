package de.littletrotter.fixture

import de.littletrotter.domain.reference.RecipientB2bFieldValue
import java.time.OffsetDateTime

/**
 * Test-only write access to a [RecipientB2bFieldValue] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun RecipientB2bFieldValue.edit(block: RecipientB2bFieldValueEditor.() -> Unit): RecipientB2bFieldValue = also { RecipientB2bFieldValueEditor(it).block() }

/** Property-style access to every persisted field of a [RecipientB2bFieldValue]. */
class RecipientB2bFieldValueEditor internal constructor(internal val entity: RecipientB2bFieldValue) {
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assign(entity, "id", value) }

    var recipientId: Long
        get() = entity.recipientId
        set(value) { PersistenceEmulation.assign(entity, "recipientId", value) }

    var profileFieldId: Long
        get() = entity.profileFieldId
        set(value) { PersistenceEmulation.assign(entity, "profileFieldId", value) }

    var value: String?
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
