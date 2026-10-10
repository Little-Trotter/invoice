package de.littletrotter.application.mail

import de.littletrotter.application.mail.port.inbound.MailBccTestResult
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.mail.port.inbound.SendMailTemplateBccTestUseCase
import de.littletrotter.application.mail.port.outbound.MailTemplateBccTestPort
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Mails a test message to a BCC address. Authorised at this boundary and resolved through
 * [Instance] because the adapter does not exist when the component is not deployed.
 */
@ApplicationScoped
class SendMailTemplateBccTestService @Inject constructor(
    private val bccTestPort: Instance<MailTemplateBccTestPort>,
) : SendMailTemplateBccTestUseCase {

    @RequiresPermission(AppPermission.MAIL_TEMPLATE_EDIT)
    override fun sendBccTest(kind: MailTemplateKind, language: MailTemplateLanguage, address: String): MailBccTestResult =
        if (bccTestPort.isResolvable) {
            bccTestPort.get().sendBccTest(kind, language, address)
        } else {
            throw MailDispatchException(MailNotAvailable.MESSAGE)
        }
}
