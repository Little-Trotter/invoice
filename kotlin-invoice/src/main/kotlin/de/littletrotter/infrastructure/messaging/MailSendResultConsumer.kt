package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.mail.MailProjectionOutcome
import de.littletrotter.application.mail.MailSendResultProjection
import de.littletrotter.infrastructure.messaging.acl.MailSendResultTranslator
import de.littletrotter.shared.mail.MailContractViolationException
import de.littletrotter.shared.mail.MailDocumentKind
import de.littletrotter.shared.mail.MailSendResultEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * The business half of the `mail.send.result` subscription: decodes one envelope and
 * hands it to [MailSendResultProjection].
 *
 * Split from [MailSendResultNatsAdapter] the same way [GenerationJobResultConsumer] is split from
 * [GenerationJobNatsAdapter]: this class knows the payload, not the transport, so every behaviour
 * that matters (malformed payload, unknown reference, redelivery) is unit-testable without a
 * broker.
 *
 * **Malformed payloads are rejected loudly, not swallowed.** The sibling consumer for generation
 * job results logs a "DLQ (simulated)" line and returns, because a broken render result degrades
 * to "no report". Here the payload decides whether a customer-facing document counts as sent, so
 * [MailSendResultEvent.decode]'s [MailContractViolationException] is allowed to propagate: the
 * adapter above routes the message to its DLQ subject instead of acknowledging a payload nobody
 * could read (see [MailContractViolationException]'s own KDoc for the defect that rule exists to
 * prevent).
 */
@ApplicationScoped
class MailSendResultConsumer @Inject constructor(
    private val projection: MailSendResultProjection,
) {

    private val log = Logger.getLogger(MailSendResultConsumer::class.java)

    /**
     * Decodes [raw] and projects it. Returns what the projection did, so the adapter's tests can
     * assert a redelivery changed nothing; throws [MailContractViolationException] on a payload
     * that violates the contract.
     */
    fun consume(raw: String): MailProjectionOutcome {
        val event = MailSendResultEvent.decode(raw)
        if (event.documentKind != MailDocumentKind.INVOICE_DISPATCH) {
            // The result stream is shared with the offer module; its keys are not invoice numbers.
            log.debugf("Ignoring mail send result of kind %s (journal %d)", event.documentKind, event.journalId)
            return MailProjectionOutcome.OTHER_DOCUMENT_KIND
        }
        val report = MailSendResultTranslator.toDeliveryReport(event)
        val outcome = projection.project(report)
        log.debugf("Projected mail send result (journal %d, reference '%s'): %s", report.journalId, report.referenceKey, outcome)
        return outcome
    }
}
