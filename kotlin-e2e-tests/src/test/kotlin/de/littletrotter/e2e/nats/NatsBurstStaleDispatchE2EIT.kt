package de.littletrotter.e2e.nats

import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.FacturXExportOutcome
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.NatsStreamSetup
import de.littletrotter.shared.PdfExportPort
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reproduces the 2026-09-17 live incident: a burst of jobs exceeding the worker pool's
 * immediate capacity queues later jobs behind earlier ones for longer than a single ack window,
 * while kotlin-invoice's own independent staleness scan
 * ([de.littletrotter.application.invoice.GenerationJobProcessor.resetStaleDelegatedJobs]) resets a
 * still-genuinely-in-flight job back to PENDING purely from a DB timestamp.
 *
 * Every messaging component is real (same convention as [NatsRoundTripE2EIT]/
 * [NatsRedeliveryE2EIT]): the actual [de.littletrotter.xpdf.infrastructure.messaging.JobConsumer]
 * with its real bounded executor and real heartbeat scheduling, the actual
 * [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer]/[de.littletrotter
 * .application.invoice.GenerationJobService], against a real JetStream broker. `workerThreads=1`
 * and a short `ackWaitSeconds` compress the incident's timing into test-friendly numbers: with a
 * single worker thread and each export taking [PER_JOB_DELAY_MS], the later jobs in a
 * [JOB_COUNT]-job burst are still queued or still running well past one `ackWaitSeconds` window
 * measured from the burst's own publish time — exactly the shape that used to leave a queued
 * message's heartbeat unscheduled for longer than its own lease.
 *
 * [de.littletrotter.e2e.nats.RecordingGenerationJobRepository] stands in for Postgres — the same,
 * documented substitution [NatsRoundTripE2EIT]'s own KDoc explains (booting the full
 * Quarkus `kotlin-invoice` app inside this module fails with a `LinkageError`). This test drives
 * that repository directly to reproduce [de.littletrotter.application.invoice
 * .GenerationJobProcessor.resetStaleDelegatedJobs]'s own bulk-`UPDATE` semantics (flip any job
 * still `DELEGATED` back to `PENDING`, unconditionally, exactly like the real `WHERE status =
 * DELEGATED` guard the production repository uses) at a point in the burst where several jobs are
 * still genuinely in flight — this is the identical race the kotlin-invoice-side unit/IT
 * regression tests ([de.littletrotter.infrastructure.messaging.GenerationJobResultConsumerTest],
 * `GenerationJobServiceTest`) cover against the real `GenerationJobService`, but here it happens
 * concurrently with a real, still-running worker, over a real NATS broker.
 */
@ExtendWith(NatsE2EExtension::class)
class NatsBurstStaleDispatchE2EIT {

    private val workerDurable = "e2e-burst-worker"
    private val appDurable = "e2e-burst-app"

    companion object {
        private const val JOB_COUNT = 4
        private const val PER_JOB_DELAY_MS = 1_500L
        private const val ACK_WAIT_SECONDS = 3L
    }

    @AfterEach
    fun cleanup() {
        System.clearProperty("job.worker.threads")
        System.clearProperty("nats.consumer.ack-wait-seconds")
        NatsE2ESupport.deleteConsumer(NatsStreamSetup.STREAM_NAME, workerDurable)
        NatsE2ESupport.deleteConsumer(NatsStreamSetup.STREAM_NAME, appDurable)
        NatsE2ESupport.purgeSubject(NatsE2EExtension.JOBS_SUBJECT)
        NatsE2ESupport.purgeSubject(NatsE2EExtension.RESULTS_SUBJECT)
    }

    @Test
    fun `a burst exceeding the worker pool's capacity still completes every job, even racing an app-side stale-DELEGATED reset mid-flight`() {
        // JobConsumer reads both of these once, at construction — must be set before
        // NatsE2ESupport.newWorkerConsumer below.
        System.setProperty("job.worker.threads", "1")
        System.setProperty("nats.consumer.ack-wait-seconds", ACK_WAIT_SECONDS.toString())

        // fix-t (2026-09-18) — every e2e/nats suite must use a job-id range disjoint from every
        // other suite's, because JetStream's publish-side Nats-Msg-Id dedup ("job-$jobId",
        // DocumentJobPublisher) is stream-level and survives a purgeSubject for the stream's full
        // 2-minute duplicate window (NatsStreamSetup.MAIN_DUPLICATE_WINDOW) — independent of which
        // consumer/durable is subscribed. Two suites racing the same literal id within that window
        // means whichever publishes second gets silently ack'd as a duplicate of the first's
        // already-consumed message: no exception, no redelivery, just permanent silence on the
        // receiving end (this is exactly what broke NatsLlmCheckE2EIT when this suite's own ids
        // used to be 900_100L..900_103L, colliding with NatsLlmCheckE2EIT's 900_101L/900_102L).
        // See the range registry in NatsE2ESupport's own class KDoc before picking a new one.
        val jobIds = (0 until JOB_COUNT).map { 900_200L + it }
        val repo = RecordingGenerationJobRepository(
            *jobIds.map { GenerationJob().apply { id = it; status = GenerationJobStatus.DELEGATED } }.toTypedArray(),
        )
        val resultConsumer = NatsE2ESupport.newAppResultConsumer(repo)
        val appHandle = NatsE2ESupport.subscribeAppAdapter(resultConsumer, appDurable)

        val exportAttempts = ConcurrentHashMap<Long, AtomicInteger>()
        val pdfExportPort = object : PdfExportPort {
            override fun performExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): ExportResult {
                exportAttempts.computeIfAbsent(jobId.toLong()) { AtomicInteger(0) }.incrementAndGet()
                // Simulates a genuinely slow (not hung) render — long enough that, with a single
                // worker thread, later jobs in the burst sit queued past ACK_WAIT_SECONDS before
                // the heartbeat fix, their heartbeat would not even have started yet.
                Thread.sleep(PER_JOB_DELAY_MS)
                return ExportResult("invoices/$jobId.pdf", "invoices/$jobId.xml", true, "kosit-ok", "sha-$jobId")
            }

            override fun performFacturXExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): FacturXExportOutcome = throw UnsupportedOperationException("Mustang-only test double; this scenario never requests FACTURX")
        }

        val workerConsumer = NatsE2ESupport.newWorkerConsumer(pdfExportPort)
        val workerHandle = NatsE2ESupport.subscribeWorker(
            workerConsumer, workerDurable,
            ackWaitSeconds = ACK_WAIT_SECONDS,
            maxAckPending = JOB_COUNT,
            batchSize = JOB_COUNT,
        )

        try {
            val publisher = NatsE2ESupport.newPublisher()
            // The burst: every job published up front, before any of them can possibly be
            // processed — the demo-seed "print every invoice at once" shape.
            // InvoiceNumber's legacy pattern is strictly PREFIX-YYYY-NNNN (4-digit counter) - a
            // 4-digit index derived from the loop position, not the (6-digit) job id itself.
            jobIds.forEachIndexed { index, id ->
                val invoiceNumber = "INV-2026-%04d".format(9100 + index)
                publisher.publish(NatsE2ESupport.newInvoice(id, invoiceNumber), id).get(10, TimeUnit.SECONDS)
            }

            // Reproduces GenerationJobProcessor.resetStaleDelegatedJobs racing the still-in-flight
            // burst: fired once, after one ack window has elapsed, exactly like the production
            // scan's own DB-timestamp-only check — with zero visibility into whether this worker's
            // own lease is still legitimately alive. At this point (with 1 worker thread and
            // PER_JOB_DELAY_MS per job) at least the last one or two jobs are still queued or still
            // running, so this reset races a genuinely in-flight attempt for them, exactly like the
            // live incident.
            Thread.sleep(ACK_WAIT_SECONDS * 1_000 + 500)
            var resetCount = 0
            jobIds.forEach { id ->
                repo.findById(id)?.let { job ->
                    if (job.status == GenerationJobStatus.DELEGATED) {
                        job.status = GenerationJobStatus.PENDING
                        resetCount++
                    }
                }
            }
            assertTrue(resetCount > 0, "the test setup must actually race at least one still-in-flight job — got 0 resets")

            val allCompleted = NatsE2ESupport.waitUntil(30) {
                jobIds.all { repo.findById(it)?.status == GenerationJobStatus.COMPLETED }
            }
            assertTrue(
                allCompleted,
                "every job must reach COMPLETED even though an app-side reset raced $resetCount still-in-flight worker attempt(s): " +
                    jobIds.associateWith { repo.findById(it)?.status },
            )

            jobIds.forEach { id ->
                val refs = repo.findById(id)?.resultRefs
                assertNotNull(refs, "job $id must carry its real resultRefs, not be silently discarded to the DLQ")
                assertTrue(refs!!.contains("invoices/$id.pdf"), "job $id's real objectKey must survive the full round trip")
                assertEquals(
                    1, exportAttempts[id]?.get(),
                    "job $id must be exported exactly once — ARC91-06's heartbeat fix means no queued job's " +
                        "JetStream lease ever lapses, so there must be no redelivery/duplicate export either",
                )
            }
        } finally {
            NatsE2ESupport.closeConsumer(workerHandle)
            NatsE2ESupport.closeConsumer(appHandle)
        }
    }
}
