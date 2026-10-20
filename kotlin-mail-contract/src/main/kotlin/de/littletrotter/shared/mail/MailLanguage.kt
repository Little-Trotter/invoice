package de.littletrotter.shared.mail

/**
 * Language a mail template renders in — German or international.
 * Used by template management endpoints to resolve and update template variants.
 */
enum class MailLanguage {
    DE,
    EN,
}
