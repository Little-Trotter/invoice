package de.littletrotter.application.dto

/**
 * One active document language a per-language field list offers (the seller form's invoice-note
 * fields, the payment-term tab's name fields): a third language is a data change, not a code
 * change. [id] is the stable, never-reused numeric identity
 * [de.littletrotter.domain.reference.Language] itself documents; [code] is what every resolution
 * (e.g. [de.littletrotter.shared.reference.resolveLocalizedText]) actually compares against.
 */
data class LanguageDto(
    val id: Int,
    val code: String,
    val isFallback: Boolean,
    val sortOrder: Int,
)
