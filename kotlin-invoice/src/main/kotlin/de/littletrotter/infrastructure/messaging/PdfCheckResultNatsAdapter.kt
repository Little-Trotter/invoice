package de.littletrotter.infrastructure.messaging

import com.fasterxml.jackson.core.JsonProcessingException
import de.littletrotter.application.invoice.port.inbound.PdfCheckVerdict
import de.littletrotter.application.invoice.port.inbound.RecordPdfCheckResultUseCase
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.NatsStreamSetup
import de.littletrotter.shared.documentjob.DocumentJobSubjects
import de.littletrotter.shared.documentjob.DocumentJobWireCodec
import de.littletrotter.shared.documentjob.PdfCheckResult
import io.nats.client.Connection
import io.nats.client.ConsumeOptions
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
import java.time.Duration

/**
 * Consumes the verdicts of the asynchronous veraPDF check and hands them to
 * [RecordPdfCheckResultUseCase]. The message is acknowledged only after the verdict is committed;
 * a failure leaves it for redelivery after a pause. A message that cannot be read goes to the
 * dead-letter subject, since reading it again would fail the same way.
 */
@Startup
@ApplicationScoped
class PdfCheckResultNatsAdapter @Inject constructor(
    @param:ConfigProperty(name = "nats.consumer.pdf-check-results", defaultValue = "little-trotter-invoice-pdfcheck-consumer")
    private val durableConsumerName: String,
    @param:ConfigProperty(name = "nats.result-consumer.enabled", defaultValue = "true")
    private val consumerEnabled: Boolean,
) {

    @Inject
    private lateinit var connection: Connection

    @Inject
    private lateinit var jetStream: JetStream

    @Inject
    private lateinit var recordResult: RecordPdfCheckResultUseCase

    private val log = Logger.getLogger(PdfCheckResultNatsAdapter::class.java)

    /** Same reasoning as the job result adapter: the request context, and with it the persistence
     *  session, is opened and closed per message instead of leaking into the next one. */
    internal var requestContextFactory: () -> AutoCloseable = {
        val ctx = Arc.container().requestContext()
        ctx.activate()
        AutoCloseable { ctx.terminate() }
    }

    @PostConstruct
    fun subscribe() {
        if (!consumerEnabled) {
            log.info("Result consumer disabled via nats.result-consumer.enabled=false — skipping PDF check subscription")
            return
        }
        NatsStreamSetup.ensureConsumer(
            connection.jetStreamManagement(),
            NatsStreamSetup.STREAM_NAME,
            NatsStreamSetup.consumerConfiguration(
                durableName = durableConsumerName,
                filterSubject = DocumentJobSubjects.PDF_CHECK_RESULTS,
                maxAckPending = MAX_ACK_PENDING,
            ),
        )
        jetStream.getStreamContext(NatsStreamSetup.STREAM_NAME).getConsumerContext(durableConsumerName)
            .consume(consumeOptions()) { receive(it) }
    }

    /** A batch of one never crosses jnats's re-pull threshold: only the first verdict of each pull
     *  would arrive, the rest after the pull expired. */
    internal fun consumeOptions(): ConsumeOptions = ConsumeOptions.builder().batchSize(PULL_BATCH_SIZE).build()

    fun receive(msg: Message) {
        requestContextFactory().use {
            val verdict = try {
                toVerdict(DocumentJobWireCodec.decodePdfCheckResult(String(msg.data, Charsets.UTF_8)))
            } catch (e: JsonProcessingException) {
                log.errorf("Unreadable PDF check result, moved to the dead-letter subject: %s", e.message)
                deadLetter(msg)
                return
            } catch (e: IllegalArgumentException) {
                log.errorf("Invalid PDF check result, moved to the dead-letter subject: %s", e.message)
                deadLetter(msg)
                return
            }
            // A failed commit must not end the consume thread, and the verdict is not lost: the
            // message stays in the stream and is delivered again after the pause.
            val recorded = runCatching { recordResult.record(verdict) }
            recorded.onSuccess { msg.ack() }
            recorded.onFailure { e ->
                log.errorf(e, "PDF check result for job %d not recorded, redelivery follows", verdict.jobId)
                msg.nakWithDelay(Duration.ofSeconds(RETRY_DELAY_SECONDS))
            }
        }
    }

    private fun toVerdict(result: PdfCheckResult): PdfCheckVerdict {
        val jobId = requireNotNull(result.jobId.toLongOrNull()) { "job id '${result.jobId}' is not a number" }
        val outcome = KositOutcome.entries.firstOrNull { it.name == result.outcome }
        return PdfCheckVerdict(
            jobId = jobId,
            outcome = outcome ?: KositOutcome.ERROR,
            validator = result.validator,
            findings = result.findings,
            // An outcome this version does not know is a check that could not be evaluated.
            engineError = if (outcome == null) "unbekanntes Ergebnis '${result.outcome}'" else result.engineError,
        )
    }

    private fun deadLetter(msg: Message) {
        try {
            jetStream.publish(DocumentJobSubjects.PDF_CHECKS_DLQ, msg.data)
            msg.ack()
        } catch (e: IOException) {
            log.errorf(e, "Dead-letter publish failed, message stays for redelivery")
            msg.nakWithDelay(Duration.ofSeconds(RETRY_DELAY_SECONDS))
        } catch (e: JetStreamApiException) {
            log.errorf(e, "Dead-letter publish rejected, message stays for redelivery")
            msg.nakWithDelay(Duration.ofSeconds(RETRY_DELAY_SECONDS))
        }
    }

    internal companion object {
        const val MAX_ACK_PENDING = 4
        const val PULL_BATCH_SIZE = 2
        const val RETRY_DELAY_SECONDS = 10L
    }
}
