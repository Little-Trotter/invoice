package de.littletrotter.application.mail

import de.littletrotter.application.mail.port.inbound.MailTemplate
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.mail.port.inbound.ResetMailTemplateUseCase
import de.littletrotter.application.mail.port.outbound.MailTemplateResetPort
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Restores a template's standard text. Authorised at this boundary and resolved through [Instance]
 * because the adapter does not exist when the component is not deployed.
 */
@ApplicationScoped
class ResetMailTemplateService @Inject constructor(
    private val resetPort: Instance<MailTemplateResetPort>,
) : ResetMailTemplateUseCase {

    @RequiresPermission(AppPermission.MAIL_TEMPLATE_EDIT)
    override fun reset(kind: MailTemplateKind, language: MailTemplateLanguage): MailTemplate =
        if (resetPort.isResolvable) {
            resetPort.get().reset(kind, language)
        } else {
            throw MailDispatchException(MailNotAvailable.MESSAGE)
        }
}
