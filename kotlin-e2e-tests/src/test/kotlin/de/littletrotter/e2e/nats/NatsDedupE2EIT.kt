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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scenario 2 — dedup: two publishes with the same `Nats-Msg-Id` (`job-$id`, set by the real
 * `DocumentJobPublisher`) result in exactly one delivery to the worker, and JetStream's own
 * `PublishAck.isDuplicate` reports the second publish as a duplicate.
 */
@ExtendWith(NatsE2EExtension::class)
class NatsDedupE2EIT {

    private val workerDurable = "e2e-dedup-worker"
    private val appDurable = "e2e-dedup-app"

    @AfterEach
    fun cleanup() {
        NatsE2ESupport.deleteConsumer(NatsStreamSetup.STREAM_NAME, workerDurable)
        NatsE2ESupport.deleteConsumer(NatsStreamSetup.STREAM_NAME, appDurable)
        NatsE2ESupport.purgeSubject(NatsE2EExtension.JOBS_SUBJECT)
        NatsE2ESupport.purgeSubject(NatsE2EExtension.RESULTS_SUBJECT)
    }

    @Test
    fun `publishing the same job id twice is deduped to exactly one worker delivery`() {
        val jobId = 900_002L
        val repo = RecordingGenerationJobRepository(
            GenerationJob().apply { id = jobId; status = GenerationJobStatus.DELEGATED },
        )
        val resultConsumer = NatsE2ESupport.newAppResultConsumer(repo)
        val appHandle = NatsE2ESupport.subscribeAppAdapter(resultConsumer, appDurable)

        val exportAttempts = AtomicInteger(0)
        val pdfExportPort = object : PdfExportPort {
            override fun performExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): ExportResult {
                exportAttempts.incrementAndGet()
                return ExportResult("invoices/$jobId.pdf", "invoices/$jobId.xml", true, "kosit-ok", "sha-$jobId")
            }

            override fun performFacturXExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): FacturXExportOutcome = throw UnsupportedOperationException("Mustang-only test double")
        }
        val workerConsumer = NatsE2ESupport.newWorkerConsumer(pdfExportPort)
        val workerHandle = NatsE2ESupport.subscribeWorker(workerConsumer, workerDurable)

        try {
            val publisher = NatsE2ESupport.newPublisher()
            val firstAck = publisher.publish(NatsE2ESupport.newInvoice(jobId, "INV-2026-9002"), jobId).get(10, TimeUnit.SECONDS)
            val secondAck = publisher.publish(NatsE2ESupport.newInvoice(jobId, "INV-2026-9003"), jobId).get(10, TimeUnit.SECONDS)

            assertFalse(firstAck.isDuplicate, "the first publish of a fresh Nats-Msg-Id must not be reported as a duplicate")
            assertTrue(secondAck.isDuplicate, "the second publish with the same Nats-Msg-Id (\"job-$jobId\") must be reported as a duplicate")
            assertEquals(firstAck.seqno, secondAck.seqno, "both PublishAcks must reference the same stored stream sequence")

            val completed = NatsE2ESupport.waitUntil(30) { repo.findById(jobId)?.status == GenerationJobStatus.COMPLETED }
            assertTrue(completed, "the (single, deduped) job must still reach COMPLETED")

            // Give the worker a moment past completion in case a (wrongly) second delivery were
            // still in flight - proves "exactly one delivery", not just "at least one".
            Thread.sleep(1_000)
            assertEquals(1, exportAttempts.get(), "the worker must have received exactly one delivery, not two")
        } finally {
            NatsE2ESupport.closeConsumer(workerHandle)
            NatsE2ESupport.closeConsumer(appHandle)
        }
    }
}
