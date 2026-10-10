package de.littletrotter.application.mail.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Puts a template back to the standard text the component ships with. */
interface ResetMailTemplateUseCase : CommandEntryPoint {

    /** Appends the standard content as the new current version; earlier versions stay in the history. */
    fun reset(kind: MailTemplateKind, language: MailTemplateLanguage): MailTemplate
}
