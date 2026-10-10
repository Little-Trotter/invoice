package de.littletrotter.infrastructure.messaging

import de.littletrotter.shared.NatsBindRetry
import de.littletrotter.shared.NatsStreamSetup
import de.littletrotter.shared.mail.MailStreams
import de.littletrotter.shared.mail.MailSubjects
import io.nats.client.JetStream
import io.nats.client.Message
import io.quarkus.arc.Arc
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Binds the component's `mail.send.result` subject to [MailSendResultConsumer].
 *
 * Mirrors [GenerationJobNatsAdapter] (eager `@Startup` bean, explicit durable consumer, explicit
 * per-message request context) with three deliberate differences, each forced by the fact that the
 * mail streams belong to the *other* bounded context:
 *
 * 1. **The subscription is conditional, not defensive.** With `mail.enabled=false` this method
 *    returns before it touches JetStream at all: no durable consumer is created, nothing binds to
 *    [MailStreams.RESULT]. A consumer bound to a stream that no component ever creates would
 *    reconnect and log-spam forever, which is exactly what the no-ballast rule this component follows
 *    rules out. It is the same shape [GenerationJobNatsAdapter] uses for
 *    `nats.result-consumer.enabled`.
 * 2. **The stream is never created here.** Stream creation stays with the component
 *    (`MailNatsStreamSetup`); this module's own [NatsStreamSetup] must not learn the mail names,
 *    otherwise a mail-less instance would carry two empty streams it never feeds
 *    ([MailStreams]'s own KDoc). Only [NatsStreamSetup.ensureConsumer] is reused — a consumer on
 *    someone else's stream, which is what a Customer/Supplier subscription is.
 * 3. **A stream that is not there yet is retried, not a crash.** The component is deployed
 *    separately and may start after the host. Failing `@PostConstruct` would take the whole
 *    invoicing application down because an *optional* component lagged, so the host keeps running
 *    and the bind is retried every 30 seconds until the stream exists ([NatsBindRetry]).
 */
@Startup
@ApplicationScoped
class MailSendResultNatsAdapter @Inject constructor(
    @param:ConfigProperty(name = "mail.enabled", defaultValue = "false")
    private val mailEnabled: Boolean,
    @param:ConfigProperty(name = "mail.nats.subject.send-result", defaultValue = MailSubjects.SEND_RESULT)
    private val resultSubject: String,
    @param:ConfigProperty(name = "mail.nats.consumer.send-result", defaultValue = "little-trotter-invoice-mail-result-consumer")
    private val durableConsumerName: String,
) {

    @Inject
    private lateinit var connection: io.nats.client.Connection

    @Inject
    private lateinit var jetStream: JetStream

    @Inject
    private lateinit var resultConsumer: MailSendResultConsumer

    private val log = Logger.getLogger(MailSendResultNatsAdapter::class.java)

    internal var bindRetry = NatsBindRetry("the mail result consumer on ${MailStreams.RESULT}")

    /** Result projection is a single short UPDATE — the same reasoning [GenerationJobNatsAdapter]
     *  records for its own fixed value rather than deriving one from a worker-thread count. */
    private val maxAckPending = 16

    /** Test seam over the ArC request-context lifecycle, identical to
     *  [GenerationJobNatsAdapter.requestContextFactory] — see that field's KDoc for why the
     *  annotation-based variant is not enough on a pooled consume thread. */
    internal var requestContextFactory: () -> AutoCloseable = {
        val ctx = Arc.container().requestContext()
        ctx.activate()
        AutoCloseable { ctx.terminate() }
    }

    @PostConstruct
    fun subscribe() {
        if (!mailEnabled) {
            log.info("Mail component disabled (mail.enabled=false) — no consumer bound to ${MailStreams.RESULT}")
            return
        }
        bindRetry.bind(::logBindFailure) {
            NatsStreamSetup.ensureConsumer(
                connection.jetStreamManagement(),
                MailStreams.RESULT,
                NatsStreamSetup.consumerConfiguration(
                    durableName = durableConsumerName,
                    filterSubject = resultSubject,
                    maxAckPending = maxAckPending,
                ),
            )
            jetStream.getStreamContext(MailStreams.RESULT)
                .getConsumerContext(durableConsumerName)
                .consume { msg -> receive(msg) }
            log.infof("Bound mail result consumer '%s' to %s/%s", durableConsumerName, MailStreams.RESULT, resultSubject)
        }
    }

    @PreDestroy
    fun shutdown() {
        bindRetry.close()
    }

    private fun logBindFailure(e: Exception) {
        log.errorf(
            e,
            "Could not bind the mail result consumer to stream %s. " +
                "Mail send results will not be projected until this host restarts.",
            MailStreams.RESULT,
        )
    }

    /**
     * One envelope: project it and ack, or terminate it.
     *
     * `term()` rather than a DLQ publish, unlike [GenerationJobNatsAdapter]: there is no
     * host-owned DLQ stream for mail subjects, and inventing one would put mail ballast into an
     * instance's NATS server — the very thing the concept forbids. A payload this host cannot read
     * is therefore logged in full and terminated, so it neither redelivers forever nor silently
     * disappears; the component's journal still holds the authoritative record of that send.
     */
    // Boundary — the NATS consumer loop for this subject, the sole catch-all of this flow:
    // the broad catch below is the point of this method, not an oversight — same rule
    // GenerationJobNatsAdapter.receive follows.
    fun receive(msg: Message) {
        requestContextFactory().use {
            val payload = String(msg.data, Charsets.UTF_8)
            try {
                resultConsumer.consume(payload)
                msg.ack()
            } catch (e: Exception) {
                // This is the JetStream pull consumer's message handler; an uncaught exception
                // here would kill the consume thread instead of getting the poison message out of
                // the way, which is the one thing this handler exists to prevent.
                log.errorf(e, "Unprocessable mail send result, terminating message: %s", payload)
                msg.term()
            }
        }
    }
}
