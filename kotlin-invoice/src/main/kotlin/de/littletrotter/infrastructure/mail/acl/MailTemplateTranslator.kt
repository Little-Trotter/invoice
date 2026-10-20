package de.littletrotter.infrastructure.mail.acl

import de.littletrotter.application.mail.port.inbound.MailBccTestResult
import de.littletrotter.application.mail.port.inbound.MailSendAttachment
import de.littletrotter.application.mail.port.inbound.MailSendCommand
import de.littletrotter.application.mail.port.inbound.MailSendOutcome
import de.littletrotter.application.mail.port.inbound.MailTemplate
import de.littletrotter.application.mail.port.inbound.MailTemplateDraft
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.mail.port.inbound.MailTemplateVersion
import de.littletrotter.application.mail.port.inbound.MailTestToMeResult
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.shared.mail.MailDocumentFamily
import de.littletrotter.shared.mail.MailDocumentKind
import de.littletrotter.shared.mail.MailLanguage
import de.littletrotter.shared.mail.MailSendAttachmentRef
import de.littletrotter.shared.mail.MailSendRequest
import de.littletrotter.shared.mail.MailSendResult
import de.littletrotter.shared.mail.MailTemplateBccTestRequest
import de.littletrotter.shared.mail.MailTemplateBccTestResult
import de.littletrotter.shared.mail.MailTemplateUpdate
import de.littletrotter.shared.mail.MailTemplateVersionView
import de.littletrotter.shared.mail.MailTemplateView
import de.littletrotter.shared.mail.MailTestSendResult

/**
 * Template and send side of the anti-corruption layer. Same rule as the journal side: exhaustive
 * `when` per enum, local model in, local model out, contract types never leave `infrastructure`.
 */
object MailTemplateTranslator {

    fun toTemplate(view: MailTemplateView): MailTemplate =
        MailTemplate(
            id = view.id,
            kind = kindFrom(view.kind),
            language = languageFrom(view.language),
            version = view.version,
            subject = view.subject,
            body = view.body,
            bccAddress = view.bccAddress,
            seededDefault = view.seededDefault,
            createdAt = view.createdAt,
        )

    fun toVersion(view: MailTemplateVersionView): MailTemplateVersion =
        MailTemplateVersion(
            kind = kindFrom(view.kind),
            language = languageFrom(view.language),
            version = view.version,
            subject = view.subject,
            body = view.body,
            bccAddress = view.bccAddress,
            seededDefault = view.seededDefault,
            createdAt = view.createdAt,
            current = view.current,
        )

    fun toUpdate(draft: MailTemplateDraft): MailTemplateUpdate =
        MailTemplateUpdate(
            kind = kindFor(draft.kind),
            language = languageFor(draft.language),
            subject = draft.subject,
            body = draft.body,
            bccAddress = draft.bccAddress,
        )

    fun toTestToMeResult(result: MailTestSendResult): MailTestToMeResult =
        MailTestToMeResult(result.recipientAddress, result.messageId, result.sentAt)

    fun toBccTestRequest(address: String): MailTemplateBccTestRequest = MailTemplateBccTestRequest(address)

    fun toBccTestResult(result: MailTemplateBccTestResult): MailBccTestResult =
        MailBccTestResult(result.bccAddress, result.messageId, result.sentAt)

    fun toSendRequest(command: MailSendCommand): MailSendRequest =
        MailSendRequest(
            kind = kindFor(command.kind),
            documentFamily = familyFor(command.documentFamily),
            recipientAddress = command.recipientAddress,
            referenceKey = command.referenceKey,
            subject = command.subject,
            body = command.body,
            placeholderValues = command.placeholderValues,
            bodyEditedByUser = command.bodyEditedByUser,
            attachments = command.attachments.map(::attachmentFor),
            draftId = command.draftId,
        )

    fun toSendOutcome(result: MailSendResult): MailSendOutcome =
        MailSendOutcome(result.journalId, MailJournalTranslator.statusFrom(result.status))

    /** Path segment of a template: the contract enums' names are the wire values. */
    fun pathOf(kind: MailTemplateKind, language: MailTemplateLanguage): String =
        "${kindFor(kind).name}/${languageFor(language).name}"

    private fun attachmentFor(attachment: MailSendAttachment) =
        MailSendAttachmentRef(attachment.s3Key, attachment.filename, attachment.contentType, attachment.sha256)

    internal fun kindFor(kind: MailTemplateKind): MailDocumentKind = when (kind) {
        MailTemplateKind.INVOICE_DISPATCH -> MailDocumentKind.INVOICE_DISPATCH
        MailTemplateKind.QUOTE_DISPATCH -> MailDocumentKind.QUOTE_DISPATCH
    }

    internal fun kindFrom(kind: MailDocumentKind): MailTemplateKind = when (kind) {
        MailDocumentKind.INVOICE_DISPATCH -> MailTemplateKind.INVOICE_DISPATCH
        MailDocumentKind.QUOTE_DISPATCH -> MailTemplateKind.QUOTE_DISPATCH
    }

    internal fun languageFor(language: MailTemplateLanguage): MailLanguage = when (language) {
        MailTemplateLanguage.DE -> MailLanguage.DE
        MailTemplateLanguage.EN -> MailLanguage.EN
    }

    internal fun languageFrom(language: MailLanguage): MailTemplateLanguage = when (language) {
        MailLanguage.DE -> MailTemplateLanguage.DE
        MailLanguage.EN -> MailTemplateLanguage.EN
    }

    private fun familyFor(format: InvoiceFormat): MailDocumentFamily =
        MailComposeRequestTranslator.documentFamilyFor(format)
}
