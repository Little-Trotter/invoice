package de.littletrotter.application.mail.port.inbound

import java.time.Instant

/**
 * The instance's setting for handing a copy of every sent mail to an external mail archive.
 * [address] is `null` while none is configured; in that state nothing is handed over whatever the
 * two switches say. [updatedBy] is `null` until an administrator saved the setting once.
 */
data class ArchiveHandoverSettings(
    val address: String?,
    val invoiceEnabled: Boolean,
    val quoteEnabled: Boolean,
    val updatedAt: Instant,
    val updatedBy: String?,
)

/** The values an administrator saves; a blank [address] clears the setting. */
data class ArchiveHandoverSettingsUpdate(
    val address: String?,
    val invoiceEnabled: Boolean = true,
    val quoteEnabled: Boolean = true,
)

/** What a manual hand-over retry left the journal row with; [error] is set when it failed again. */
data class ArchiveHandoverRetryOutcome(
    val journalId: Long,
    val status: MailJournalHandoverStatus,
    val error: String?,
)
