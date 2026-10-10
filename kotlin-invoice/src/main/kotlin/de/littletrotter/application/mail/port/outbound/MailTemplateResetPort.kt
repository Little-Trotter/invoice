package de.littletrotter.application.mail.port.outbound

import de.littletrotter.application.mail.port.inbound.MailTemplate
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage

/** What the application needs from the mail component to restore a template's standard text. */
fun interface MailTemplateResetPort {

    /** Appends the standard content as the new current version. */
    fun reset(kind: MailTemplateKind, language: MailTemplateLanguage): MailTemplate
}
