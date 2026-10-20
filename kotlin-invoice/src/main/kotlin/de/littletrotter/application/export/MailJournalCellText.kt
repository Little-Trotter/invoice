package de.littletrotter.application.export

import de.littletrotter.application.mail.port.inbound.MailJournalEntry

/**
 * The journal's combined "template" cell, shared by the list and the export so both always show
 * the same text: document kind, language and the template version the mail was rendered from.
 */
object MailJournalCellText {

    /** `Rechnungsversand · DE · v3`, with the kind label resolved through [translate]. */
    fun template(entry: MailJournalEntry, translate: (String) -> String): String =
        "${translate("mail.journal.kind.${entry.templateKind.name}")} · ${entry.templateLanguage.name} · v${entry.templateVersion}"
}
