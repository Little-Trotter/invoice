package de.littletrotter.e2e.nats

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
import java.util.concurrent.TimeUnit

/**
 * Round trip: publish a document job -> worker consumer processes -> result on
 * `document-job-results` -> app consumer marks COMPLETED; artifact keys present in the envelope.
 *
 * Every messaging component is real: [de.littletrotter.application.invoice.DocumentJobPublisher]
 * (kotlin-invoice), [de.littletrotter.xpdf.JobConsumer]/[de.littletrotter.xpdf.ResultPublisher]
 * (kotlin-xpdf), [de.littletrotter.infrastructure.messaging.GenerationJobNatsAdapter]/
 * [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer]/
 * [de.littletrotter.application.invoice.GenerationJobService] (kotlin-invoice), all against a real
 * JetStream broker provisioned via the real [NatsStreamSetup]. The only substitutions (see
 * [NatsE2ESupport]'s own KDoc) are the [PdfExportPort]'s inner render step and the
 * [RecordingGenerationJobRepository] standing in for a database — booting the
 * full Quarkus `kotlin-invoice` app inside this module was found to fail with a `LinkageError` on
 * kotlin-shared enums.
 */
@ExtendWith(NatsE2EExtension::class)
class NatsRoundTripE2EIT {

    private val mapper = jacksonObjectMapper()
    private val workerDurable = "e2e-roundtrip-worker"
    private val appDurable = "e2e-roundtrip-app"

    @AfterEach
    fun cleanup() {
        NatsE2ESupport.deleteConsumer(NatsStreamSetup.STREAM_NAME, workerDurable)
        NatsE2ESupport.deleteConsumer(NatsStreamSetup.STREAM_NAME, appDurable)
        NatsE2ESupport.purgeSubject(NatsE2EExtension.JOBS_SUBJECT)
        NatsE2ESupport.purgeSubject(NatsE2EExtension.RESULTS_SUBJECT)
    }

    @Test
    fun `a published job is exported by the worker and marks the app-side job COMPLETED with artifact keys in the envelope`() {
        val jobId = 900_001L
        val repo = RecordingGenerationJobRepository(
            GenerationJob().apply { id = jobId; status = GenerationJobStatus.DELEGATED },
        )
        val resultConsumer = NatsE2ESupport.newAppResultConsumer(repo)
        val appHandle = NatsE2ESupport.subscribeAppAdapter(resultConsumer, appDurable)

        val exportAttempts = java.util.concurrent.atomic.AtomicInteger(0)
        val pdfExportPort = object : PdfExportPort {
            override fun performExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): ExportResult {
                exportAttempts.incrementAndGet()
                return ExportResult("invoices/$jobId.pdf", "invoices/$jobId.xml", true, "kosit-ok", "sha-$jobId")
            }

            override fun performFacturXExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): FacturXExportOutcome =
                throw UnsupportedOperationException("Mustang-only test double; the round-trip scenario never requests FACTURX")
        }
        val workerConsumer = NatsE2ESupport.newWorkerConsumer(pdfExportPort)
        val workerHandle = NatsE2ESupport.subscribeWorker(workerConsumer, workerDurable)

        try {
            val publisher = NatsE2ESupport.newPublisher()
            val invoice = NatsE2ESupport.newInvoice(jobId, "INV-2026-9001")
            publisher.publish(invoice, jobId).get(10, TimeUnit.SECONDS)

            // Same race guard as NatsPoisonDlqE2EIT: markCompleted writes status before
            // resultRefs on the shared in-memory instance, so gate on the save() that follows
            // every field write before reading resultRefs below.
            val completed = NatsE2ESupport.waitUntil(30) {
                repo.saveCount.get() > 0 && repo.findById(jobId)?.status == GenerationJobStatus.COMPLETED
            }
            assertTrue(completed, "the job must reach COMPLETED once the full round trip finishes")
            assertEquals(1, exportAttempts.get(), "a clean round trip must export exactly once")

            val saved = repo.findById(jobId)
            assertNotNull(saved?.resultRefs, "the app-side job must carry the worker's resultRefs")
            val resultRefs: Map<String, Any?> = mapper.readValue(saved!!.resultRefs!!)
            assertEquals("invoices/$jobId.pdf", resultRefs["objectKey"], "the PDF object key must survive the NATS envelope unchanged")
            assertEquals("invoices/$jobId.xml", resultRefs["zugferdObjectKey"], "the XML object key must survive the NATS envelope unchanged")
            assertNotNull(resultRefs["downloadUrl"], "a downloadUrl artifact reference must be present in the envelope")
        } finally {
            NatsE2ESupport.closeConsumer(workerHandle)
            NatsE2ESupport.closeConsumer(appHandle)
        }
    }
}
