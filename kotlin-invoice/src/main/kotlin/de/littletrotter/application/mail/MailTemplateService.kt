package de.littletrotter.application.mail

import de.littletrotter.application.mail.port.inbound.MailTemplate
import de.littletrotter.application.mail.port.inbound.MailTemplateDraft
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.mail.port.inbound.MailTemplateUseCase
import de.littletrotter.application.mail.port.inbound.MailTestToMeResult
import de.littletrotter.application.mail.port.outbound.MailTemplateWritePort
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Template changes, authorised here and executed by the mail component.
 *
 * The component authorises again by the forwarded groups, but a UI that bypassed this check must
 * not depend on that: the permission is enforced at this boundary, like every other write. The
 * outbound port is resolved through [Instance] because on an instance without the component its
 * adapter is not a bean; the call then fails with a readable message instead of a CDI error.
 */
@ApplicationScoped
class MailTemplateService @Inject constructor(
    private val writePort: Instance<MailTemplateWritePort>,
) : MailTemplateUseCase {

    @RequiresPermission(AppPermission.MAIL_TEMPLATE_EDIT)
    override fun save(draft: MailTemplateDraft): MailTemplate = port().save(draft)

    @RequiresPermission(AppPermission.MAIL_TEMPLATE_EDIT)
    override fun restoreVersion(kind: MailTemplateKind, language: MailTemplateLanguage, version: Int): MailTemplate =
        port().restoreVersion(kind, language, version)

    @RequiresPermission(AppPermission.MAIL_TEMPLATE_EDIT)
    override fun sendTestToMe(kind: MailTemplateKind, language: MailTemplateLanguage): MailTestToMeResult =
        port().sendTestToMe(kind, language)

    private fun port(): MailTemplateWritePort =
        if (writePort.isResolvable) writePort.get() else throw MailDispatchException(MailNotAvailable.MESSAGE)
}
