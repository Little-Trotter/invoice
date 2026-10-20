package de.littletrotter.fixture

import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.LocalizedTextEntry
import de.littletrotter.domain.reference.LocalizedTextEntryId
import de.littletrotter.domain.reference.LocalizedTextGroup

/**
 * Test-only write access to a [LocalizedTextEntry] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun LocalizedTextEntry.edit(block: LocalizedTextEntryEditor.() -> Unit): LocalizedTextEntry = also { LocalizedTextEntryEditor(it).block() }

/** Property-style access to every persisted field of a [LocalizedTextEntry]. */
class LocalizedTextEntryEditor internal constructor(internal val entity: LocalizedTextEntry) {
    var id: LocalizedTextEntryId
        get() = entity.id
        set(value) { PersistenceEmulation.assign(entity, "id", value) }

    var group: LocalizedTextGroup?
        get() = entity.group
        set(value) { PersistenceEmulation.assign(entity, "group", value) }

    var language: Language?
        get() = entity.language
        set(value) { PersistenceEmulation.assign(entity, "language", value) }

    var value: String
        get() = entity.value
        set(value) { PersistenceEmulation.assign(entity, "value", value) }
}
