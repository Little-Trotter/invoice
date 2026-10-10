package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.ListItem
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.html.UnorderedList
import de.littletrotter.application.mail.port.inbound.MailJournalAttachment
import de.littletrotter.application.mail.port.inbound.MailJournalEntry
import de.littletrotter.ui.i18n.TranslationProvider
import java.text.NumberFormat
import java.util.Locale

/**
 * The panel under an expanded journal row: what the SMTP server answered, the message id, the
 * attachments with a shortened hash, the archive key and where the row sits in a retry chain.
 * Empty facts are left out rather than shown as dashes, so a freshly queued row shows little.
 */
internal class MailJournalDetails(private val translations: TranslationProvider) {

    private fun tr(key: String, vararg params: Any): String = translations.tr(key, *params)

    /**
     * Builds the panel for [entry]; [siblings] are the rows on the current page, searched for
     * successors because the component only records the link from a retry back to its original.
     */
    fun build(entry: MailJournalEntry, siblings: List<MailJournalEntry>): Component =
        Div().apply {
            addClassName("mail-journal-details")
            entry.smtpResult?.let { add(fact("mail.journal.details.smtp", it)) }
            entry.messageId?.let { add(fact("mail.journal.details.messageId", it)) }
            entry.archiveS3Key?.let { add(fact("mail.journal.details.archiveKey", it)) }
            chainText(entry, siblings)?.let { add(fact("mail.journal.details.chain", it)) }
            if (entry.attachments.isNotEmpty()) add(attachmentList(entry.attachments))
        }

    private fun fact(labelKey: String, value: String): Div =
        Div(Span(tr(labelKey)).apply { addClassName("mail-journal-details-label") }, Span(" $value"))

    private fun chainText(entry: MailJournalEntry, siblings: List<MailJournalEntry>): String? {
        val parts = buildList {
            entry.retryOfJournalId?.let { add(tr("mail.journal.details.chain.retryOf", it)) }
            val successors = siblings.filter { it.retryOfJournalId == entry.id }.map { it.id }
            if (successors.isNotEmpty()) add(tr("mail.journal.details.chain.retriedBy", successors.joinToString(", ") { "#$it" }))
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun attachmentList(attachments: List<MailJournalAttachment>): Component =
        Div(
            Span(tr("mail.journal.details.attachments")).apply { addClassName("mail-journal-details-label") },
            UnorderedList().apply {
                attachments.forEach { add(ListItem(attachmentText(it))) }
            },
        )

    private fun attachmentText(attachment: MailJournalAttachment): String {
        val size = attachment.sizeBytes?.let { formatSize(it) } ?: tr("mail.journal.details.sizeUnknown")
        return "${attachment.filename} · $size · SHA-256 ${shortHash(attachment.sha256)}"
    }

    companion object {
        private const val SHORT_HASH_CHARS = 12
        private const val BYTES_PER_KIB = 1024.0

        /** Leading characters are enough to tell two attachments apart on screen; the full hash stays in the archive. */
        fun shortHash(sha256: String): String =
            if (sha256.length > SHORT_HASH_CHARS) "${sha256.take(SHORT_HASH_CHARS)}…" else sha256

        fun formatSize(bytes: Long): String =
            if (bytes < BYTES_PER_KIB) {
                "$bytes B"
            } else {
                NumberFormat.getNumberInstance(Locale.GERMANY).apply {
                    minimumFractionDigits = 1
                    maximumFractionDigits = 1
                }.format(bytes / BYTES_PER_KIB) + " KB"
            }
    }
}
