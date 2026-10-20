package de.littletrotter.infrastructure.mail.acl

import de.littletrotter.application.mail.port.inbound.MailJournalArchiveStatus
import de.littletrotter.application.mail.port.inbound.MailJournalAttachment
import de.littletrotter.application.mail.port.inbound.MailJournalCriteria
import de.littletrotter.application.mail.port.inbound.MailJournalEntry
import de.littletrotter.application.mail.port.inbound.MailJournalHandoverStatus
import de.littletrotter.application.mail.port.inbound.MailJournalSortColumn
import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.application.mail.port.inbound.MailRearchiveOutcome
import de.littletrotter.application.mail.port.inbound.MailSortOrder
import de.littletrotter.shared.mail.MailArchiveHandoverStatus
import de.littletrotter.shared.mail.MailArchiveStatus
import de.littletrotter.shared.mail.MailJournalAttachmentView
import de.littletrotter.shared.mail.MailJournalEntryView
import de.littletrotter.shared.mail.MailJournalQuery
import de.littletrotter.shared.mail.MailJournalSortField
import de.littletrotter.shared.mail.MailRearchiveResult
import de.littletrotter.shared.mail.MailSendStatus
import de.littletrotter.shared.mail.MailSortDirection

/**
 * Journal side of the anti-corruption layer: the local filter becomes the contract query and its
 * REST parameters, contract rows become local entries. Every enum is mapped by an exhaustive `when`
 * so a constant added on either side fails the build here instead of at runtime.
 */
object MailJournalTranslator {

    fun toQuery(criteria: MailJournalCriteria): MailJournalQuery =
        MailJournalQuery(
            text = criteria.text,
            referenceKey = criteria.referenceKey,
            status = criteria.status?.let(::statusFor),
            archiveStatus = criteria.archiveStatus?.let(::archiveStatusFor),
            templateKind = criteria.templateKind?.let(MailTemplateTranslator::kindFor),
            from = criteria.from,
            to = criteria.to,
            editedOnly = criteria.editedOnly,
            sort = sortFor(criteria.sort),
            direction = directionFor(criteria.direction),
            page = criteria.page,
            size = criteria.size,
        )

    /**
     * The REST parameters of [query]; blank text and unset filters are left out, `editedOnly` only
     * when set. [withWindow] adds sort and paging — the counts ignore both, so they are not sent.
     */
    fun toParameters(query: MailJournalQuery, withWindow: Boolean): List<Pair<String, String>> =
        buildList {
            query.text?.takeIf { it.isNotBlank() }?.let { add("text" to it) }
            query.referenceKey?.takeIf { it.isNotBlank() }?.let { add("referenceKey" to it) }
            query.status?.let { add("status" to it.name) }
            query.archiveStatus?.let { add("archiveStatus" to it.name) }
            query.templateKind?.let { add("templateKind" to it.name) }
            query.from?.let { add("from" to it.toString()) }
            query.to?.let { add("to" to it.toString()) }
            if (query.editedOnly) add("editedOnly" to "true")
            if (withWindow) {
                add("sort" to query.sort.name)
                add("dir" to query.direction.name)
                add("page" to query.page.toString())
                add("size" to query.size.toString())
            }
        }

    fun toEntry(view: MailJournalEntryView): MailJournalEntry =
        MailJournalEntry(
            id = view.id,
            referenceKey = view.referenceKey,
            recipient = view.recipient,
            subject = view.subject,
            messageId = view.messageId,
            status = statusFrom(view.status),
            smtpResult = view.smtpResult,
            templateKind = MailTemplateTranslator.kindFrom(view.templateKind),
            templateLanguage = MailTemplateTranslator.languageFrom(view.templateLanguage),
            templateVersion = view.templateVersion,
            bodyEditedByUser = view.bodyEditedByUser,
            retryOfJournalId = view.retryOfJournalId,
            createdAt = view.createdAt,
            attachments = view.attachments.map(::attachmentFrom),
            archiveStatus = archiveStatusFrom(view.archiveStatus),
            archiveS3Key = view.archiveS3Key,
            archiveHandoverStatus = handoverStatusFrom(view.archiveHandoverStatus),
            archiveHandoverAt = view.archiveHandoverAt,
            archiveHandoverError = view.archiveHandoverError,
        )

    fun toRearchiveOutcome(result: MailRearchiveResult): MailRearchiveOutcome =
        MailRearchiveOutcome(result.journalId, archiveStatusFrom(result.archiveStatus), result.archiveError)

    /** Statuses the component did not report count zero, so every chip has a number. */
    fun toCounts(counts: Map<MailSendStatus, Long>): Map<MailJournalStatus, Long> =
        MailJournalStatus.entries.associateWith { local ->
            counts.entries.firstOrNull { statusFrom(it.key) == local }?.value ?: 0L
        }

    private fun attachmentFrom(view: MailJournalAttachmentView) =
        MailJournalAttachment(view.filename, view.contentType, view.sizeBytes, view.sha256)

    internal fun statusFor(status: MailJournalStatus): MailSendStatus = when (status) {
        MailJournalStatus.CREATED -> MailSendStatus.CREATED
        MailJournalStatus.QUEUED -> MailSendStatus.QUEUED
        MailJournalStatus.SENDING -> MailSendStatus.SENDING
        MailJournalStatus.SENT -> MailSendStatus.SENT
        MailJournalStatus.FAILED -> MailSendStatus.FAILED
    }

    internal fun statusFrom(status: MailSendStatus): MailJournalStatus = when (status) {
        MailSendStatus.CREATED -> MailJournalStatus.CREATED
        MailSendStatus.QUEUED -> MailJournalStatus.QUEUED
        MailSendStatus.SENDING -> MailJournalStatus.SENDING
        MailSendStatus.SENT -> MailJournalStatus.SENT
        MailSendStatus.FAILED -> MailJournalStatus.FAILED
    }

    private fun archiveStatusFor(status: MailJournalArchiveStatus): MailArchiveStatus = when (status) {
        MailJournalArchiveStatus.PENDING -> MailArchiveStatus.PENDING
        MailJournalArchiveStatus.ARCHIVED -> MailArchiveStatus.ARCHIVED
        MailJournalArchiveStatus.FAILED -> MailArchiveStatus.FAILED
    }

    private fun archiveStatusFrom(status: MailArchiveStatus): MailJournalArchiveStatus = when (status) {
        MailArchiveStatus.PENDING -> MailJournalArchiveStatus.PENDING
        MailArchiveStatus.ARCHIVED -> MailJournalArchiveStatus.ARCHIVED
        MailArchiveStatus.FAILED -> MailJournalArchiveStatus.FAILED
    }

    private fun handoverStatusFrom(status: MailArchiveHandoverStatus): MailJournalHandoverStatus = when (status) {
        MailArchiveHandoverStatus.NOT_CONFIGURED -> MailJournalHandoverStatus.NOT_CONFIGURED
        MailArchiveHandoverStatus.DISABLED -> MailJournalHandoverStatus.DISABLED
        MailArchiveHandoverStatus.PENDING -> MailJournalHandoverStatus.PENDING
        MailArchiveHandoverStatus.HANDED_OVER -> MailJournalHandoverStatus.HANDED_OVER
        MailArchiveHandoverStatus.FAILED -> MailJournalHandoverStatus.FAILED
    }

    private fun sortFor(sort: MailJournalSortColumn): MailJournalSortField = when (sort) {
        MailJournalSortColumn.CREATED_AT -> MailJournalSortField.CREATED_AT
        MailJournalSortColumn.STATUS -> MailJournalSortField.STATUS
        MailJournalSortColumn.REFERENCE_KEY -> MailJournalSortField.REFERENCE_KEY
        MailJournalSortColumn.RECIPIENT -> MailJournalSortField.RECIPIENT
        MailJournalSortColumn.SUBJECT -> MailJournalSortField.SUBJECT
        MailJournalSortColumn.TEMPLATE -> MailJournalSortField.TEMPLATE
        MailJournalSortColumn.ARCHIVE_STATUS -> MailJournalSortField.ARCHIVE_STATUS
    }

    private fun directionFor(order: MailSortOrder): MailSortDirection = when (order) {
        MailSortOrder.ASC -> MailSortDirection.ASC
        MailSortOrder.DESC -> MailSortDirection.DESC
    }
}
