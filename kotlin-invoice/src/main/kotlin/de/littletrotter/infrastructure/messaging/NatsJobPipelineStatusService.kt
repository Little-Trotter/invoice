package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.system.JobPipelineStatus
import de.littletrotter.application.system.JobPipelineStatusMapper
import de.littletrotter.application.system.JobPipelineStatusService
import de.littletrotter.application.system.TransportSnapshot
import de.littletrotter.application.system.WorkerPresenceWindow
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.shared.NatsStreamSetup
import io.nats.client.Connection
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamManagement
import io.nats.client.api.ConsumerInfo
import io.nats.client.support.NatsJetStreamConstants
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.IOException
import de.littletrotter.shared.time.TimeSource

/**
 * The real, NATS+Postgres-backed [JobPipelineStatusService]. Jobs are counted in one SQL
 * round trip ([GenerationJobRepository.pipelineCounts]); the transport line is one
 * `getConsumerInfo` for the worker consumer, one for the result consumer, one `getStreamInfo` for
 * the DLQ. Any failure reaching the broker at all degrades the whole [TransportSnapshot] to
 * `null` — [JobPipelineStatusMapper.map] then still returns the DB-authoritative job stages, with
 * [JobPipelineStatus.TransportLine.reachable] `false` for the view to show "nicht erreichbar".
 */
@ApplicationScoped
class NatsJobPipelineStatusService @Inject constructor(
    private val connection: Connection,
    private val generationJobRepository: GenerationJobRepository,
    private val workerPresenceWindow: WorkerPresenceWindow,
    private val time: TimeSource,
    @param:ConfigProperty(name = "nats.consumer.document-jobs", defaultValue = "kotlin-xpdf-group")
    private val workerConsumerName: String,
    @param:ConfigProperty(name = "nats.consumer.document-jobs-interactive", defaultValue = "kotlin-xpdf-interactive")
    private val interactiveConsumerName: String,
    @param:ConfigProperty(name = "nats.consumer.document-job-results", defaultValue = "little-trotter-invoice-result-consumer")
    private val resultConsumerName: String,
) : JobPipelineStatusService {

    private val log = Logger.getLogger(javaClass)

    override fun currentStatus(): JobPipelineStatus {
        val now = time.nowUtc()
        val counts = generationJobRepository.pipelineCounts(now.minusHours(HOURS_WINDOW), now.minusMinutes(MINUTES_WINDOW))
        val transport = fetchTransport()
        return JobPipelineStatusMapper.map(counts, transport, now)
    }

    // Narrowed to what connection.jetStreamManagement()/getConsumerInfo actually declare,
    // the same two types resultConsumerPending/dlqStreamState below already narrow to; nothing is
    // swallowed, ex.message is logged before degrading to "nicht erreichbar".
    private fun fetchTransport(): TransportSnapshot? = try {
        val jsm = connection.jetStreamManagement()
        val bulk = jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, workerConsumerName)
        val interactive = interactiveConsumerInfo(jsm)
        val resultPending = resultConsumerPending(jsm)
        val dlq = dlqStreamState(jsm)

        val rawActive = bulk.numWaiting > 0 || bulk.numAckPending > 0 ||
            (interactive?.let { it.numWaiting > 0 || it.numAckPending > 0 } ?: false)
        TransportSnapshot(
            queueDepth = bulk.numPending + (interactive?.numPending ?: 0L),
            ackPending = bulk.numAckPending + (interactive?.numAckPending ?: 0L),
            unprocessedResults = resultPending,
            redelivered = bulk.redelivered + (interactive?.redelivered ?: 0L),
            workerConnected = workerPresenceWindow.observe(rawActive),
            dlqMessages = dlq?.first ?: 0,
            dlqBytes = dlq?.second ?: 0,
        )
    } catch (ex: JetStreamApiException) {
        log.warn("NATS pipeline transport check failed, degrading to DB-only view: ${ex.message}", ex)
        null
    } catch (ex: IOException) {
        log.warn("NATS pipeline transport check failed, degrading to DB-only view: ${ex.message}", ex)
        null
    }

    // The interactive consumer exists only once a worker with the interactive lane has started;
    // its absence is no broker failure, it simply contributes nothing to the transport line.
    private fun interactiveConsumerInfo(jsm: JetStreamManagement): ConsumerInfo? = try {
        jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, interactiveConsumerName)
    } catch (e: JetStreamApiException) {
        if (e.apiErrorCode != NatsJetStreamConstants.JS_CONSUMER_NOT_FOUND_ERR) throw e
        log.debugf(e, "No interactive durable consumer '%s' yet -- lane not counted", interactiveConsumerName)
        null
    }

    private fun resultConsumerPending(jsm: JetStreamManagement): Long = try {
        jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, resultConsumerName).numPending
    } catch (e: JetStreamApiException) {
        log.warn("NATS result consumer info unavailable, reporting 0 unprocessed results: ${e.message}", e)
        0L
    } catch (e: IOException) {
        log.warn("NATS result consumer info unavailable, reporting 0 unprocessed results: ${e.message}", e)
        0L
    }

    private fun dlqStreamState(jsm: JetStreamManagement): Pair<Long, Long>? = try {
        val info = jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)
        info.streamState.msgCount to info.streamState.byteCount
    } catch (e: JetStreamApiException) {
        log.warn("NATS DLQ stream info unavailable, reporting 0/0: ${e.message}", e)
        null
    } catch (e: IOException) {
        log.warn("NATS DLQ stream info unavailable, reporting 0/0: ${e.message}", e)
        null
    }

    private companion object {
        const val HOURS_WINDOW: Long = 24
        const val MINUTES_WINDOW: Long = 5
    }
}
