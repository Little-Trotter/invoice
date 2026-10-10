package de.littletrotter.application.mail

import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.value.Email
import de.littletrotter.shared.Money
import java.time.LocalDate

/**
 * The invoicing application's own local model of "what to hand over to the
 * mail-dispatch context", built entirely from domain/application types.
 *
 * This is what used to be `de.littletrotter.shared.mail.MailComposeRequest` reaching one layer too
 * far in: [InvoiceMailComposeMapper] still does the domain -> local-model mapping described in its
 * own KDoc (document family, typed placeholders, the blank-string omission rule), but the result
 * now stays inside the invoicing context. Turning this into the contract's wire shape is the job of
 * `de.littletrotter.infrastructure.mail.acl.MailComposeRequestTranslator`, the one place that may
 * import `de.littletrotter.shared.mail..` on this side of the boundary.
 *
 * [documentFamily] deliberately stays the domain's own [InvoiceFormat] rather than a duplicate
 * local enum: it is already the value [InvoiceMailComposeMapper] decided on (the recipient's format,
 * not the invoice's own), and re-declaring it here would just be a second copy with the same two
 * constants. The translator maps it onto the contract's `MailDocumentFamily` with an exhaustive
 * `when`, the anti-corruption-layer approach asks for.
 */
data class InvoiceMailDraft(
    /** The invoice number this mail is about; echoed back on [MailDeliveryReport.referenceKey]. */
    val referenceKey: String,
    /** Which template family the component should offer — the recipient's own [InvoiceFormat]. */
    val documentFamily: InvoiceFormat,
    /** The recipient's e-mail address. */
    val recipientAddress: Email,
    /** The recipient's display name, for the `To:` header and the `recipientName` placeholder. */
    val recipientName: String,
    /** Typed template values, keyed by template key (`invoiceNumber`, `grossTotal`, …). May be empty. */
    val placeholderValues: Map<String, InvoiceMailPlaceholderValue> = emptyMap(),
    /** Documents to attach, by presigned reference. May be empty — a reminder mail carries none. */
    val attachments: List<InvoiceMailAttachment> = emptyList(),
    /** Signature block built from the invoice's sender profile; `null` when the profile has no usable name. */
    val senderBlock: InvoiceMailSenderBlock? = null,
)

/**
 * The signature block appended to the mail body. Built from the invoice's sender profile so that
 * company data has one source (the same as the PDF footer); the template only shapes the prose.
 * Every contact field is optional because a profile need not carry all of them.
 */
data class InvoiceMailSenderBlock(
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val website: String? = null,
    val legalLines: List<String> = emptyList(),
)

/**
 * The invoicing context's own typed placeholder value — the local counterpart of the contract's
 * `MailPlaceholderValue`, kept separate on purpose (similar types on both sides of a
 * context boundary are intended, DRY applies inside a context, not across it).
 */
sealed interface InvoiceMailPlaceholderValue {

    /** A plain string placeholder — invoice number, recipient/sender name, IBAN. */
    data class Text(val value: String) : InvoiceMailPlaceholderValue

    /** A calendar date — invoice date, due date. */
    data class Date(val value: LocalDate) : InvoiceMailPlaceholderValue

    /** A monetary amount — the gross total. */
    data class Amount(val value: Money) : InvoiceMailPlaceholderValue
}

/**
 * One document the invoicing application hands over to be attached — by presigned reference, never
 * by value. Local counterpart of the contract's `MailAttachmentHandover`; see
 * [de.littletrotter.application.mail.InvoiceMailDispatchService] for how the five fields are filled
 * in from an [de.littletrotter.domain.invoice.InvoiceDocument] plus a presigned URL.
 */
data class InvoiceMailAttachment(
    /** The filename the recipient sees in the MIME part, e.g. `Rechnung-2026-0001.pdf`. */
    val filename: String,
    /** The MIME type of the object, e.g. `application/pdf`. */
    val contentType: String,
    /** Byte length of the object, for the component's total-mail-size budget check. */
    val sizeBytes: Long,
    /** Lowercase hex SHA-256 of the object's bytes. */
    val sha256: String,
    /** Short-lived presigned GET URL the component fetches the bytes from exactly once. */
    val downloadUrl: String,
)
