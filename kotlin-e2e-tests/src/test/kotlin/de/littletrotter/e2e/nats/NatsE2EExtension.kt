package de.littletrotter.e2e.nats

import de.littletrotter.shared.NatsStreamSetup
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.JetStreamManagement
import io.nats.client.Nats
import io.nats.client.Options
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * JUnit 5 extension backing the NATS E2E suites with a real JetStream-enabled NATS
 * Testcontainer, provisioned through the exact [NatsStreamSetup] contract both real applications
 * use in production (`de.littletrotter.infrastructure.messaging.NatsConnectionProducer` in
 * kotlin-invoice, `de.littletrotter.xpdf.NatsConnectionProducer` in kotlin-xpdf — see
 * [provisionRealStreams], which reproduces both `ensureStreams()` bodies verbatim).
 *
 * Deliberately distinct from the per-module `NatsTestContainersExtension` fixtures already used by
 * `kotlin-invoice`/`kotlin-xpdf`'s own unit-level suites: those back their tests with a single
 * generic wildcard-subject `TEST_STREAM`, which is a re-implemented double of the production
 * stream contract. These suites require the real thing — the actual `LITTLE_TROTTER_JOBS`/
 * `LITTLE_TROTTER_DLQ` streams and subjects an operator would see via `nats stream info` against
 * production — so every E2E scenario in this package provisions against this extension instead.
 */
class NatsE2EExtension : BeforeAllCallback {

    companion object {
        // Mirrors NatsConnectionProducer's own `@ConfigProperty` defaults exactly (both apps agree
        // on these; see that class's own KDoc for the full cross-app contract).
        const val JOBS_SUBJECT = "document-jobs"
        const val INTERACTIVE_JOBS_SUBJECT = "document-jobs-interactive"
        const val RESULTS_SUBJECT = "document-job-results"
        const val JOBS_DLQ_SUBJECT = "document-jobs-dlq"
        const val RESULTS_DLQ_SUBJECT = "document-job-results-dlq"

        private val natsImage = DockerImageName.parse("nats:2.14.6-alpine")

        private val nats: GenericContainer<*> by lazy {
            GenericContainer(natsImage)
                .withExposedPorts(4222)
                .withCommand("-js")
                .waitingFor(Wait.forLogMessage(".*Server is ready.*\\n", 1))
                .withReuse(true)
                .apply { start() }
        }

        fun natsUrl(): String = "nats://${nats.host}:${nats.getMappedPort(4222)}"

        val connection: Connection by lazy {
            val conn = Nats.connect(Options.Builder().server(natsUrl()).build())
            provisionRealStreams(conn.jetStreamManagement())
            conn
        }

        fun jetStream(): JetStream = connection.jetStream()

        fun jetStreamManagement(): JetStreamManagement = connection.jetStreamManagement()

        /**
         * The exact two calls both real `NatsConnectionProducer.ensureStreams()` implementations
         * make — this IS the production provisioning contract, not a re-implementation of it.
         * Public (not just used from [connection]'s lazy init) so the E2E suite's own stream-provisioning
         * scenario (idempotency) can invoke it again, simulating "both apps starting up against an
         * already-provisioned broker".
         */
        fun provisionRealStreams(jsm: JetStreamManagement) {
            NatsStreamSetup.ensureStream(jsm, listOf(JOBS_SUBJECT, INTERACTIVE_JOBS_SUBJECT, RESULTS_SUBJECT))
            NatsStreamSetup.ensureDlqStream(jsm, listOf(JOBS_DLQ_SUBJECT, RESULTS_DLQ_SUBJECT))
        }
    }

    override fun beforeAll(context: ExtensionContext) {
        connection // trigger connect + real stream provisioning
    }
}
