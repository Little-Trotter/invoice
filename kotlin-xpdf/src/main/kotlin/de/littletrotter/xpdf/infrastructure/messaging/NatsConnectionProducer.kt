package de.littletrotter.xpdf.infrastructure.messaging

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
 * Single shared NATS connection for kotlin-xpdf, backing the same two JetStream streams that
 * little-trotter-invoice covers: the main WorkQueue stream (document-jobs / document-job-results)
 * and the DLQ stream (document-jobs-dlq / document-job-results-dlq). Both apps race to
 * create each stream at startup; addStream failures are treated as "someone else already created
 * it" as long as getStreamInfo then succeeds. The actual connect-and-ensure-streams sequence
 * lives in [NatsConnections], shared with little-trotter-invoice's own producer; this
 * class only supplies this app's config property names and CDI bean lifecycle.
 */
@ApplicationScoped
class NatsConnectionProducer @Inject constructor(
    @param:ConfigProperty(name = "nats.url", defaultValue = "nats://localhost:4222")
    private val natsUrl: String,
    @param:ConfigProperty(name = "nats.subject.document-jobs", defaultValue = "document-jobs")
    private val jobsSubject: String,
    @param:ConfigProperty(name = "nats.subject.document-jobs-interactive", defaultValue = DocumentJobSubjects.INTERACTIVE_JOBS)
    private val interactiveJobsSubject: String,
    @param:ConfigProperty(name = "nats.subject.document-job-results", defaultValue = "document-job-results")
    private val resultsSubject: String,
    @param:ConfigProperty(name = "nats.subject.document-jobs-dlq", defaultValue = "document-jobs-dlq")
    private val jobsDlqSubject: String,
    @param:ConfigProperty(name = "nats.subject.document-job-results-dlq", defaultValue = "document-job-results-dlq")
    private val resultsDlqSubject: String,
) {

    private lateinit var connection: Connection

    @PostConstruct
    fun connect() {
        connection = NatsConnections.connect(
            natsUrl,
            NatsJobResultSubjects(jobsSubject, resultsSubject),
            NatsJobResultSubjects(jobsDlqSubject, resultsDlqSubject),
            interactiveJobsSubject,
        )
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
