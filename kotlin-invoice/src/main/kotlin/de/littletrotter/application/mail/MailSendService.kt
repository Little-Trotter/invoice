package de.littletrotter.application.mail

import de.littletrotter.application.mail.port.inbound.MailSendCommand
import de.littletrotter.application.mail.port.inbound.MailSendOutcome
import de.littletrotter.application.mail.port.inbound.MailSendUseCase
import de.littletrotter.application.mail.port.outbound.MailDeliveryPort
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Hands a confirmed mail to the component. Authorised at this boundary (the component checks the
 * forwarded groups again, but must not be the only line of defence) and resolved through
 * [Instance] because the adapter does not exist when the component is not deployed.
 */
@ApplicationScoped
class MailSendService @Inject constructor(
    private val deliveryPort: Instance<MailDeliveryPort>,
) : MailSendUseCase {

    @RequiresPermission(AppPermission.MAIL_COMPOSE_AND_SEND)
    override fun send(command: MailSendCommand): MailSendOutcome =
        if (deliveryPort.isResolvable) {
            deliveryPort.get().send(command)
        } else {
            throw MailDispatchException(MailNotAvailable.MESSAGE)
        }
}

/** Shared wording for "this instance has no mail component". */
internal object MailNotAvailable {
    const val MESSAGE: String = "The email component is not part of this instance"
}
