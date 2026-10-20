package de.littletrotter.shared.mail

/**
 * One placeholder variable in a template catalogue — key, type, and sample value.
 * Nested in [MailPlaceholderCatalog].
 */
data class MailPlaceholderCatalogEntry(
    val templateKey: String,
    val valueType: MailPlaceholderValueType,
    val sampleValue: String,
)
