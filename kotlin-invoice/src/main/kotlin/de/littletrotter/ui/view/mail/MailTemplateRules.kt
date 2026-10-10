package de.littletrotter.ui.view.mail

import de.littletrotter.application.mail.port.inbound.MailPlaceholderEntry
import de.littletrotter.application.mail.port.inbound.MailPlaceholderType
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.domain.shared.InvoiceFormat

/** Placeholders a template uses wrongly: names the component does not know and required ones it lacks. */
internal data class MailTemplateProblems(val unknown: List<String>, val missing: List<String>) {
    val any: Boolean get() = unknown.isNotEmpty() || missing.isNotEmpty()
}

/** Headings the placeholder chips are grouped under, in display order. */
internal enum class MailPlaceholderGroup { DOCUMENT, AMOUNTS, DATES, SENDER, RECIPIENT, OTHER }

/**
 * What the template editor checks and groups, kept free of UI so the rules are testable on their own.
 *
 * A template without its document number would send a mail that names no invoice or quote, and a
 * token the component does not know would reach the customer as raw `{{…}}` text; both therefore
 * block saving rather than being left to the render step to fail on later.
 */
internal object MailTemplateRules {

    private val TOKEN = Regex("\\{\\{(\\w+)}}")

    /** The placeholder every template of [kind] must contain, in subject or body. */
    fun requiredKey(kind: MailTemplateKind): String = when (kind) {
        MailTemplateKind.INVOICE_DISPATCH -> "invoiceNumber"
        MailTemplateKind.QUOTE_DISPATCH -> "quoteNumber"
    }

    /** Checks the tokens of [subject] and [body] against the [catalog]. */
    fun check(kind: MailTemplateKind, subject: String, body: String, catalog: List<MailPlaceholderEntry>): MailTemplateProblems {
        val known = catalog.map { it.key }.toSet()
        val used = tokensIn(subject) + tokensIn(body)
        val required = requiredKey(kind)
        return MailTemplateProblems(
            unknown = (used + MailPlaceholderText.sectionNames(subject) + MailPlaceholderText.sectionNames(body))
                .filter { it !in known }.distinct(),
            missing = listOf(required).filter { it !in used },
        )
    }

    /** Names of the `{{name}}` tokens in [text], in order of appearance. */
    fun tokensIn(text: String): List<String> = TOKEN.findAll(text).map { it.groupValues[1] }.toList()

    /** The sample value of every catalogue entry, the only data the preview is filled with. */
    fun sampleValues(catalog: List<MailPlaceholderEntry>): Map<String, String> = catalog.associate { it.key to it.sampleValue }

    /** The heading a chip for [entry] belongs under; unlisted keys fall back to their value type. */
    fun groupOf(entry: MailPlaceholderEntry): MailPlaceholderGroup = when (entry.key) {
        "invoiceNumber", "quoteNumber" -> MailPlaceholderGroup.DOCUMENT
        "grossTotal", "totalGross", "iban" -> MailPlaceholderGroup.AMOUNTS
        "invoiceDate", "dueDate", "quoteDate", "validUntil" -> MailPlaceholderGroup.DATES
        "senderName", "senderPhone", "senderEmail", "senderWebsite" -> MailPlaceholderGroup.SENDER
        "recipientName" -> MailPlaceholderGroup.RECIPIENT
        else -> when (entry.type) {
            MailPlaceholderType.MONEY -> MailPlaceholderGroup.AMOUNTS
            MailPlaceholderType.DATE -> MailPlaceholderGroup.DATES
            MailPlaceholderType.TEXT -> MailPlaceholderGroup.OTHER
        }
    }

    /** The document family whose preview the component renders in [language]. */
    fun familyOf(language: MailTemplateLanguage): InvoiceFormat = when (language) {
        MailTemplateLanguage.DE -> InvoiceFormat.GERMAN
        MailTemplateLanguage.EN -> InvoiceFormat.INTERNATIONAL
    }

    /** [text] with every known token replaced by its sample; unknown tokens stay visible as typed. */
    fun fill(text: String, samples: Map<String, String>): String = MailPlaceholderText.resolve(text, samples)
}
