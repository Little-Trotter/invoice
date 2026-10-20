package de.littletrotter.domain.message

import java.time.OffsetDateTime

/**
 * How long a message stays "unread" without a receipt: older messages count as read, so the badge
 * does not grow forever for messages nobody needs to act on. Errors are kept unread longer than
 * info and warnings. A pure query bound; no receipts are written for aged-out messages.
 */
data class UnreadWindow(val cutoff: OffsetDateTime, val errorCutoff: OffsetDateTime) {
    /** Whether a message of [severity] recorded at [occurredAt] is still inside the window. */
    fun covers(severity: SystemMessageSeverity, occurredAt: OffsetDateTime): Boolean =
        !occurredAt.isBefore(if (severity == SystemMessageSeverity.ERROR) errorCutoff else cutoff)
}
