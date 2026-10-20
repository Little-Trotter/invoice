package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.invoice.port.outbound.DelegatedMessageProbe
import de.littletrotter.application.invoice.port.outbound.DelegatedStreamSnapshot
import de.littletrotter.shared.NatsStreamSetup
import io.nats.client.Connection
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamManagement
import io.nats.client.support.NatsJetStreamConstants
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * The real, NATS-backed [DelegatedMessageProbe]. Same config key as [de.littletrotter.xpdf
 * .infrastructure.messaging.JobConsumer]'s own `nats.consumer.document-jobs` (default
 * `kotlin-xpdf-group`) — both sides must always agree on which durable consumer they mean; this
 * config key also backed the former worker-liveness signal this class replaced. The interactive
 * lane has its own durable consumer ([interactiveConsumerName]) over the same stream.
 */
@ApplicationScoped
class NatsDelegatedMessageProbe @Inject constructor(
    private val connection: Connection,
    @param:ConfigProperty(name = "nats.consumer.document-jobs", defaultValue = "kotlin-xpdf-group")
    private val workerConsumerName: String,
    @param:ConfigProperty(name = "nats.consumer.document-jobs-interactive", defaultValue = "kotlin-xpdf-interactive")
    private val interactiveConsumerName: String,
) : DelegatedMessageProbe {
    private val log = Logger.getLogger(NatsDelegatedMessageProbe::class.java)

    /**
     * `lastSeq` is read first and propagates any failure unchanged (the stream itself must exist —
     * every candidate this is ever called for was, by construction, published to it). `delivered`
     * treats "no such consumer" specially: no lane durable has ever been created at
     * all (a fresh stack, or this module's own `@QuarkusTest`s, which never run kotlin-xpdf's
     * `JobConsumer.subscribe()`) is not a broker failure — it means nothing has ever been
     * delivered, the same "absent" default the former worker-liveness port reported for
     * this case. Any OTHER failure (broker unreachable) still propagates, same as `lastSeq` above —
     * that is what [de.littletrotter.application.invoice.StaleDelegatedResetCoordinator]'s own
     * per-tick abort exists for.
     */
    override fun snapshot(): DelegatedStreamSnapshot {
        val jsm = connection.jetStreamManagement()
        val lastSeq = jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME).streamState.lastSequence
        return DelegatedStreamSnapshot(
            deliveredStreamSeq = deliveredSeq(jsm, workerConsumerName),
            interactiveDeliveredStreamSeq = deliveredSeq(jsm, interactiveConsumerName),
            lastSeq = lastSeq,
        )
    }

    private fun deliveredSeq(jsm: JetStreamManagement, consumerName: String): Long =
        try {
            jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, consumerName).delivered.streamSequence
        } catch (e: JetStreamApiException) {
            if (e.apiErrorCode != NatsJetStreamConstants.JS_CONSUMER_NOT_FOUND_ERR) throw e
            log.debugf(e, "No durable consumer '%s' yet -- treating delivered cursor as 0", consumerName)
            0L
        }

    /** JetStream error 10037 ("no message found") is the one documented answer for a genuinely
     *  gone message (acked, purged) — see `NatsJetStreamConstants.JS_NO_MESSAGE_FOUND_ERR`. Any
     *  other [JetStreamApiException]/[java.io.IOException] propagates, never folded into `false`. */
    override fun exists(streamSeq: Long): Boolean =
        try {
            connection.jetStreamManagement().getMessage(NatsStreamSetup.STREAM_NAME, streamSeq)
            true
        } catch (e: JetStreamApiException) {
            if (e.apiErrorCode != NatsJetStreamConstants.JS_NO_MESSAGE_FOUND_ERR) throw e
            false
        }
}
