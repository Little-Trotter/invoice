package de.littletrotter.fixture

import de.littletrotter.domain.reference.LocalizedTextEntry
import de.littletrotter.domain.reference.LocalizedTextGroup
import de.littletrotter.domain.reference.LocalizedTextPurpose

/**
 * Test-only write access to a [LocalizedTextGroup] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun LocalizedTextGroup.edit(block: LocalizedTextGroupEditor.() -> Unit): LocalizedTextGroup = also { LocalizedTextGroupEditor(it).block() }

/** Property-style access to every persisted field of a [LocalizedTextGroup]. */
class LocalizedTextGroupEditor internal constructor(internal val entity: LocalizedTextGroup) {
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assign(entity, "id", value) }

    var purpose: LocalizedTextPurpose
        get() = entity.purpose
        set(value) { PersistenceEmulation.assign(entity, "purpose", value) }

    var entries: MutableList<LocalizedTextEntry>
        get() = entity.entries
        set(value) { PersistenceEmulation.assign(entity, "entries", value) }
}
