package de.littletrotter.application.mail.port.outbound

import de.littletrotter.application.mail.port.inbound.MailTemplate
import de.littletrotter.application.mail.port.inbound.MailTemplateDraft
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.mail.port.inbound.MailTestToMeResult

/**
 * What the application needs from the mail component to change templates. Implemented by the HTTP
 * adapter; present only when the component is part of the instance.
 */
interface MailTemplateWritePort {

    /** Stores [draft] as the new current version of its template. */
    fun save(draft: MailTemplateDraft): MailTemplate

    /** Copies the content of an older [version] forward as the new current one. */
    fun restoreVersion(kind: MailTemplateKind, language: MailTemplateLanguage, version: Int): MailTemplate

    /** Sends the rendered template to the acting user's own address. */
    fun sendTestToMe(kind: MailTemplateKind, language: MailTemplateLanguage): MailTestToMeResult
}
