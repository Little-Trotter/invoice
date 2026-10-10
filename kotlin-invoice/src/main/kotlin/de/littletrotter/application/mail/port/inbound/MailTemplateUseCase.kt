package de.littletrotter.application.mail.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Changes to the mail templates; every change is committed in the mail component before it returns. */
interface MailTemplateUseCase : CommandEntryPoint {

    /** Stores [draft] as the new current version of its template. */
    fun save(draft: MailTemplateDraft): MailTemplate

    /** Copies the content of an older [version] forward as the new current one. */
    fun restoreVersion(kind: MailTemplateKind, language: MailTemplateLanguage, version: Int): MailTemplate

    /** Sends the template, rendered with sample values, to the acting user's own address. */
    fun sendTestToMe(kind: MailTemplateKind, language: MailTemplateLanguage): MailTestToMeResult
}
