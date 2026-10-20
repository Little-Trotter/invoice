package de.littletrotter.shared.mail

/**
 * Lifecycle state of a mail dispatch in the journal — from draft creation through send attempts to delivery or permanent failure.
 * Used by journal query and entry view endpoints.
 */
enum class MailSendStatus {
    CREATED,
    QUEUED,
    SENDING,
    SENT,
    FAILED,
}
