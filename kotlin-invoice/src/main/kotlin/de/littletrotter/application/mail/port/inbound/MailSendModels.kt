package de.littletrotter.application.mail.port.inbound

import de.littletrotter.domain.shared.InvoiceFormat

/** An attachment already stored in the document store, referenced by key and checksum. */
data class MailSendAttachment(
    val s3Key: String,
    val filename: String,
    val contentType: String,
    val sha256: String,
)

/** The mail a user confirmed in the compose view. */
data class MailSendCommand(
    val kind: MailTemplateKind,
    val documentFamily: InvoiceFormat,
    val recipientAddress: String,
    val referenceKey: String,
    val subject: String,
    val body: String,
    val placeholderValues: Map<String, String?>,
    val bodyEditedByUser: Boolean,
    val attachments: List<MailSendAttachment>,
    val draftId: String? = null,
)

/** Journal row created by a send; delivery continues asynchronously, so the status is rarely final. */
data class MailSendOutcome(val journalId: Long, val status: MailJournalStatus)
