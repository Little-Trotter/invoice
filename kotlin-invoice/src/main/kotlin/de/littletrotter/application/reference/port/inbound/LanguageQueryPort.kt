package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.dto.LanguageDto

/**
 * Input port for reading the languages a per-language field list offers (the seller form's
 * invoice-note fields, the payment-term tab's name fields): a third language is a data change,
 * not a code change.
 */
fun interface LanguageQueryPort {
    /** Active languages ordered by [de.littletrotter.domain.reference.Language.sortOrder]. */
    fun findActiveLanguages(): List<LanguageDto>
}
