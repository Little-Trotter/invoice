package de.littletrotter.fixture

import de.littletrotter.domain.reference.Language

/**
 * Test-only write access to a [Language] whose setters are closed to production code. Tests arrange
 * rows as the database would hand them over, without going through the intent methods; each write
 * applies at once through the persistence field.
 */
fun Language.edit(block: LanguageEditor.() -> Unit): Language = also { LanguageEditor(it).block() }

/** Property-style access to every persisted field of a [Language]. */
class LanguageEditor internal constructor(internal val entity: Language) {
    var id: Int
        get() = entity.id
        set(value) { PersistenceEmulation.assign(entity, "id", value) }

    var code: String
        get() = entity.code
        set(value) { PersistenceEmulation.assign(entity, "code", value) }

    var isActive: Boolean
        get() = entity.isActive
        set(value) { PersistenceEmulation.assign(entity, "isActive", value) }

    var isFallback: Boolean
        get() = entity.isFallback
        set(value) { PersistenceEmulation.assign(entity, "isFallback", value) }

    var sortOrder: Int
        get() = entity.sortOrder
        set(value) { PersistenceEmulation.assign(entity, "sortOrder", value) }
}
