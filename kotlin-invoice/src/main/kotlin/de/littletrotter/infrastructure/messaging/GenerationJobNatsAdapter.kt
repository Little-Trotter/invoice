package de.littletrotter.infrastructure.messaging

import de.littletrotter.shared.NatsStreamSetup
import de.littletrotter.shared.documentjob.DocumentJobSubjects
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.Message
import io.quarkus.arc.Arc
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Connects the NATS JetStream subject 'document-job-results' to the business logic in
 * GenerationJobResultConsumer. Manual ack (autoAck=false) so unrecoverable failures can be
 * routed to the DLQ subject instead of retried forever.
 *
 * @Startup forces eager CDI instantiation: unlike the previous @Incoming-based Kafka adapter
 * (auto-activated by the reactive-messaging framework), this is a plain bean and would
 * otherwise never be instantiated — its @PostConstruct subscribe() would simply never run.
 *
 * PO-Grundsatz 29.08.2026 (CDI-Injektionen nie ausnehmen) — every `@ConfigProperty` field used to
 * be pure field injection with no constructor at all, which Qodana's CDI inspector flagged.
 * Moved to constructor injection (the `@param:ConfigProperty` pattern, see
 * `GenerationJobProcessor.maxAttempts`/[NatsAdminStatusService] for the
 * same shape elsewhere): `@param:` pins the annotation to the constructor parameter only, since
 * Kotlin 2.4's default param-property annotation-use-site-target would otherwise also copy
 * `@ConfigProperty` onto the backing final field, which Qodana misreads as an illegal final-field
 * injection even though CDI never reads the field. [connection]/[jetStream]/[resultConsumer]
 * stay plain field injection — ordinary, unflagged CDI field injection of bean types, not config
 * properties, so none of the above applies to them.
 */
@Startup
@ApplicationScoped
class GenerationJobNatsAdapter @Inject constructor(
    // `private val` (not the previous `public var`/`private lateinit var`): an
    // `@ApplicationScoped` (normal-scoped) bean must not expose public mutable fields, since a
    // caller reaching one directly (bypassing the CDI client proxy) would break the proxy's scope
    // semantics — this constructor closes that gap for `resultConsumerEnabled` too, which was
    // previously public.
    @param:ConfigProperty(name = "nats.subject.document-job-results", defaultValue = DocumentJobSubjects.RESULTS)
    private val resultsSubject: String,
    @param:ConfigProperty(name = "nats.subject.document-job-results-dlq", defaultValue = DocumentJobSubjects.RESULTS_DLQ)
    private val dlqSubject: String,
    @param:ConfigProperty(name = "nats.consumer.document-job-results", defaultValue = "little-trotter-invoice-result-consumer")
    private val durableConsumerName: String,
    @param:ConfigProperty(name = "nats.result-consumer.enabled", defaultValue = "true")
    private val resultConsumerEnabled: Boolean,
) {

    @Inject
    private lateinit var connection: Connection

    @Inject
    private lateinit var jetStream: JetStream

    @Inject
    private lateinit var resultConsumer: GenerationJobResultConsumer

    private val log = Logger.getLogger(GenerationJobNatsAdapter::class.java)

    // Results are quick (no long-running heartbeat needed here, unlike kotlin-xpdf's
    // job consumer), so this is a fixed value rather than derived from a worker-thread count.
    private val maxAckPending = 16

    /**
     * Test seam over the ArC request-context lifecycle — see [receive]'s own KDoc for why this
     * replaced `@ActivateRequestContext`. Production wiring never overrides this field:
     * [Arc.container] is resolved lazily, only the first time [receive] actually runs, so
     * constructing this adapter as a plain object (every test in `GenerationJobNatsAdapterTest`,
     * no ArC container running at all) never touches it. Tests that DO exercise [receive]
     * override this field via reflection, the same way every other collaborator on this class is
     * wired in those tests.
     */
    private var requestContextFactory: () -> AutoCloseable = {
        val ctx = Arc.container().requestContext()
        ctx.activate()
        AutoCloseable { ctx.terminate() }
    }

    /**
     * Migrated from a push `Dispatcher`/`PushSubscribeOptions` subscription to
     * jnats's simplified pull API (jnats >= 2.26: `StreamContext` -> `ConsumerContext` ->
     * `consume(handler)`). The durable consumer is created/updated explicitly first, via the same
     * shared helper [NatsStreamSetup] already uses for the streams (`ensureConsumer`) — this
     * replaces the implicit consumer creation the old `PushSubscribeOptions` buried. No `ackWait`
     * override (same as before this AP): processing stays on the consume thread and is quick, so
     * the server's own default ack-wait is enough.
     */
    @PostConstruct
    fun subscribe() {
        if (!resultConsumerEnabled) {
            log.info("Result consumer disabled via nats.result-consumer.enabled=false — skipping subscription")
            return
        }
        val jsm = connection.jetStreamManagement()
        NatsStreamSetup.ensureConsumer(
            jsm,
            NatsStreamSetup.STREAM_NAME,
            NatsStreamSetup.consumerConfiguration(
                durableName = durableConsumerName,
                filterSubject = resultsSubject,
                maxAckPending = maxAckPending,
            ),
        )

        val consumerContext = jetStream.getStreamContext(NatsStreamSetup.STREAM_NAME).getConsumerContext(durableConsumerName)
        consumerContext.consume { msg -> receive(msg) }
    }

    /**
     * Bug production 2026-08-26 — `@ActivateRequestContext` is a documented no-op when a request
     * context is already active on the current thread (CDI reuse-don't-nest semantics). The
     * JetStream pull consumer's dispatch runs on a small, reused pool of threads, so a context
     * left active by an earlier call on that same thread (including [GenerationJobResultConsumer
     * .consume]'s own retry loop, which spans several transactions inside one activation — see
     * its own KDoc) could silently carry its Hibernate Session, and every entity already cached
     * in it with whatever `@Version` it held at load time, into a LATER, unrelated message.
     * [requestContextFactory] makes activation and teardown of the request context explicit and
     * unconditional per message instead of relying on the annotation's "skip if already active"
     * behavior: the returned [AutoCloseable] always tears the context down via `.use` — including
     * when the block below throws — so nothing this method does can ever leak forward into the
     * next `receive()` call.
     */
    fun receive(msg: Message) {
        requestContextFactory().use {
            val payload = String(msg.data, Charsets.UTF_8)
            log.debugf("Received job result from NATS: %s", payload)
            try {
                resultConsumer.consume(payload)
                msg.ack()
            } catch (e: Exception) {
                // AP-EXC-1: kept broad on purpose — this is the JetStream pull consumer's message
                // handler (wired in subscribe() via consumerContext.consume { receive(it) }); an
                // uncaught exception here would kill the consume thread instead of routing the
                // poison message to the DLQ, which is the whole point of this class. No counter
                // exists in this package to increment; already logs at error with the payload.
                log.errorf(e, "Unrecoverable error processing job result, sending to DLQ: %s", payload)
                publishToDlqOrNak(msg, payload)
            }
        }
    }

    /** [receive]'s DLQ fallback, extracted to keep that method's own nested block depth down. */
    private fun publishToDlqOrNak(msg: Message, payload: String) {
        // AP-EXC-1: kept broad on purpose — same message-handler reasoning as [receive], one
        // level deeper: if publishing to the DLQ itself fails (broker down, disk full),
        // this must still fall through to nak() instead of crashing the consume thread.
        try {
            jetStream.publish(dlqSubject, msg.data)
            msg.ack() // DLQ accepted — remove from main subject
        } catch (dlqEx: InterruptedException) {
            Thread.currentThread().interrupt()
            log.errorf(dlqEx, "DLQ send failed — nacking for redelivery: %s", payload)
            msg.nak()
        } catch (dlqEx: Exception) {
            log.errorf(dlqEx, "DLQ send failed — nacking for redelivery: %s", payload)
            msg.nak()
        }
    }
}
