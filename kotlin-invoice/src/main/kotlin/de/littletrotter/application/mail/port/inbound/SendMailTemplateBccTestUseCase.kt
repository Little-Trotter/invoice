package de.littletrotter.application.mail.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Checks that a BCC address works by mailing it a test message before the author saves it. */
interface SendMailTemplateBccTestUseCase : CommandEntryPoint {

    /** Sends the test message to [address], which need not be stored on the template yet. */
    fun sendBccTest(kind: MailTemplateKind, language: MailTemplateLanguage, address: String): MailBccTestResult
}
