package de.littletrotter.shared.mail

/**
 * Archive state of a sent mail in the journal — whether the dispatch was persisted to durable storage.
 * Used by journal entry views and rearchive endpoints.
 */
enum class MailArchiveStatus {
    PENDING,
    ARCHIVED,
    FAILED,
}
