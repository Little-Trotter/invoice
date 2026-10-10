package de.littletrotter.application.mail.port.inbound

import java.time.Instant
import java.time.LocalDate

/** Delivery state of one journal row, as this application shows it. */
enum class MailJournalStatus { CREATED, QUEUED, SENDING, SENT, FAILED }

/** Whether the legally required archive package of a sent mail exists yet. */
enum class MailJournalArchiveStatus { PENDING, ARCHIVED, FAILED }

/** Whether a sent mail reached the external archive mailbox; independent of [MailJournalArchiveStatus]. */
enum class MailJournalHandoverStatus { NOT_CONFIGURED, DISABLED, PENDING, HANDED_OVER, FAILED }

/** Which kind of document a mail dispatches; selects the template family. */
enum class MailTemplateKind { INVOICE_DISPATCH, QUOTE_DISPATCH }

/** Template language; every kind has one template per language. */
enum class MailTemplateLanguage { DE, EN }

/** Columns the journal can be ordered by. */
enum class MailJournalSortColumn { CREATED_AT, STATUS, REFERENCE_KEY, RECIPIENT, SUBJECT, TEMPLATE, ARCHIVE_STATUS }

/** Sort direction of the journal list. */
enum class MailSortOrder { ASC, DESC }

/**
 * Filter, order and page window of one journal listing. The same filter drives the page, the
 * total count (pager) and the per-status counts (status chips), so the three can never disagree;
 * [page]/[size]/[sort]/[direction] are ignored by the counts, and the status chips additionally
 * ignore [status] so that choosing one chip does not zero the others.
 */
data class MailJournalCriteria(
    val text: String? = null,
    val referenceKey: String? = null,
    val status: MailJournalStatus? = null,
    val archiveStatus: MailJournalArchiveStatus? = null,
    val templateKind: MailTemplateKind? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val editedOnly: Boolean = false,
    val sort: MailJournalSortColumn = MailJournalSortColumn.CREATED_AT,
    val direction: MailSortOrder = MailSortOrder.DESC,
    val page: Int = 0,
    val size: Int = DEFAULT_PAGE_SIZE,
) {
    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 50
    }
}

/** One attachment of a journal row; [sizeBytes] is unknown for rows written before sizes were recorded. */
data class MailJournalAttachment(
    val filename: String,
    val contentType: String,
    val sizeBytes: Long?,
    val sha256: String,
)

/** One journal row as the list and its detail panel show it. */
data class MailJournalEntry(
    val id: Long,
    val referenceKey: String,
    val recipient: String,
    val subject: String,
    val messageId: String?,
    val status: MailJournalStatus,
    val smtpResult: String?,
    val templateKind: MailTemplateKind,
    val templateLanguage: MailTemplateLanguage,
    val templateVersion: Int,
    val bodyEditedByUser: Boolean,
    val retryOfJournalId: Long?,
    val createdAt: Instant,
    val attachments: List<MailJournalAttachment>,
    val archiveStatus: MailJournalArchiveStatus,
    val archiveS3Key: String?,
    val archiveHandoverStatus: MailJournalHandoverStatus = MailJournalHandoverStatus.NOT_CONFIGURED,
    val archiveHandoverAt: Instant? = null,
    val archiveHandoverError: String? = null,
)
