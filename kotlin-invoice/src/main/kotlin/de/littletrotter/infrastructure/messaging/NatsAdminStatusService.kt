package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.application.system.NatsStatusService
import de.littletrotter.shared.NatsStreamSetup
import io.nats.client.Connection
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamManagement
import io.nats.client.api.StreamInfoOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.IOException

@ApplicationScoped
class NatsAdminStatusService @Inject constructor(
    private val connection: Connection,
    private val publisher: DocumentJobPublisher,
    @param:ConfigProperty(name = "nats.url", defaultValue = "nats://localhost:4222")
    private val natsUrl: String,
    // One entry per reported subject as `subject=durable-consumer`, in display order. Each lane of
    // the job stream has its own durable consumer (bulk and interactive), so each row shows the
    // backlog of the consumer that actually serves that subject. The durable names must agree with
    // the consumers kotlin-xpdf and the result consumer create.
    @param:ConfigProperty(
        name = "system.nats.subject-consumers",
        defaultValue = "document-jobs=kotlin-xpdf-group," +
            "document-jobs-interactive=kotlin-xpdf-interactive," +
            "document-job-results=little-trotter-invoice-result-consumer",
    )
    private val subjectConsumers: String,
) : NatsStatusService {

    private val log = Logger.getLogger(NatsAdminStatusService::class.java)

    override fun currentStatus(): NatsStatusService.NatsStatus {
        val consumersBySubject = parseSubjectConsumers()
        val subjects = consumersBySubject.keys.toList()
        val sentMessages = publisher.currentSentCount()

        return try {
            val jsm = connection.jetStreamManagement()
            val streamInfo = jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME, StreamInfoOptions.builder().allSubjects().build())
            val subjectCounts = streamInfo.streamState.subjectMap

            val subjectStatuses = consumersBySubject.map { (subject, consumer) ->
                NatsStatusService.SubjectStatus(
                    name = subject,
                    retainedMessages = subjectCounts[subject] ?: 0L,
                    consumerLag = consumerPending(jsm, consumer),
                )
            }

            // AP-M3 §5 — DLQ visibility comes from the DLQ stream's own StreamInfo, not from a
            // subject inside the main stream (it moved out to its own stream). Queried separately
            // so a missing/unreachable DLQ stream degrades to 0/0 without failing the whole status.
            val dlqInfo = try {
                jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)
            } catch (e: JetStreamApiException) {
                // AP-EXC-1: narrowed — getStreamInfo only declares IOException/JetStreamApiException.
                // A missing/unreachable DLQ stream degrading to 0/0 is deliberate (see comment above),
                // but that degradation must be logged, not silent.
                log.warn("NATS DLQ stream info unavailable, reporting 0/0: ${e.message}", e)
                null
            } catch (e: IOException) {
                log.warn("NATS DLQ stream info unavailable, reporting 0/0: ${e.message}", e)
                null
            }

            NatsStatusService.NatsStatus(
                brokerUp = true,
                serverUrl = natsUrl,
                sentMessages = sentMessages,
                totalLag = subjectStatuses.sumOf { it.consumerLag },
                subjects = subjectStatuses,
                dlqMessages = dlqInfo?.streamState?.msgCount ?: 0,
                dlqBytes = dlqInfo?.streamState?.byteCount ?: 0,
            )
        } catch (ex: Exception) {
            // AP-EXC-1: kept broad on purpose. This is a health-check endpoint — its entire job is
            // to turn ANY failure talking to the broker (connection down, JetStream not initialized,
            // TLS handshake failure, ...) into a graceful "brokerUp=false" status for the admin UI
            // rather than propagate and break that UI. The error was already surfaced in the
            // returned status; now also logged so an operator sees it without opening the UI.
            log.warn("NATS status check failed, reporting broker down: ${ex.message}", ex)
            NatsStatusService.NatsStatus(
                brokerUp = false,
                serverUrl = natsUrl,
                sentMessages = sentMessages,
                totalLag = 0,
                subjects = subjects.map { NatsStatusService.SubjectStatus(it, 0, 0) },
                error = ex.message ?: "NATS status unavailable",
            )
        }
    }

    private fun parseSubjectConsumers(): Map<String, String> =
        subjectConsumers.split(',')
            .map(String::trim)
            .filter { it.contains('=') }
            .associate { entry -> entry.substringBefore('=').trim() to entry.substringAfter('=').trim() }

    /** Degrade-to-zero lookup for a single consumer's `numPending`. */
    private fun consumerPending(jsm: JetStreamManagement, name: String): Long =
        try {
            jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, name).numPending
        } catch (e: JetStreamApiException) {
            // AP-EXC-1: narrowed — getConsumerInfo only declares IOException/JetStreamApiException.
            // The consumer legitimately does not exist yet right after stream setup; degrading
            // to 0 pending is the whole point of this status endpoint, but it must be visible.
            log.warn("NATS consumer info unavailable for $name, reporting 0 pending: ${e.message}", e)
            0L
        } catch (e: IOException) {
            log.warn("NATS consumer info unavailable for $name, reporting 0 pending: ${e.message}", e)
            0L
        }
}
