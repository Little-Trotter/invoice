package de.littletrotter.application.mail

import de.littletrotter.application.mail.port.inbound.MailSendOutcome
import de.littletrotter.application.mail.port.inbound.RetryMailUseCase
import de.littletrotter.application.mail.port.outbound.MailJournalActionPort
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Repeats a failed journal row through the mail component. Authorised at this boundary like the
 * journal view itself, and resolved through [Instance] because the adapter does not exist when the
 * component is not deployed.
 */
@ApplicationScoped
class RetryMailService @Inject constructor(
    private val actionPort: Instance<MailJournalActionPort>,
) : RetryMailUseCase {

    @RequiresPermission(AppPermission.MAIL_JOURNAL_VIEW)
    override fun retry(journalId: Long): MailSendOutcome =
        if (actionPort.isResolvable) {
            actionPort.get().retry(journalId)
        } else {
            throw MailDispatchException(MailNotAvailable.MESSAGE)
        }
}
