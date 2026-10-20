package de.littletrotter.shared.mail

/**
 * All placeholder variables available for a template kind — their types and samples.
 * Returned by the placeholder catalogue endpoint.
 */
data class MailPlaceholderCatalog(
    val entries: List<MailPlaceholderCatalogEntry>,
)
