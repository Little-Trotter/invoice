package de.littletrotter.e2e.nats

import de.littletrotter.shared.NatsStreamSetup
import io.nats.client.JetStreamManagement
import io.nats.client.Nats
import io.nats.client.Options
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * Scenario 6 — stream provisioning: [NatsStreamSetup] against a genuinely fresh broker is
 * idempotent from both sides (app + worker) — reproducing the exact two calls both
 * `NatsConnectionProducer.ensureStreams()` implementations make (kotlin-invoice, kotlin-xpdf), and
 * the exact `ensureConsumer` call each app's own durable-consumer-owning class makes
 * (`JobConsumer.subscribe()` / `GenerationJobNatsAdapter.subscribe()`).
 *
 * Deliberately does NOT reuse [NatsE2EExtension]'s shared, already-provisioned broker: "fresh
 * broker" here specifically means first-contact provisioning (`addStream`, not `updateStream`),
 * which the shared extension's container has already been through by the time any other test in
 * this module runs.
 */
class NatsStreamProvisioningE2EIT {

    companion object {
        private val nats: GenericContainer<*> by lazy {
            GenericContainer(DockerImageName.parse("nats:2.14.6-alpine"))
                .withExposedPorts(4222)
                .withCommand("-js")
                .waitingFor(Wait.forLogMessage(".*Server is ready.*\\n", 1))
                .apply { start() }
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() {
            if (nats.isRunning) nats.stop()
        }
    }

    @Test
    fun `ensureStream, ensureDlqStream and ensureConsumer are idempotent across repeated app-side and worker-side calls against a fresh broker`() {
        val conn = Nats.connect(Options.Builder().server("nats://${nats.host}:${nats.getMappedPort(4222)}").build())
        try {
            val jsm = conn.jetStreamManagement()
            val jobsSubject = NatsE2EExtension.JOBS_SUBJECT
            val resultsSubject = NatsE2EExtension.RESULTS_SUBJECT
            val jobsDlqSubject = NatsE2EExtension.JOBS_DLQ_SUBJECT
            val resultsDlqSubject = NatsE2EExtension.RESULTS_DLQ_SUBJECT

            // "App side" starts up first (NatsConnectionProducer.ensureStreams(), kotlin-invoice) -
            // first contact with a genuinely fresh broker: addStream/addStream, not updateStream.
            appSideEnsureStreams(jsm, jobsSubject, resultsSubject, jobsDlqSubject, resultsDlqSubject)
            // "Worker side" starts up next (NatsConnectionProducer.ensureStreams(), kotlin-xpdf) -
            // races the exact same calls against what the app side just created.
            workerSideEnsureStreams(jsm, jobsSubject, resultsSubject, jobsDlqSubject, resultsDlqSubject)

            val afterFirstRound = jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME).configuration
            val dlqAfterFirstRound = jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME).configuration

            // Both "sides" restart (a redeploy, a pod bounce) - the same two ensure* calls run
            // again, on both apps, against a now-already-provisioned broker (reconcile, not
            // create). Must not throw and must not drift the config.
            repeat(2) {
                appSideEnsureStreams(jsm, jobsSubject, resultsSubject, jobsDlqSubject, resultsDlqSubject)
                workerSideEnsureStreams(jsm, jobsSubject, resultsSubject, jobsDlqSubject, resultsDlqSubject)
            }

            val afterRepeat = jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME).configuration
            val dlqAfterRepeat = jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME).configuration
            assertEquals(afterFirstRound.subjects.toSet(), afterRepeat.subjects.toSet(), "repeated provisioning must not drift the main stream's subjects")
            assertTrue(
                NatsE2EExtension.INTERACTIVE_JOBS_SUBJECT in afterRepeat.subjects,
                "the main stream must carry the interactive jobs subject, else interactive publishes are rejected",
            )
            assertEquals(afterFirstRound.retentionPolicy, afterRepeat.retentionPolicy, "repeated provisioning must not drift the main stream's retention policy")
            assertEquals(dlqAfterFirstRound.subjects.toSet(), dlqAfterRepeat.subjects.toSet(), "repeated provisioning must not drift the DLQ stream's subjects")

            // The two durable-consumer-owning classes' own ensureConsumer calls
            // (JobConsumer.subscribe / GenerationJobNatsAdapter.subscribe), also called repeatedly.
            val workerDurable = "kotlin-xpdf-group"
            val interactiveDurable = "kotlin-xpdf-interactive"
            val appDurable = "little-trotter-invoice-result-consumer"
            repeat(3) {
                NatsStreamSetup.ensureConsumer(
                    jsm, NatsStreamSetup.STREAM_NAME,
                    NatsStreamSetup.consumerConfiguration(workerDurable, jobsSubject, maxAckPending = 8, ackWaitSeconds = 120),
                )
                NatsStreamSetup.ensureConsumer(
                    jsm, NatsStreamSetup.STREAM_NAME,
                    NatsStreamSetup.consumerConfiguration(interactiveDurable, NatsE2EExtension.INTERACTIVE_JOBS_SUBJECT, maxAckPending = 8),
                )
                NatsStreamSetup.ensureConsumer(
                    jsm, NatsStreamSetup.STREAM_NAME,
                    NatsStreamSetup.consumerConfiguration(appDurable, resultsSubject, maxAckPending = 16),
                )
            }

            val consumerNames = jsm.getConsumerNames(NatsStreamSetup.STREAM_NAME).toSet()
            assertEquals(
                setOf(workerDurable, interactiveDurable, appDurable), consumerNames,
                "repeated ensureConsumer calls must reconcile the SAME three durables, never create duplicates",
            )
            val workerInfo = jsm.getConsumerInfo(NatsStreamSetup.STREAM_NAME, workerDurable)
            assertTrue(workerInfo.consumerConfiguration.ackWait?.seconds == 120L, "the worker durable's ackWait must reflect the latest ensureConsumer call")
            assertEquals(jobsSubject, workerInfo.consumerConfiguration.filterSubject)
        } finally {
            conn.close()
        }
    }

    private fun appSideEnsureStreams(
        jsm: JetStreamManagement, jobsSubject: String, resultsSubject: String, jobsDlqSubject: String, resultsDlqSubject: String,
    ) {
        NatsStreamSetup.ensureStream(jsm, listOf(jobsSubject, NatsE2EExtension.INTERACTIVE_JOBS_SUBJECT, resultsSubject))
        NatsStreamSetup.ensureDlqStream(jsm, listOf(jobsDlqSubject, resultsDlqSubject))
    }

    // Byte-identical to appSideEnsureStreams today (both real NatsConnectionProducers run the
    // exact same two calls) - kept as its own named call site so this test documents "both sides
    // agree", not merely "one side, called twice".
    private fun workerSideEnsureStreams(
        jsm: JetStreamManagement, jobsSubject: String, resultsSubject: String, jobsDlqSubject: String, resultsDlqSubject: String,
    ) {
        NatsStreamSetup.ensureStream(jsm, listOf(jobsSubject, NatsE2EExtension.INTERACTIVE_JOBS_SUBJECT, resultsSubject))
        NatsStreamSetup.ensureDlqStream(jsm, listOf(jobsDlqSubject, resultsDlqSubject))
    }
}
