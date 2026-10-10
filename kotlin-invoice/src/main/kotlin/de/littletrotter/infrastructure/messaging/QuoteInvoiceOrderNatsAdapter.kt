package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.shared.NatsStreamSetup
import de.littletrotter.shared.quote.QuoteContractViolationException
import de.littletrotter.shared.quote.QuoteStreams
import de.littletrotter.shared.quote.QuoteSubjects
import io.nats.client.JetStream
import io.nats.client.JetStreamApiException
import io.nats.client.Message
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.impl.Headers
import io.nats.client.impl.NatsMessage
import io.quarkus.arc.Arc
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.PersistenceException
import jakarta.transaction.TransactionalException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.IOException
import java.sql.SQLException
import java.time.Duration

/**
 * Binds the offer service's `quote.invoice-order.requested` subject to [QuoteInvoiceOrderConsumer].
 *
 * Like [MailSendResultNatsAdapter] the subscription is conditional: without `quote.enabled` the
 * method returns before it touches JetStream, so an instance without the offer module carries no
 * consumer. The stream itself is created at connection time ([QuoteStreamSetup]).
 *
 * Deliveries are strictly one at a time (`maxAckPending = 1`): an acceptance creates numbered
 * invoices from a gapless range, and ordering the work keeps a retry of one offer from racing the
 * next. A failure is retried with a delay; after the last allowed delivery the message is archived
 * in the dead-letter stream and the clerk is told, instead of redelivering forever.
 */
@Startup
@ApplicationScoped
class QuoteInvoiceOrderNatsAdapter @Inject constructor(
    @param:ConfigProperty(name = "quote.enabled", defaultValue = "false")
    private val quoteEnabled: Boolean,
    @param:ConfigProperty(name = "quote.nats.consumer.invoice-order", defaultValue = "little-trotter-invoice-quote-invoice-order-consumer")
    private val durableConsumerName: String,
    @param:ConfigProperty(name = "nats.subject.document-job-results-dlq", defaultValue = "document-job-results-dlq")
    private val dlqSubject: String,
) {

    @Inject
    private lateinit var connection: io.nats.client.Connection

    @Inject
    private lateinit var jetStream: JetStream

    @Inject
    private lateinit var consumer: QuoteInvoiceOrderConsumer

    private val log = Logger.getLogger(QuoteInvoiceOrderNatsAdapter::class.java)

    /** Test seam over the ArC request-context lifecycle, same shape as [MailSendResultNatsAdapter]. */
    internal var requestContextFactory: () -> AutoCloseable = {
        val ctx = Arc.container().requestContext()
        ctx.activate()
        AutoCloseable { ctx.terminate() }
    }

    @PostConstruct
    fun subscribe() {
        if (!quoteEnabled) {
            log.info("Offer module disabled (quote.enabled=false) - no consumer bound to ${QuoteStreams.INVOICE_ORDER}")
            return
        }
        try {
            NatsStreamSetup.ensureConsumer(connection.jetStreamManagement(), QuoteStreams.INVOICE_ORDER, consumerConfiguration(durableConsumerName))
            jetStream.getStreamContext(QuoteStreams.INVOICE_ORDER)
                .getConsumerContext(durableConsumerName)
                .consume { msg -> receive(msg) }
            log.infof("Bound quote invoice order consumer '%s' to %s", durableConsumerName, QuoteStreams.INVOICE_ORDER)
        } catch (e: IOException) {
            logBindFailure(e)
        } catch (e: JetStreamApiException) {
            logBindFailure(e)
        }
    }

    private fun logBindFailure(e: Exception) {
        log.errorf(e, "Could not bind the quote invoice order consumer to stream %s - invoice orders will not become invoice drafts until restart", QuoteStreams.INVOICE_ORDER)
    }

    // Boundary - the NATS consumer loop for this subject. The failures that can leave a message
    // unprocessed (offer service down, database trouble, an inconsistent state) are retried; a
    // contract violation is terminal. Anything else is a defect and surfaces in the client's
    // error listener, the unacknowledged message returning after its ack wait.
    fun receive(msg: Message) {
        requestContextFactory().use {
            try {
                consumer.consume(msg.data)
                msg.ack()
            } catch (e: QuoteContractViolationException) {
                log.errorf(e, "Unreadable accepted-offer payload, terminating message: %s", String(msg.data, Charsets.UTF_8))
                msg.term()
            } catch (e: QuoteServiceException) {
                retryOrPark(msg, e)
            } catch (e: PersistenceException) {
                retryOrPark(msg, e)
            } catch (e: TransactionalException) {
                retryOrPark(msg, e)
            } catch (e: SQLException) {
                retryOrPark(msg, e)
            } catch (e: IllegalStateException) {
                retryOrPark(msg, e)
            }
        }
    }

    private fun retryOrPark(msg: Message, cause: Exception) {
        if (deliveries(msg) < MAX_DELIVER) {
            log.warnf(cause, "Invoice order could not be processed, redelivering in %s", RETRY_DELAY)
            msg.nakWithDelay(RETRY_DELAY)
            return
        }
        log.errorf(cause, "Invoice order failed %d times, archiving it in the dead-letter stream", MAX_DELIVER)
        if (archive(msg)) {
            consumer.reportParked(msg.data)
            msg.term()
        } else {
            msg.nakWithDelay(RETRY_DELAY)
        }
    }

    /** `false` when the dead-letter stream did not take the message; it then stays in the queue. */
    private fun archive(msg: Message): Boolean =
        try {
            val headers = Headers().add(ORIGIN_HEADER, QuoteSubjects.INVOICE_ORDER_REQUESTED)
            jetStream.publish(NatsMessage.builder().subject(dlqSubject).headers(headers).data(msg.data).build())
            true
        } catch (e: IOException) {
            log.error("Dead-letter publish failed, keeping the accepted offer in the queue", e)
            false
        } catch (e: JetStreamApiException) {
            log.error("Dead-letter publish failed, keeping the accepted offer in the queue", e)
            false
        }

    /** A message that is not a JetStream delivery reads as the first one, never as exhausted. */
    private fun deliveries(msg: Message): Long =
        try {
            msg.metaData().deliveredCount()
        } catch (e: IllegalStateException) {
            log.debug("Message carries no JetStream metadata, treating it as a first delivery", e)
            1L
        }

    private fun consumerConfiguration(durableName: String): ConsumerConfiguration =
        ConsumerConfiguration.builder()
            .durable(durableName)
            .filterSubject(QuoteSubjects.INVOICE_ORDER_REQUESTED)
            .maxAckPending(MAX_ACK_PENDING)
            .ackWait(Duration.ofSeconds(ACK_WAIT_SECONDS))
            .maxDeliver(MAX_DELIVER)
            .build()
}

private const val MAX_ACK_PENDING = 1L
private const val MAX_DELIVER = 5L
private const val ACK_WAIT_SECONDS = 60L
private const val ORIGIN_HEADER = "Origin-Subject"
private val RETRY_DELAY: Duration = Duration.ofSeconds(30)
