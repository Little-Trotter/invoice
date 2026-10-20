package de.littletrotter.shared

import io.nats.client.Connection
import io.nats.client.ConnectionListener
import io.nats.client.Nats
import io.nats.client.Options
import java.time.Duration

/**
 * Builds the single NATS [Connection] shared by little-trotter-invoice and kotlin-xpdf:
 * connection options (5s connect timeout, infinite reconnect, [normalizeNatsServers]-normalized
 * server list; optionally a callback once a lost connection is back), then ensures both JetStream streams exist via [NatsStreamSetup]. Before this,
 * both apps' own `NatsConnectionProducer` carried this exact sequence byte-for-byte; CDI wiring
 * (config property names, `@ApplicationScoped`/`@Produces`/`@PreDestroy` bean lifecycle) is what
 * actually differs per module and stays local to each one's own `NatsConnectionProducer`.
 *
 * [interactiveJobsSubject] is the jobs subject of the interactive lane (a document a user is
 * waiting for). It lives in the same WorkQueue stream as the bulk jobs subject but is consumed by
 * its own durable, so bulk backlog never queues in front of it; the subject has to be part of the
 * stream or published interactive jobs would be rejected.
 */
object NatsConnections {

    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
    private const val INFINITE_RECONNECTS = -1

    fun connect(
        natsUrl: String,
        workQueue: NatsJobResultSubjects,
        dlq: NatsJobResultSubjects,
        interactiveJobsSubject: String,
        onReconnected: (() -> Unit)? = null,
    ): Connection {
        val builder = Options.Builder()
            .servers(normalizeNatsServers(natsUrl).split(",").toTypedArray())
            .connectionTimeout(CONNECT_TIMEOUT)
            .maxReconnects(INFINITE_RECONNECTS)
        onReconnected?.let { callback ->
            builder.connectionListener { _, event -> if (event == ConnectionListener.Events.RECONNECTED) callback() }
        }
        val options = builder.build()
        val connection = Nats.connect(options)
        val jsm = connection.jetStreamManagement()
        NatsStreamSetup.ensureStream(jsm, listOf(workQueue.jobsSubject, interactiveJobsSubject, workQueue.resultsSubject, NatsStreamSetup.OFFER_RESULTS_SUBJECT,
            NatsStreamSetup.PDF_CHECK_SUBJECT, NatsStreamSetup.PDF_CHECK_RESULT_SUBJECT))
        NatsStreamSetup.ensureDlqStream(jsm, listOf(dlq.jobsSubject, dlq.resultsSubject, NatsStreamSetup.PDF_CHECK_DLQ_SUBJECT))
        return connection
    }
}

/**
 * The job/result subject pair one JetStream stream is built from — the same shape used for both
 * the main WorkQueue stream and its DLQ counterpart ([NatsConnections.connect]). Names the
 * subject-pair concept the two `NatsConnectionProducer`s (kotlin-invoice, kotlin-xpdf) already
 * configure per stream, rather than passing four loose subject strings.
 */
data class NatsJobResultSubjects(
    val jobsSubject: String,
    val resultsSubject: String,
)
