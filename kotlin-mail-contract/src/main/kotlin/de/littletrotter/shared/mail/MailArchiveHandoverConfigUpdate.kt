package de.littletrotter.shared.mail

/**
 * Body of `PUT /mail/api/archive-handover`, which replaces the whole setting.
 *
 * Direction: host -> component. A `null` or blank [address] clears the setting ("not configured");
 * otherwise it must be a single RFC 5322 address. The switches decide per document family whether
 * a configured address receives a copy and default to on.
 */
data class MailArchiveHandoverConfigUpdate(
    val address: String? = null,
    val invoiceEnabled: Boolean = true,
    val quoteEnabled: Boolean = true,
)
