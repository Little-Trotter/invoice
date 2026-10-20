package de.littletrotter.infrastructure.messaging

import de.littletrotter.infrastructure.messaging.masterdata.MasterdataStreamSetup
import de.littletrotter.shared.NatsConnections
import de.littletrotter.shared.NatsJobResultSubjects
import de.littletrotter.shared.documentjob.DocumentJobSubjects
import io.nats.client.Connection
import io.nats.client.JetStream
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Single shared NATS connection for the application, backing the two JetStream streams that
 * kotlin-xpdf also covers: the main WorkQueue stream (document-jobs / document-job-results) and
 * the DLQ stream (document-jobs-dlq / document-job-results-dlq). WorkQueue retention on
 * the main stream mirrors the previous Kafka consumer-group-exclusive semantics; both streams'
 * limits keep storage bounded on the Pi. The actual connect-and-ensure-streams sequence lives in
 * [NatsConnections], shared with kotlin-xpdf's own producer; this class only supplies
 * this app's config property names and CDI bean lifecycle. With `quote.enabled` it also creates
 * the `MASTERDATA` stream the offer module replicates from.
 */
@ApplicationScoped
class NatsConnectionProducer @Inject constructor(
    @param:ConfigProperty(name = "nats.url", defaultValue = "nats://localhost:4222")
    private val natsUrl: String,
    @param:ConfigProperty(name = "nats.subject.document-jobs", defaultValue = "document-jobs")
    private val jobsSubject: String,
    @param:ConfigProperty(name = "nats.subject.document-job-results", defaultValue = "document-job-results")
    private val resultsSubject: String,
    @param:ConfigProperty(name = "nats.subject.document-jobs-dlq", defaultValue = "document-jobs-dlq")
    private val jobsDlqSubject: String,
    @param:ConfigProperty(name = "nats.subject.document-job-results-dlq", defaultValue = "document-job-results-dlq")
    private val resultsDlqSubject: String,
    @param:ConfigProperty(name = "quote.enabled", defaultValue = "false")
    private val quoteEnabled: Boolean,
    private val reconnects: NatsReconnectListener,
) {

    private lateinit var connection: Connection

    // Set by CDI through the initializer method below rather than as a further constructor
    // parameter (detekt parameter limit); tests that build the producer directly keep the contract default.
    private var interactiveJobsSubject: String = DocumentJobSubjects.INTERACTIVE_JOBS

    /** Subject of the interactive lane; it has to be part of the work-queue stream. */
    @Inject
    fun configureInteractiveJobsSubject(
        @ConfigProperty(name = "nats.subject.document-jobs-interactive", defaultValue = DocumentJobSubjects.INTERACTIVE_JOBS)
        subject: String,
    ) {
        interactiveJobsSubject = subject
    }

    @PostConstruct
    fun connect() {
        connection = NatsConnections.connect(
            natsUrl,
            NatsJobResultSubjects(jobsSubject, resultsSubject),
            NatsJobResultSubjects(jobsDlqSubject, resultsDlqSubject),
            interactiveJobsSubject,
            onReconnected = reconnects::reconnected,
        )
        // Created before the first outbox publish can happen; an instance without the offer
        // module carries no master data stream.
        if (quoteEnabled) {
            MasterdataStreamSetup.ensure(connection.jetStreamManagement())
            QuoteStreamSetup.ensure(connection.jetStreamManagement())
        }
    }

    @Produces
    fun produceConnection(): Connection = connection

    @Produces
    fun produceJetStream(): JetStream = connection.jetStream()

    @PreDestroy
    fun close() {
        if (::connection.isInitialized) connection.close()
    }
}
