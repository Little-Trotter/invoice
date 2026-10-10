package de.littletrotter.infrastructure.mail.acl

import de.littletrotter.application.mail.port.inbound.MailComposeAttachment
import de.littletrotter.application.mail.port.inbound.MailComposeDraft
import de.littletrotter.application.mail.port.inbound.MailPlaceholderEntry
import de.littletrotter.application.mail.port.inbound.MailPlaceholderType
import de.littletrotter.application.mail.port.inbound.MailRenderPreview
import de.littletrotter.application.mail.port.inbound.MailRenderPreviewRequest
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.shared.mail.MailDocumentFamily
import de.littletrotter.shared.mail.MailDraftAttachmentView
import de.littletrotter.shared.mail.MailDraftView
import de.littletrotter.shared.mail.MailPlaceholderCatalogEntry
import de.littletrotter.shared.mail.MailPlaceholderValueType
import de.littletrotter.shared.mail.MailRenderPreview as ContractRenderPreview
import de.littletrotter.shared.mail.MailRenderPreviewRequest as ContractRenderPreviewRequest

/**
 * Compose side of the anti-corruption layer: the draft, the placeholder catalogue and the preview
 * cross the boundary as local models only. Enums are mapped by exhaustive `when`, so a constant
 * added to the contract fails the build here instead of at runtime.
 */
object MailComposeTranslator {

    fun toDraft(view: MailDraftView): MailComposeDraft =
        MailComposeDraft(
            draftId = view.draftId,
            referenceKey = view.referenceKey,
            kind = MailTemplateTranslator.kindFrom(view.documentKind),
            documentFamily = familyFrom(view.documentFamily),
            recipientAddress = view.recipientAddress,
            recipientName = view.recipientName,
            placeholderValues = view.placeholderValues,
            attachments = view.attachments.map(::toAttachment),
            totalAttachmentBytes = view.totalAttachmentBytes,
            expiresAt = view.expiresAt,
        )

    fun toPlaceholder(entry: MailPlaceholderCatalogEntry): MailPlaceholderEntry =
        MailPlaceholderEntry(entry.templateKey, typeFrom(entry.valueType), entry.sampleValue)

    fun toRequest(request: MailRenderPreviewRequest): ContractRenderPreviewRequest =
        ContractRenderPreviewRequest(
            kind = MailTemplateTranslator.kindFor(request.kind),
            documentFamily = MailComposeRequestTranslator.documentFamilyFor(request.documentFamily),
            placeholderValues = request.placeholderValues,
        )

    fun toPreview(preview: ContractRenderPreview): MailRenderPreview =
        MailRenderPreview(
            subject = preview.subject,
            body = preview.body,
            language = MailTemplateTranslator.languageFrom(preview.templateLanguage),
            templateVersion = preview.templateVersion,
        )

    private fun toAttachment(view: MailDraftAttachmentView): MailComposeAttachment =
        MailComposeAttachment(view.filename, view.contentType, view.sizeBytes, view.sha256, view.s3Key)

    internal fun familyFrom(family: MailDocumentFamily): InvoiceFormat = when (family) {
        MailDocumentFamily.GERMAN -> InvoiceFormat.GERMAN
        MailDocumentFamily.INTERNATIONAL -> InvoiceFormat.INTERNATIONAL
    }

    private fun typeFrom(type: MailPlaceholderValueType): MailPlaceholderType = when (type) {
        MailPlaceholderValueType.STRING -> MailPlaceholderType.TEXT
        MailPlaceholderValueType.DATE -> MailPlaceholderType.DATE
        MailPlaceholderValueType.MONEY -> MailPlaceholderType.MONEY
    }
}
