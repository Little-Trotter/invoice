package de.littletrotter.infrastructure.messaging.masterdata

import de.littletrotter.shared.NatsStreamSetup
import de.littletrotter.shared.masterdata.MasterdataStreams
import de.littletrotter.shared.masterdata.MasterdataSubjects
import de.littletrotter.shared.masterdata.MasterdataWireCodec
import de.littletrotter.shared.masterdata.SnapshotRequested
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.JetStreamApiException
import io.nats.client.Message
import io.quarkus.arc.Arc
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.IOException

/**
 * Binds `masterdata.snapshot.requested` to [MasterdataSnapshotService].
 *
 * Only with `quote.enabled=true`: without the offer module no stream and no consumer exist. The
 * stream is created by the connection producer before this bean starts. A request that cannot be
 * read is terminated and logged; a request whose answer failed halfway is redelivered, and the
 * stream drops the chunks that were already published (message id = request + sequence).
 */
@Startup
@ApplicationScoped
class MasterdataSnapshotNatsAdapter @Inject constructor(
    private val connection: Connection,
    private val jetStream: JetStream,
    private val snapshotService: MasterdataSnapshotService,
    @param:ConfigProperty(name = "quote.enabled", defaultValue = "false")
    private val quoteEnabled: Boolean,
    @param:ConfigProperty(
        name = "masterdata.nats.consumer.snapshot-requests",
        defaultValue = "little-trotter-invoice-masterdata-snapshot",
    )
    private val durableConsumerName: String,
) {

    private val log = Logger.getLogger(MasterdataSnapshotNatsAdapter::class.java)

    /** Test seam over the request-context lifecycle of the consume thread. */
    internal var requestContextFactory: () -> AutoCloseable = {
        val ctx = Arc.container().requestContext()
        ctx.activate()
        AutoCloseable { ctx.terminate() }
    }

    @PostConstruct
    fun subscribe() {
        if (!quoteEnabled) {
            log.info("Offer module disabled (quote.enabled=false) - no master data snapshot consumer")
            return
        }
        NatsStreamSetup.ensureConsumer(
            connection.jetStreamManagement(),
            MasterdataStreams.MASTERDATA,
            NatsStreamSetup.consumerConfiguration(
                durableName = durableConsumerName,
                filterSubject = MasterdataSubjects.SNAPSHOT_REQUESTED,
                maxAckPending = MAX_ACK_PENDING,
                ackWaitSeconds = ACK_WAIT_SECONDS,
            ),
        )
        jetStream.getStreamContext(MasterdataStreams.MASTERDATA)
            .getConsumerContext(durableConsumerName)
            .consume { message -> receive(message) }
        log.infof("Bound master data snapshot consumer '%s'", durableConsumerName)
    }

    /** One request: answer it and ack, terminate it when unreadable, redeliver it when publishing failed. */
    fun receive(message: Message) {
        requestContextFactory().use {
            val payload = String(message.data, Charsets.UTF_8)
            val request = runCatching { MasterdataWireCodec.decode<SnapshotRequested>(payload) }.getOrNull()
            if (request == null) {
                log.errorf("Unreadable master data snapshot request, terminating message: %s", payload)
                message.term()
                return
            }
            try {
                val chunks = snapshotService.answer(request)
                log.infof("Master data snapshot %s answered with %d chunks", request.requestId, chunks)
                message.ack()
            } catch (e: IOException) {
                redeliver(message, request, e)
            } catch (e: JetStreamApiException) {
                redeliver(message, request, e)
            }
        }
    }

    private fun redeliver(message: Message, request: SnapshotRequested, e: Exception) {
        log.errorf(e, "Master data snapshot %s was not published completely, redelivering", request.requestId)
        message.nak()
    }

    private companion object {
        /** One snapshot at a time is plenty: it reads the whole inventory. */
        const val MAX_ACK_PENDING = 1

        /** A snapshot can take a while on a large inventory; do not redeliver it meanwhile. */
        const val ACK_WAIT_SECONDS = 300L
    }
}
