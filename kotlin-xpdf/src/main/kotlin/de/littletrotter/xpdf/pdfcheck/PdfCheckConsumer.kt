package de.littletrotter.xpdf.pdfcheck

import com.fasterxml.jackson.core.JsonProcessingException
import de.littletrotter.shared.NatsStreamSetup
import de.littletrotter.shared.documentjob.DocumentJobSubjects
import de.littletrotter.shared.documentjob.DocumentJobWireCodec
import de.littletrotter.shared.documentjob.PdfCheckRequest
import de.littletrotter.shared.documentjob.PdfCheckResult
import io.nats.client.Connection
import io.nats.client.ConsumeOptions
import io.nats.client.JetStream
import io.nats.client.Message
import io.nats.client.PublishOptions
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Consumes the PDF check requests on a lane of its own: one thread, its own durable consumer, no
 * share of the export workers' memory budget. Checks run one at a time because the service they
 * call is sized for one.
 *
 * Failure handling is JetStream's: a check that could not be performed is not acknowledged but
 * redelivered after a pause. Only the last delivery gives up and publishes the failure as an
 * `ERROR` result, so the document ends as "check not possible" instead of waiting forever. A
 * request that cannot be read goes to the dead-letter subject.
 */
@Startup
@ApplicationScoped
class PdfCheckConsumer @Inject constructor(
    private val connection: Connection,
    private val jetStream: JetStream,
    private val processor: PdfCheckProcessor,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Test seam: swapped for a direct executor so a test can assert after [receive] returns. */
    internal var executor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-check").apply { isDaemon = true } }

    /** Pause before a failed check is delivered again; shortened by tests that wait for the retries. */
    internal var retryDelay: Duration = Duration.ofSeconds(RETRY_DELAY_SECONDS)

    internal var heartbeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pdf-check-heartbeat").apply { isDaemon = true }
    }

    @PostConstruct
    fun subscribe() {
        NatsStreamSetup.ensureConsumer(
            connection.jetStreamManagement(),
            NatsStreamSetup.STREAM_NAME,
            NatsStreamSetup.consumerConfiguration(
                durableName = DURABLE,
                filterSubject = DocumentJobSubjects.PDF_CHECKS,
                maxAckPending = MAX_ACK_PENDING,
                ackWaitSeconds = ACK_WAIT_SECONDS,
                maxDeliver = MAX_DELIVER,
            ),
        )
        jetStream.getStreamContext(NatsStreamSetup.STREAM_NAME).getConsumerContext(DURABLE)
            .consume(consumeOptions()) { receive(it) }
    }

    /**
     * The pull options production consumes with; the IT uses the same ones. The batch is two, not
     * one: jnats pulls again only once fewer than `batchSize - max(1, 25 % of batchSize)` messages
     * are outstanding, which a batch of one never reaches. The consumer would then receive a single
     * request per pull expiry, and a request set back for redelivery would wait for the next one.
     */
    internal fun consumeOptions(): ConsumeOptions =
        ConsumeOptions.builder().batchSize(PULL_BATCH_SIZE).expiresIn(PULL_EXPIRES_MILLIS).build()

    /** The lease is kept alive from hand-over on, so a request waiting behind a running check
     *  is not redelivered while it queues. */
    fun receive(message: Message) {
        val heartbeat = heartbeatExecutor.scheduleAtFixedRate(
            { sendProgress(message) }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS,
        )
        executor.execute {
            try {
                handle(message)
            } finally {
                heartbeat.cancel(false)
            }
        }
    }

    internal fun handle(message: Message) {
        val request = try {
            DocumentJobWireCodec.decodePdfCheckRequest(String(message.data, Charsets.UTF_8))
        } catch (e: JsonProcessingException) {
            log.error("Unreadable PDF check request, moved to the dead-letter subject: {}", e.message)
            deadLetter(message)
            return
        }
        // The object store or the stream can break mid-check: a failure of the environment, handled
        // like a service failure below.
        val result = runCatching { processor.check(request) }.getOrElse { e ->
            log.warn("PDF check {} failed: {}", request.checkId, e.message)
            failed(message, request, e.message ?: e.javaClass.simpleName)
            return
        }
        if (result.outcome == OUTCOME_ERROR) {
            failed(message, request, result.engineError ?: "unbekannter Fehler")
            return
        }
        publishAndAck(message, result)
    }

    private fun failed(message: Message, request: PdfCheckRequest, reason: String) {
        if (message.metaData().deliveredCount() < MAX_DELIVER) {
            message.nakWithDelay(retryDelay)
            return
        }
        publishAndAck(
            message,
            PdfCheckResult(
                checkId = request.checkId, jobId = request.jobId, objectKey = request.objectKey, sha256 = request.sha256,
                outcome = OUTCOME_ERROR, profile = PROFILE, validator = "veraPDF", engineError = reason,
                correlationId = request.correlationId,
            ),
        )
    }

    private fun publishAndAck(message: Message, result: PdfCheckResult) {
        try {
            // A stable id: a redelivered request that finishes a second time cannot double-apply.
            jetStream.publish(
                DocumentJobSubjects.PDF_CHECK_RESULTS,
                DocumentJobWireCodec.encodePdfCheckResult(result).toByteArray(Charsets.UTF_8),
                PublishOptions.builder().messageId("pdfcheck-${result.checkId}").build(),
            )
        } catch (e: java.io.IOException) {
            log.warn("PDF check result {} not published, request stays for redelivery: {}", result.checkId, e.message)
            message.nakWithDelay(retryDelay)
            return
        } catch (e: io.nats.client.JetStreamApiException) {
            log.warn("PDF check result {} rejected by the stream, request stays for redelivery: {}", result.checkId, e.message)
            message.nakWithDelay(retryDelay)
            return
        }
        message.ack()
    }

    private fun deadLetter(message: Message) {
        try {
            jetStream.publish(DocumentJobSubjects.PDF_CHECKS_DLQ, message.data)
            message.ack()
        } catch (e: java.io.IOException) {
            log.error("Dead-letter publish failed, request stays for redelivery: {}", e.message)
            message.nak()
        } catch (e: io.nats.client.JetStreamApiException) {
            log.error("Dead-letter publish rejected, request stays for redelivery: {}", e.message)
            message.nak()
        }
    }

    private fun sendProgress(message: Message) {
        try {
            message.inProgress()
        } catch (e: IllegalStateException) {
            log.warn("PDF check heartbeat failed: {}", e.message)
        }
    }

    companion object {
        const val DURABLE = "kotlin-xpdf-pdfcheck"
        const val MAX_DELIVER = 3
        private const val MAX_ACK_PENDING = 2
        private const val ACK_WAIT_SECONDS = 150L
        private const val HEARTBEAT_SECONDS = ACK_WAIT_SECONDS / 3
        private const val RETRY_DELAY_SECONDS = 30L
        internal const val PULL_BATCH_SIZE = 2
        private const val PULL_EXPIRES_MILLIS = 10_000L
        private const val OUTCOME_ERROR = "ERROR"
        private const val PROFILE = "PDF/A-3B"
    }
}
