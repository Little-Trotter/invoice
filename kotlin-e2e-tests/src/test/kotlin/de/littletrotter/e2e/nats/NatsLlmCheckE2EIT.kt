package de.littletrotter.e2e.nats

import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.FacturXExportOutcome
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.NatsStreamSetup
import de.littletrotter.shared.PdfExportPort
import de.littletrotter.xpdf.infrastructure.messaging.ResultPublisher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** When a print job requests `llmOptimize = true`, does the boolean survive the envelope
 *  round trip to [PdfExportPort.performExport] and does the `LLM_COMPLETED` callback correctly
 *  merge into [GenerationJob.llmStatus]? Tests the full NATS messaging chain with canned results. */
@ExtendWith(NatsE2EExtension::class)
class NatsLlmCheckE2EIT {

    private val workerDurable = "e2e-llmcheck-worker"
    private val appDurable = "e2e-llmcheck-app"

    @AfterEach
    fun cleanup() {
        NatsE2ESupport.deleteConsumer(NatsStreamSetup.STREAM_NAME, workerDurable)
        NatsE2ESupport.deleteConsumer(NatsStreamSetup.STREAM_NAME, appDurable)
        NatsE2ESupport.purgeSubject(NatsE2EExtension.JOBS_SUBJECT)
        NatsE2ESupport.purgeSubject(NatsE2EExtension.RESULTS_SUBJECT)
    }

    @Test
    fun `a print job requesting the KI-Pruefung carries llmOptimize across the wire and the deferred oMLX result merges into the job's llmStatus`() {
        val jobId = 900_101L
        val repo = RecordingGenerationJobRepository(
            GenerationJob().apply { id = jobId; status = GenerationJobStatus.DELEGATED },
        )
        val resultConsumer = NatsE2ESupport.newAppResultConsumer(repo)
        val appHandle = NatsE2ESupport.subscribeAppAdapter(resultConsumer, appDurable)

        // The second, deferred callback LlmAnalysisStage publishes in production, off its own
        // single-thread executor — same real ResultPublisher/JetStream, same subject, no
        // reimplementation of the wire format.
        val deferredResultPublisher = ResultPublisher(NatsE2EExtension.jetStream())
        val observedLlmOptimize = AtomicBoolean(false)
        // finding-fixes (2026-08-31) — the main thread counts this down only AFTER it has already
        // asserted llmStatus == "PENDING" (see below). Without this gate, the background thread
        // below raced the main thread on the exact same "job.status == COMPLETED" condition: both
        // are only milliseconds apart with a fake, near-instant "oMLX call", so which one the main
        // thread's OWN subsequent (separate) llmStatus read observed was pure timing luck — under
        // load the deferred second callback's full round trip (publish -> consumer -> mergeLlmResult
        // -> commit) could and did complete before the main thread got back around to its own read,
        // observing "COMPLETED" where the test wanted to pin the still-"PENDING" intermediate state.
        // This is independent of GenerationJobService.markCompleted's own ordering guard (that guard
        // protects against the FIRST callback's message being reprocessed/redelivered AFTER the
        // second one — a different hazard); here the two writes always land in the intended order,
        // the test just had no way to reliably OBSERVE the intermediate one before it moved on.
        val pendingStateObserved = CountDownLatch(1)
        val pdfExportPort = object : PdfExportPort {
            override fun performExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): ExportResult {
                observedLlmOptimize.set(llmOptimize)
                if (llmOptimize) {
                    // Mirrors PdfExportServiceImpl.completeExport's two-message LLM shape: the print job's
                    // own result reports llmStatus PENDING immediately, and a SECOND, later
                    // message (here: fired from a plain background thread, standing in for
                    // LlmAnalysisStage's own executor) carries the oMLX outcome — never a real
                    // HTTP call to any model. Waits for the FIRST callback to actually land on the
                    // app side, AND for the main thread's own "still PENDING" assertion to have run
                    // (pendingStateObserved), before firing the second — see that latch's own KDoc.
                    Thread {
                        NatsE2ESupport.waitUntil(10) { repo.findById(jobId.toLong())?.status == GenerationJobStatus.COMPLETED }
                        pendingStateObserved.await(10, TimeUnit.SECONDS)
                        deferredResultPublisher.publishResult(
                            jobId, "LLM_COMPLETED",
                            mapOf("llmStatus" to "COMPLETED", "llmModel" to "stub-model", "llmDurationMs" to "42"),
                            "", null,
                        )
                    }.start()
                }
                return ExportResult(
                    objectKey = "invoices/$jobId.pdf",
                    zugferdXmlKey = "invoices/$jobId.xml",
                    kositSuccess = true,
                    kositReport = "kosit-ok",
                    sha256 = "sha-$jobId",
                    llmStatus = if (llmOptimize) "PENDING" else "NOT_REQUESTED",
                )
            }

            override fun performFacturXExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): FacturXExportOutcome =
                throw UnsupportedOperationException("Mustang-only test double; this scenario never requests FACTURX")
        }
        val workerConsumer = NatsE2ESupport.newWorkerConsumer(pdfExportPort)
        val workerHandle = NatsE2ESupport.subscribeWorker(workerConsumer, workerDurable)

        try {
            val publisher = NatsE2ESupport.newPublisher()
            val invoice = NatsE2ESupport.newInvoice(jobId, "INV-2026-9101")
            // llmOptimize travels ON THE JOB, exactly like
            // InvoiceExportPanel/GenerationJobService.createJob persist it, not as a publish()
            // argument (see DocumentJobPublisher.publish's own KDoc on why that overload was
            // removed): this is the one field this whole scenario exists to pin end to end.
            val job = GenerationJob().apply {
                id = jobId
                jobType = "PRINT_JOB"
                engine = DocumentEngine.MUSTANG
                this.invoice = invoice
                llmOptimize = true
            }
            publisher.publish(job).get(10, TimeUnit.SECONDS)

            val firstCallbackDone = NatsE2ESupport.waitUntil(30) {
                repo.saveCount.get() > 0 && repo.findById(jobId)?.status == GenerationJobStatus.COMPLETED
            }
            assertTrue(firstCallbackDone, "the print job itself must complete via the first callback")
            assertTrue(
                observedLlmOptimize.get(),
                "job.llmOptimize=true must survive publish -> NATS envelope -> JobConsumer.resolveOptions " +
                    "and reach PdfExportPort.performExport unchanged",
            )
            assertEquals(
                "PENDING", repo.findById(jobId)?.llmStatus,
                "the first callback's resultRefs.llmStatus must be persisted onto the job's own llmStatus column",
            )
            // Only now may the deferred second callback fire — see pendingStateObserved's own KDoc.
            pendingStateObserved.countDown()

            val secondCallbackDone = NatsE2ESupport.waitUntil(10) {
                repo.findById(jobId)?.llmStatus == "COMPLETED"
            }
            assertTrue(
                secondCallbackDone,
                "the deferred oMLX LLM_COMPLETED result must merge into the job's own llmStatus " +
                    "via GenerationJobResultConsumer.mergeLlmResult",
            )
        } finally {
            NatsE2ESupport.closeConsumer(workerHandle)
            NatsE2ESupport.closeConsumer(appHandle)
        }
    }

    @Test
    fun `a print job that never requests the KI-Pruefung never carries llmOptimize across the wire`() {
        val jobId = 900_102L
        val repo = RecordingGenerationJobRepository(
            GenerationJob().apply { id = jobId; status = GenerationJobStatus.DELEGATED },
        )
        val resultConsumer = NatsE2ESupport.newAppResultConsumer(repo)
        val appHandle = NatsE2ESupport.subscribeAppAdapter(resultConsumer, appDurable)

        val observedLlmOptimize = AtomicBoolean(true)
        val pdfExportPort = object : PdfExportPort {
            override fun performExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): ExportResult {
                observedLlmOptimize.set(llmOptimize)
                return ExportResult(
                    objectKey = "invoices/$jobId.pdf", zugferdXmlKey = "invoices/$jobId.xml",
                    kositSuccess = true, kositReport = "kosit-ok", sha256 = "sha-$jobId",
                    llmStatus = "NOT_REQUESTED",
                )
            }

            override fun performFacturXExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): FacturXExportOutcome =
                throw UnsupportedOperationException("Mustang-only test double; this scenario never requests FACTURX")
        }
        val workerConsumer = NatsE2ESupport.newWorkerConsumer(pdfExportPort)
        val workerHandle = NatsE2ESupport.subscribeWorker(workerConsumer, workerDurable)

        try {
            val publisher = NatsE2ESupport.newPublisher()
            val invoice = NatsE2ESupport.newInvoice(jobId, "INV-2026-9102")
            val job = GenerationJob().apply {
                id = jobId
                jobType = "PRINT_JOB"
                engine = DocumentEngine.MUSTANG
                this.invoice = invoice
                llmOptimize = false
            }
            publisher.publish(job).get(10, TimeUnit.SECONDS)

            val completed = NatsE2ESupport.waitUntil(30) {
                repo.saveCount.get() > 0 && repo.findById(jobId)?.status == GenerationJobStatus.COMPLETED
            }
            assertTrue(completed, "the print job itself must complete via the first callback")
            assertTrue(!observedLlmOptimize.get(), "an unrequested LLM check must never reach performExport as true")
            assertEquals("NOT_REQUESTED", repo.findById(jobId)?.llmStatus)
        } finally {
            NatsE2ESupport.closeConsumer(workerHandle)
            NatsE2ESupport.closeConsumer(appHandle)
        }
    }
}
