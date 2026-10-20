package de.littletrotter.shared.mail

import java.time.LocalDate

/**
 * Query parameters for journal search and filtering — text, exact reference key, status, date range, and pagination.
 * Accepted by the journal endpoint.
 */
data class MailJournalQuery(
    val text: String? = null,
    val referenceKey: String? = null,
    val status: MailSendStatus? = null,
    val archiveStatus: MailArchiveStatus? = null,
    val templateKind: MailDocumentKind? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val editedOnly: Boolean = false,
    val sort: MailJournalSortField = MailJournalSortField.CREATED_AT,
    val direction: MailSortDirection = MailSortDirection.DESC,
    val page: Int = 0,
    val size: Int = 50,
)
