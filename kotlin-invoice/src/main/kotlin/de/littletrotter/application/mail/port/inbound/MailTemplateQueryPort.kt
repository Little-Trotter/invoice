package de.littletrotter.application.mail.port.inbound

/** Reads the mail templates kept by the mail component: the current ones and each one's history. */
interface MailTemplateQueryPort {

    /** The current version of every template, ordered by kind and language. */
    fun listTemplates(): List<MailTemplate>

    /** The current version of one template, or `null` when that kind/language has none. */
    fun findTemplate(kind: MailTemplateKind, language: MailTemplateLanguage): MailTemplate?

    /** Every stored version of one template, newest first. */
    fun listVersions(kind: MailTemplateKind, language: MailTemplateLanguage): List<MailTemplateVersion>
}
