package de.littletrotter.infrastructure.mail

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.mail.port.inbound.MailSendCommand
import de.littletrotter.application.mail.port.inbound.MailSendOutcome
import de.littletrotter.application.mail.port.outbound.MailDeliveryPort
import de.littletrotter.infrastructure.mail.acl.MailTemplateTranslator
import de.littletrotter.shared.mail.MailSendResult
import de.littletrotter.shared.mail.MailWireCodec
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/**
 * `POST /mail/api/send`: hands the confirmed mail to the component, which answers 202 once the
 * journal row exists.
 *
 * No retry: the request carries no idempotency key, so a repeated send after a lost answer would
 * mail the customer twice. A failure is shown to the user, who can check the journal first.
 */
@LookupIfProperty(name = "mail.enabled", stringValue = "true")
@ApplicationScoped
class HttpMailSendAdapter @Inject constructor(
    private val apiClient: MailApiClient,
) : MailDeliveryPort {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun send(command: MailSendCommand): MailSendOutcome =
        MailTemplateTranslator.toSendOutcome(
            apiClient.post(
                "/send",
                MailWireCodec.encode(MailTemplateTranslator.toSendRequest(command)),
                "send result",
                object : TypeReference<MailSendResult>() {},
            ),
        )
}
