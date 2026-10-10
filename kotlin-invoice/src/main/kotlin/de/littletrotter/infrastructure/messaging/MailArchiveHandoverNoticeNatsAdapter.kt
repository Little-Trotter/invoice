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
 * Binds the component's `mail.archive-handover.notice` subject to [MailArchiveHandoverNoticeConsumer].
 *
 * Same shape as [MailSendResultNatsAdapter] and for the same reasons: nothing is bound while
 * `mail.enabled=false`, the stream belongs to the mail component and is never created here, and a
 * stream that is not there yet is retried instead of failing the host. The notice has its own
 * durable consumer because a durable consumer filters exactly one subject.
 */
@Startup
@ApplicationScoped
class MailArchiveHandoverNoticeNatsAdapter @Inject constructor(
    @param:ConfigProperty(name = "mail.enabled", defaultValue = "false")
    private val mailEnabled: Boolean,
    @param:ConfigProperty(name = "mail.nats.subject.archive-handover-notice", defaultValue = MailSubjects.ARCHIVE_HANDOVER_NOTICE)
    private val noticeSubject: String,
    @param:ConfigProperty(name = "mail.nats.consumer.archive-handover-notice", defaultValue = "little-trotter-invoice-mail-archive-handover-notice-consumer")
    private val durableConsumerName: String,
) {

    @Inject
    private lateinit var connection: io.nats.client.Connection

    @Inject
    private lateinit var jetStream: JetStream

    @Inject
    private lateinit var noticeConsumer: MailArchiveHandoverNoticeConsumer

    private val log = Logger.getLogger(MailArchiveHandoverNoticeNatsAdapter::class.java)

    internal var bindRetry = NatsBindRetry("the mail archive hand-over notice consumer on ${MailStreams.RESULT}")

    /** A notice is one short insert; a small fixed window is enough. */
    private val maxAckPending = 16

    /** Test seam over the ArC request-context lifecycle: a pooled consume thread has none of its own. */
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
                    filterSubject = noticeSubject,
                    maxAckPending = maxAckPending,
                ),
            )
            jetStream.getStreamContext(MailStreams.RESULT)
                .getConsumerContext(durableConsumerName)
                .consume { msg -> receive(msg) }
            log.infof("Bound mail archive hand-over notice consumer '%s' to %s/%s", durableConsumerName, MailStreams.RESULT, noticeSubject)
        }
    }

    @PreDestroy
    fun shutdown() {
        bindRetry.close()
    }

    private fun logBindFailure(e: Exception) {
        log.errorf(
            e,
            "Could not bind the mail archive hand-over notice consumer to stream %s. " +
                "Archive hand-over notices will not be shown until this host restarts.",
            MailStreams.RESULT,
        )
    }

    /**
     * One envelope: project it and ack, or terminate it. Whatever goes wrong, the message must
     * leave the stream: an uncaught failure would kill the consume thread, and a payload that
     * cannot be read would redeliver forever. It is logged in full before it is terminated.
     */
    fun receive(msg: Message) {
        requestContextFactory().use {
            val payload = String(msg.data, Charsets.UTF_8)
            runCatching { noticeConsumer.consume(payload) }
                .onSuccess { msg.ack() }
                .onFailure {
                    log.errorf(it, "Unprocessable archive hand-over notice, terminating message: %s", payload)
                    msg.term()
                }
        }
    }
}
