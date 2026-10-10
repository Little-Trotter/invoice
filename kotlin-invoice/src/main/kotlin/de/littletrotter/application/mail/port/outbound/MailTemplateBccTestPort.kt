package de.littletrotter.application.mail.port.outbound

import de.littletrotter.application.mail.port.inbound.MailBccTestResult
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage

/** What the application needs from the mail component to try out a BCC address. */
fun interface MailTemplateBccTestPort {

    /** Mails a test message to [address]. */
    fun sendBccTest(kind: MailTemplateKind, language: MailTemplateLanguage, address: String): MailBccTestResult
}
