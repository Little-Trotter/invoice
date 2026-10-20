package de.littletrotter.application.mail

import de.littletrotter.application.mail.port.inbound.MailRearchiveOutcome
import de.littletrotter.application.mail.port.inbound.RearchiveMailUseCase
import de.littletrotter.application.mail.port.outbound.MailJournalActionPort
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Rebuilds the archive package of a sent journal row through the mail component. Authorised at this
 * boundary like the journal view itself, and resolved through [Instance] because the adapter does
 * not exist when the component is not deployed.
 */
@ApplicationScoped
class RearchiveMailService @Inject constructor(
    private val actionPort: Instance<MailJournalActionPort>,
) : RearchiveMailUseCase {

    @RequiresPermission(AppPermission.MAIL_JOURNAL_VIEW)
    override fun rearchive(journalId: Long): MailRearchiveOutcome =
        if (actionPort.isResolvable) {
            actionPort.get().rearchive(journalId)
        } else {
            throw MailDispatchException(MailNotAvailable.MESSAGE)
        }
}
