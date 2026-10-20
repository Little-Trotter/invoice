package de.littletrotter.shared.mail

import java.time.Instant

/**
 * Response when a mail draft is created — the draft's ID and the compose URL.
 * Returned by the compose endpoint.
 */
data class MailDraftCreated(
    val draftId: String,
    val composeUrl: String,
    val expiresAt: Instant,
)
