package de.littletrotter.shared.mail

/**
 * Sortable columns in the mail journal — available as sort keys in query requests.
 * Used by journal query endpoints.
 */
enum class MailJournalSortField {
    CREATED_AT,
    STATUS,
    REFERENCE_KEY,
    RECIPIENT,
    SUBJECT,
    TEMPLATE,
    ARCHIVE_STATUS,
}
