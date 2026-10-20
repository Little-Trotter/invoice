package de.littletrotter.shared.mail

import java.time.Instant

/**
 * The instance's archive hand-over setting as returned by `GET /mail/api/archive-handover`.
 *
 * Direction: component -> host. [address] is `null` while no archive address is configured; in
 * that state nothing is handed over regardless of the two switches. [updatedBy] is `null` until an
 * admin saved the setting for the first time ([updatedAt] then is the installation time).
 */
data class MailArchiveHandoverConfigView(
    val address: String?,
    val invoiceEnabled: Boolean,
    val quoteEnabled: Boolean,
    val updatedAt: Instant,
    val updatedBy: String?,
)
