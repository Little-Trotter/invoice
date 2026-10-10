package de.littletrotter.e2e.nats

import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.FacturXExportOutcome
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.NatsStreamSetup
import de.littletrotter.shared.PdfExportPort
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.TimeUnit

/**
 * Atomic checksum persistence: a job bound to a real invoice completes through the full NATS round
 * trip, and `GenerationJobResultHandler.markCompleted` writes exactly one `invoice_document`
 * checksum row (the PDF; no XML checksum is supplied) BEFORE the COMPLETED transition. No state
 * exists in which the job is COMPLETED but the row is missing. The database transaction itself is
 * not reproducible here (in-memory repositories, see [NatsE2ESupport]); the row-before-COMPLETED
 * ordering is what the single-transaction contract reduces to.
 */
@ExtendWith(NatsE2EExtension::class)
class NatsChecksumPersistenceE2EIT {

    private val workerDurable = "e2e-checksum-worker"
    private val appDurable = "e2e-checksum-app"

    @AfterEach
    fun cleanup() {
        NatsE2ESupport.deleteConsumer(NatsStreamSetup.STREAM_NAME, workerDurable)
        NatsE2ESupport.deleteConsumer(NatsStreamSetup.STREAM_NAME, appDurable)
        NatsE2ESupport.purgeSubject(NatsE2EExtension.JOBS_SUBJECT)
        NatsE2ESupport.purgeSubject(NatsE2EExtension.RESULTS_SUBJECT)
    }

    @Test
    fun `a completed job with an invoice has exactly one checksum row written before COMPLETED`() {
        val jobId = 900_301L
        val job = NatsE2ESupport.newSendJobWithInvoice(jobId, "INV-2026-9301")
        val repo = RecordingGenerationJobRepository(job)
        val documents = RecordingInvoiceDocumentRepository(job)
        val appHandle = NatsE2ESupport.subscribeAppAdapter(NatsE2ESupport.newAppResultConsumer(repo, documents), appDurable)

        val pdfExportPort = object : PdfExportPort {
            override fun performExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): ExportResult = ExportResult("invoices/$jobId.pdf", "invoices/$jobId.xml", true, "kosit-ok", "sha-$jobId")

            override fun performFacturXExport(
                invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
            ): FacturXExportOutcome =
                throw UnsupportedOperationException("Mustang-only test double")
        }
        val workerHandle = NatsE2ESupport.subscribeWorker(NatsE2ESupport.newWorkerConsumer(pdfExportPort), workerDurable)

        try {
            NatsE2ESupport.newPublisher().publish(requireNotNull(job.invoice), jobId).get(10, TimeUnit.SECONDS)

            val completed = NatsE2ESupport.waitUntil(30) {
                repo.saveCount.get() > 0 && repo.findById(jobId)?.status == GenerationJobStatus.COMPLETED
            }
            assertTrue(completed, "the job must reach COMPLETED")

            assertEquals(1, documents.rows.size, "exactly one checksum row must exist for the completed job")
            val row = documents.rows.single()
            assertEquals("invoices/$jobId.pdf", row.objectKey)
            assertEquals("sha-$jobId", row.sha256Checksum)
            assertEquals(jobId, row.invoice?.id, "the row must belong to the job's invoice")
            assertEquals(
                listOf(GenerationJobStatus.DELEGATED), documents.jobStatusAtSave,
                "the checksum row must be written before the job transitions to COMPLETED",
            )
        } finally {
            NatsE2ESupport.closeConsumer(workerHandle)
            NatsE2ESupport.closeConsumer(appHandle)
        }
    }
}
