package de.littletrotter.infrastructure.mail.acl

import de.littletrotter.application.mail.InvoiceMailAttachment
import de.littletrotter.application.mail.InvoiceMailDraft
import de.littletrotter.application.mail.InvoiceMailPlaceholderValue
import de.littletrotter.application.mail.InvoiceMailSenderBlock
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.shared.mail.MailAttachmentHandover
import de.littletrotter.shared.mail.MailComposeRequest
import de.littletrotter.shared.mail.MailDocumentFamily
import de.littletrotter.shared.mail.MailDocumentKind
import de.littletrotter.shared.mail.MailPlaceholderValue
import de.littletrotter.shared.mail.MailSenderBlock

/**
 * The anti-corruption layer between the invoicing application and the published
 * mail language (`de.littletrotter.shared.mail`), on the outbound (compose) side.
 *
 * This is the host half of the pair the contract's package KDoc names (`MailComposeRequestTranslator`
 * here, `ComposeRequestAssembler` in the component). It is the **only** class in `infrastructure.mail`
 * allowed to build a [MailComposeRequest]; [de.littletrotter.infrastructure.mail.HttpMailDispatchAdapter]
 * calls it once, at the top of `compose`, and never touches a contract type itself beyond that.
 *
 * Two anti-corruption-layer decisions live here, moved from the application layer's old
 * `InvoiceMailComposeMapper` (each side maps the contract into its own local model *at its
 * own adapter*):
 *
 * 1. **The document family is mapped, never shared.** [InvoiceMailDraft.documentFamily] is the
 *    invoicing context's own [InvoiceFormat]; [MailDocumentFamily] is the contract's. They
 *    currently carry the same two constants, and [documentFamilyFor] is a `when` over them
 *    precisely so that adding a third invoice format becomes a compile error *here* — instead of
 *    silently coupling the two contexts' release cycles (see [MailDocumentFamily]'s own KDoc,
 *    "Deliberately a third enum. Do not unify.").
 * 2. **Placeholder values are re-typed onto the wire's own sealed type**, one constructor call per
 *    kind — [InvoiceMailPlaceholderValue] and [MailPlaceholderValue] are intentionally separate
 *    types on either side of the boundary.
 * 3. **The dispatch kind is set here, not carried on [InvoiceMailDraft].** This host only ever
 *    dispatches invoices — [InvoiceMailDraft] has no kind field of its own precisely because there
 *    is only one — so [MailComposeRequest.documentKind] is always
 *    [MailDocumentKind.INVOICE_DISPATCH], the same "map at the adapter, not one layer in" reasoning
 *    as decision 1: `InvoiceMailComposeMapper`/`InvoiceMailDraft` (application layer) must never
 *    import the contract at all (`ArchitectureRulesTest`'s `mailContractOnlyInInfrastructure`), so a
 *    second dispatch kind added later (a quote, say) would still be this class's decision to make,
 *    from whatever the application layer's own local model tells it by then.
 */
object MailComposeRequestTranslator {

    /** Translates [draft] into the wire shape [HttpMailDispatchAdapter][de.littletrotter.infrastructure.mail.HttpMailDispatchAdapter] sends. */
    fun toComposeRequest(draft: InvoiceMailDraft): MailComposeRequest =
        MailComposeRequest(
            referenceKey = draft.referenceKey,
            documentFamily = documentFamilyFor(draft.documentFamily),
            documentKind = MailDocumentKind.INVOICE_DISPATCH,
            recipientAddress = draft.recipientAddress.value,
            recipientName = draft.recipientName,
            placeholderValues = draft.placeholderValues.mapValues { (_, value) -> placeholderValueFor(value) },
            attachments = draft.attachments.map(::attachmentFor),
            senderBlock = draft.senderBlock?.let(::senderBlockFor),
        )

    private fun senderBlockFor(block: InvoiceMailSenderBlock): MailSenderBlock =
        MailSenderBlock(block.name, block.phone, block.email, block.website, block.legalLines)

    /**
     * The invoicing context's [InvoiceFormat] mapped onto the contract's [MailDocumentFamily].
     * Exhaustive `when`, not `MailDocumentFamily.valueOf(format.name)`: the name-based shortcut
     * would compile forever and start throwing at runtime the day either enum grows a constant the
     * other one lacks. Here the compiler says so first.
     */
    internal fun documentFamilyFor(format: InvoiceFormat): MailDocumentFamily = when (format) {
        InvoiceFormat.GERMAN -> MailDocumentFamily.GERMAN
        InvoiceFormat.INTERNATIONAL -> MailDocumentFamily.INTERNATIONAL
    }

    /** Exhaustive `when` over the local sealed type — a new kind fails the build here, not at runtime. */
    private fun placeholderValueFor(value: InvoiceMailPlaceholderValue): MailPlaceholderValue = when (value) {
        is InvoiceMailPlaceholderValue.Text -> MailPlaceholderValue.Text(value.value)
        is InvoiceMailPlaceholderValue.Date -> MailPlaceholderValue.Date(value.value)
        is InvoiceMailPlaceholderValue.Amount -> MailPlaceholderValue.Amount(value.value)
    }

    private fun attachmentFor(attachment: InvoiceMailAttachment): MailAttachmentHandover =
        MailAttachmentHandover(
            filename = attachment.filename,
            contentType = attachment.contentType,
            sizeBytes = attachment.sizeBytes,
            sha256 = attachment.sha256,
            downloadUrl = attachment.downloadUrl,
        )
}
