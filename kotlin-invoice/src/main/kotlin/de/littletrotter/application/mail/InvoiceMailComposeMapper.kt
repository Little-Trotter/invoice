package de.littletrotter.application.mail

import de.littletrotter.application.mail.port.outbound.DocumentPresignPort
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.masterdata.showsCommercialRegister
import de.littletrotter.domain.shared.InvoiceFormat
import java.time.LocalDate

/**
 * Maps one invoice onto this application's own local model of a mail draft
 * ([InvoiceMailDraft]).
 *
 * This used to be the anti-corruption layer between the invoicing context and the
 * published mail language (`de.littletrotter.shared.mail`) directly — it built the contract's
 * `MailComposeRequest` itself. That contract-facing half moved to
 * `de.littletrotter.infrastructure.mail.acl.MailComposeRequestTranslator`; this mapper now stays a
 * pure domain -> application mapping and never imports `de.littletrotter.shared.mail..` at all.
 * Everything the mail-dispatch context may see about an invoice still passes through this one
 * function: **SPI by value, not by entity** — no [Invoice], no [Recipient], no line items, no
 * subject and no body leave the host.
 *
 * Two rules this mapper still exists to enforce:
 *
 * 1. **Placeholder values travel typed, and are never formatted here.** A gross total becomes an
 *    [InvoiceMailPlaceholderValue.Amount], a date an [InvoiceMailPlaceholderValue.Date] — never
 *    "1.234,56 €" or "07.09.2026". Formatting is the component's job, because only the component
 *    knows the *template's* language, which is not the host's UI locale and not necessarily the
 *    invoice's `documentLanguage` either.
 * 2. **A blank STRING placeholder is omitted, not sent empty.** The translator's contract encoding
 *    rejects a blank required value, and a template rendering `{{iban}}` as the empty string is
 *    worse than one whose placeholder is unresolved. [putText] is the single place that rule lives.
 *
 * [InvoiceMailDraft.documentFamily] is simply the recipient's own [de.littletrotter.domain.shared
 * .InvoiceFormat] — [de.littletrotter.infrastructure.mail.acl.MailComposeRequestTranslator] is
 * where that becomes the contract's `MailDocumentFamily`, the anti-corruption-layer approach
 * asks for.
 *
 * Attachments are **not** built here: an [InvoiceMailAttachment] carries a presigned URL, which
 * only the storage adapter can mint ([DocumentPresignPort]). [InvoiceMailDispatchService] assembles
 * them and hands them in, so this mapper stays a pure function and is unit-testable without any
 * infrastructure at all.
 */
object InvoiceMailComposeMapper {

    /** Template keys of the component's `Placeholder` whitelist. Plain strings on purpose — this
     *  application must not depend on the component's enum. */
    internal const val KEY_INVOICE_NUMBER = "invoiceNumber"
    internal const val KEY_INVOICE_DATE = "invoiceDate"
    internal const val KEY_DUE_DATE = "dueDate"
    internal const val KEY_GROSS_TOTAL = "grossTotal"
    internal const val KEY_RECIPIENT_NAME = "recipientName"
    internal const val KEY_SENDER_NAME = "senderName"
    internal const val KEY_IBAN = "iban"
    internal const val KEY_SENDER_PHONE = "senderPhone"
    internal const val KEY_SENDER_EMAIL = "senderEmail"
    internal const val KEY_SENDER_WEBSITE = "senderWebsite"

    /**
     * Maps one invoice plus its already-presigned [attachments] onto this application's own draft
     * model.
     *
     * Throws [MailDispatchException] — never a half-filled draft — when the invoice cannot address
     * a mail at all: no recipient, no recipient email, or no invoice number to use as the
     * [InvoiceMailDraft.referenceKey] the result event comes back on. Those are the three values
     * the component has no way to recover from, so failing here is the only honest option.
     */
    fun toDraft(
        invoice: Invoice,
        attachments: List<InvoiceMailAttachment> = emptyList(),
    ): InvoiceMailDraft {
        val recipient = invoice.recipient
            ?: throw MailDispatchException("Invoice ${invoice.id} has no recipient — nothing to address a mail to")
        val recipientAddress = recipient.email?.takeUnless { it.value.isBlank() }
            ?: throw MailDispatchException("Recipient '${recipient.name}' has no email address")
        val referenceKey = invoice.invoiceNumber.value.takeUnless { it.isBlank() }
            ?: throw MailDispatchException("Invoice ${invoice.id} has no invoice number to use as reference key")

        return InvoiceMailDraft(
            referenceKey = referenceKey,
            documentFamily = recipient.invoiceFormat,
            recipientAddress = recipientAddress,
            recipientName = recipient.name,
            placeholderValues = placeholderValues(invoice, recipient, referenceKey),
            attachments = attachments,
            senderBlock = invoice.senderProfile?.let { senderBlock(it, recipient.invoiceFormat) },
        )
    }

    /**
     * The signature block for [sender], or `null` when the profile has no usable name. Legal lines
     * follow the recipient's document family (German labels for German, English otherwise) so a
     * foreign customer does not receive a German signature; each is omitted when its data is blank,
     * and the register line only when the legal form actually has a commercial register entry.
     */
    private fun senderBlock(sender: SenderProfile, format: InvoiceFormat): InvoiceMailSenderBlock? {
        val name = sender.name.trim().takeIf { it.isNotEmpty() } ?: return null
        val german = format == InvoiceFormat.GERMAN
        val register = if (sender.showsCommercialRegister()) {
            listOfNotNull(sender.registerCourt?.trim(), sender.commercialRegisterNumber?.trim())
                .filter { it.isNotEmpty() }.joinToString(" ").takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val director = sender.managingDirector?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { (if (german) "Geschäftsführer: " else "Managing director: ") + it }
        val vat = sender.vatId?.value?.takeIf { it.isNotBlank() }
            ?.let { (if (german) "USt-IdNr.: " else "VAT ID: ") + it }
        return InvoiceMailSenderBlock(
            name = name,
            phone = sender.phone?.trim()?.takeIf { it.isNotEmpty() },
            email = sender.email?.value?.trim()?.takeIf { it.isNotEmpty() },
            website = sender.website?.trim()?.takeIf { it.isNotEmpty() },
            legalLines = listOfNotNull(register, director, vat),
        )
    }

    /**
     * The typed values a template may substitute. Insertion order is the component's
     * `Placeholder` declaration order, so a golden-JSON comparison on either side reads the same
     * way; the map is a [LinkedHashMap] for that reason.
     *
     * `dueDate` is omitted rather than sent as `null` when the invoice has none or is a credit
     * note (marked by the invoice it reverses; a credit note is not paid by a deadline): the contract's placeholder map has no
     * "absent" value kind, and the stock template wraps the payment sentence in an optional
     * `dueDate` section that disappears when the key is absent.
     */
    private fun placeholderValues(
        invoice: Invoice,
        recipient: Recipient,
        referenceKey: String,
    ): Map<String, InvoiceMailPlaceholderValue> {
        val values = LinkedHashMap<String, InvoiceMailPlaceholderValue>()
        putText(values, KEY_INVOICE_NUMBER, referenceKey)
        putDate(values, KEY_INVOICE_DATE, invoice.invoiceDate)
        if (invoice.originalInvoiceNumber == null) putDate(values, KEY_DUE_DATE, invoice.dueDate)
        values[KEY_GROSS_TOTAL] = InvoiceMailPlaceholderValue.Amount(invoice.totals.grossAmount)
        putText(values, KEY_RECIPIENT_NAME, recipient.name)
        putText(values, KEY_SENDER_NAME, invoice.senderProfile?.name)
        putText(values, KEY_IBAN, invoice.senderProfile?.iban?.value)
        putText(values, KEY_SENDER_PHONE, invoice.senderProfile?.phone)
        putText(values, KEY_SENDER_EMAIL, invoice.senderProfile?.email?.value)
        putText(values, KEY_SENDER_WEBSITE, invoice.senderProfile?.website)
        return values
    }

    /** Rule 2 above: a blank or absent string is left out of the map, never written as `""`. */
    private fun putText(target: MutableMap<String, InvoiceMailPlaceholderValue>, key: String, value: String?) {
        val text = value?.trim().orEmpty()
        if (text.isNotEmpty()) target[key] = InvoiceMailPlaceholderValue.Text(text)
    }

    /** An absent date omits its key. */
    private fun putDate(target: MutableMap<String, InvoiceMailPlaceholderValue>, key: String, value: LocalDate?) {
        if (value != null) target[key] = InvoiceMailPlaceholderValue.Date(value)
    }
}
