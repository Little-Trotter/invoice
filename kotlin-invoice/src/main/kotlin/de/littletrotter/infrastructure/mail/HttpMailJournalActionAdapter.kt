package de.littletrotter.infrastructure.mail

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.mail.port.inbound.MailRearchiveOutcome
import de.littletrotter.application.mail.port.inbound.MailSendOutcome
import de.littletrotter.application.mail.port.outbound.MailJournalActionPort
import de.littletrotter.infrastructure.mail.acl.MailJournalTranslator
import de.littletrotter.infrastructure.mail.acl.MailTemplateTranslator
import de.littletrotter.shared.mail.MailRearchiveResult
import de.littletrotter.shared.mail.MailSendResult
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/**
 * The two journal row actions of the component: `POST /mail/api/journal/{id}/retry` and
 * `…/{id}/rearchive`.
 *
 * Neither is retried on a transport failure: a retry creates a new row and a lost answer would
 * otherwise send the customer a second copy. The user sees the error and checks the journal first.
 */
@LookupIfProperty(name = "mail.enabled", stringValue = "true")
@ApplicationScoped
class HttpMailJournalActionAdapter @Inject constructor(
    private val apiClient: MailApiClient,
) : MailJournalActionPort {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun retry(journalId: Long): MailSendOutcome =
        MailTemplateTranslator.toSendOutcome(
            apiClient.post(
                "/journal/$journalId/retry",
                null,
                "retry result",
                object : TypeReference<MailSendResult>() {},
            ),
        )

    @Timeout(value = 60_000, unit = ChronoUnit.MILLIS)
    override fun rearchive(journalId: Long): MailRearchiveOutcome =
        MailJournalTranslator.toRearchiveOutcome(
            apiClient.post(
                "/journal/$journalId/rearchive",
                null,
                "rearchive result",
                object : TypeReference<MailRearchiveResult>() {},
            ),
        )
}
