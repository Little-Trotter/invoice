package de.littletrotter.domain.repository

import de.littletrotter.domain.reference.Language

/**
 * Read-only lookup of the [Language] reference table. No write method: nothing in this
 * package's UI adds or edits a language.
 */
interface LanguageRepository {
    /** Active languages ordered by [Language.sortOrder], for a language picker. */
    fun findActiveOrdered(): List<Language>

    fun findByCode(code: String): Language?
}
