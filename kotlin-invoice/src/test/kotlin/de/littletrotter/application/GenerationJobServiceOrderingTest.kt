package de.littletrotter.application

import de.littletrotter.application.invoice.GenerationJobEventDispatcher
import de.littletrotter.application.invoice.GenerationJobLlmResultHandler
import de.littletrotter.application.invoice.GenerationJobResultHandler
import de.littletrotter.application.invoice.NoOpInvoiceRepository
import de.littletrotter.application.invoice.NoOpLlmAnalysisRepository
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.repository.GenerationJobRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * finding-fixes (2026-08-31) — [GenerationJobResultHandler.markCompleted]'s ordering guard: the FIRST
 * callback's own `resultRefs.llmStatus` ("PENDING" while an LLM check is still in flight)
 * must never clobber a terminal `llmStatus` a deferred SECOND callback
 * ([GenerationJobLlmResultHandler.mergeLlmResult]) already wrote — regardless of which one this
 * consumer happened to process first. See [de.littletrotter.e2e.nats.NatsLlmCheckE2EIT]'s own
 * KDoc for the production reordering scenario this closes (a real, if rare, NATS delivery-order
 * hazard).
 *
 * `GenerationJobService` no longer exists: [resultHandler]/[llmResultHandler] are the real collaborators
 * it used to build internally, sharing the same repo/event dispatcher.
 */
class GenerationJobServiceOrderingTest {

    private class InMemoryGenerationJobRepository : GenerationJobRepository {
        private val jobs = mutableListOf<GenerationJob>()
        private var nextId = 1L

        override fun findByInvoiceId(invoiceId: Long): List<GenerationJob> = jobs.filter { it.invoice?.id == invoiceId }
        override fun findByInvoiceIdWithTaxRates(invoiceId: Long): List<GenerationJob> = findByInvoiceId(invoiceId)
        override fun findPending(): List<GenerationJob> = jobs.filter { it.status == GenerationJobStatus.PENDING }
        override fun findPendingWithInvoice(): List<GenerationJob> = findPending()
        override fun findById(id: Long): GenerationJob? = jobs.find { it.id == id }
        override fun tryMarkDelegated(id: Long): Boolean = true
        override fun findCompletedBetween(from: java.time.OffsetDateTime, to: java.time.OffsetDateTime): List<GenerationJob> = emptyList()
        override fun save(job: GenerationJob): GenerationJob {
            if (job.id == null) {
                job.id = nextId++
            }
            if (jobs.none { it.id == job.id }) {
                jobs.add(job)
            }
            return job
        }
    }

    private fun newJob(id: Long): GenerationJob = GenerationJob().apply {
        this.id = id
        jobType = "PDF_EXPORT"
        status = GenerationJobStatus.DELEGATED
    }

    // ─── reordered delivery: the deferred second callback lands FIRST ──────────────────────────

    @Test
    fun `LLM_COMPLETED merged first, then the first callback's own COMPLETED - terminal llmStatus survives`() {
        val jobRepo = InMemoryGenerationJobRepository()
        val eventDispatcher = GenerationJobEventDispatcher(mock(), null)
        val resultHandler = GenerationJobResultHandler(jobRepo, eventDispatcher, time = FIXED_TIME_SOURCE)
        val llmResultHandler = GenerationJobLlmResultHandler(jobRepo, NoOpLlmAnalysisRepository, NoOpInvoiceRepository, eventDispatcher, time = FIXED_TIME_SOURCE)
        val job = jobRepo.save(newJob(1L))

        // The deferred second callback (GenerationJobResultConsumer's LLM_COMPLETED branch)
        // processed first, ahead of the print job's own first callback.
        llmResultHandler.mergeLlmResult(job, mapOf("llmStatus" to "COMPLETED", "llmModel" to "stub-model"))
        assertEquals("COMPLETED", jobRepo.findById(1L)!!.llmStatus)

        // The first callback (markCompleted) is processed second/late, still reporting its own
        // "PENDING" — this must not clobber the already-terminal COMPLETED status.
        resultHandler.markCompleted(job, resultRefs = "{}", llmStatus = "PENDING")

        assertEquals(
            "COMPLETED", jobRepo.findById(1L)!!.llmStatus,
            "a terminal llmStatus must never be downgraded back to PENDING by a later-processed first callback",
        )
    }

    @Test
    fun `LLM_FAILED merged first, then the first callback's own COMPLETED - terminal FAILED survives`() {
        val jobRepo = InMemoryGenerationJobRepository()
        val eventDispatcher = GenerationJobEventDispatcher(mock(), null)
        val resultHandler = GenerationJobResultHandler(jobRepo, eventDispatcher, time = FIXED_TIME_SOURCE)
        val llmResultHandler = GenerationJobLlmResultHandler(jobRepo, NoOpLlmAnalysisRepository, NoOpInvoiceRepository, eventDispatcher, time = FIXED_TIME_SOURCE)
        val job = jobRepo.save(newJob(2L))

        llmResultHandler.mergeLlmResult(job, mapOf("llmStatus" to "FAILED", "llmError" to "model timeout"))
        assertEquals("FAILED", jobRepo.findById(2L)!!.llmStatus)

        resultHandler.markCompleted(job, resultRefs = "{}", llmStatus = "PENDING")

        assertEquals("FAILED", jobRepo.findById(2L)!!.llmStatus)
    }

    // ─── normal order: the first callback lands first, exactly as designed ─────────────────────

    @Test
    fun `normal order - first callback PENDING then deferred COMPLETED - unchanged behavior`() {
        val jobRepo = InMemoryGenerationJobRepository()
        val eventDispatcher = GenerationJobEventDispatcher(mock(), null)
        val resultHandler = GenerationJobResultHandler(jobRepo, eventDispatcher, time = FIXED_TIME_SOURCE)
        val llmResultHandler = GenerationJobLlmResultHandler(jobRepo, NoOpLlmAnalysisRepository, NoOpInvoiceRepository, eventDispatcher, time = FIXED_TIME_SOURCE)
        val job = jobRepo.save(newJob(3L))

        resultHandler.markCompleted(job, resultRefs = "{}", llmStatus = "PENDING")
        assertEquals("PENDING", jobRepo.findById(3L)!!.llmStatus)

        llmResultHandler.mergeLlmResult(job, mapOf("llmStatus" to "COMPLETED", "llmModel" to "stub-model"))

        assertEquals("COMPLETED", jobRepo.findById(3L)!!.llmStatus)
    }

    @Test
    fun `no llmOptimize requested - markCompleted with a null llmStatus leaves the column untouched`() {
        val jobRepo = InMemoryGenerationJobRepository()
        val eventDispatcher = GenerationJobEventDispatcher(mock(), null)
        val resultHandler = GenerationJobResultHandler(jobRepo, eventDispatcher, time = FIXED_TIME_SOURCE)
        val llmResultHandler = GenerationJobLlmResultHandler(jobRepo, NoOpLlmAnalysisRepository, NoOpInvoiceRepository, eventDispatcher, time = FIXED_TIME_SOURCE)
        val job = jobRepo.save(newJob(4L))

        resultHandler.markCompleted(job, resultRefs = "{}", llmStatus = null)

        assertEquals(null, jobRepo.findById(4L)!!.llmStatus)
    }

    @Test
    fun `first callback on a fresh job with no prior llmStatus still writes it normally`() {
        val jobRepo = InMemoryGenerationJobRepository()
        val eventDispatcher = GenerationJobEventDispatcher(mock(), null)
        val resultHandler = GenerationJobResultHandler(jobRepo, eventDispatcher, time = FIXED_TIME_SOURCE)
        val llmResultHandler = GenerationJobLlmResultHandler(jobRepo, NoOpLlmAnalysisRepository, NoOpInvoiceRepository, eventDispatcher, time = FIXED_TIME_SOURCE)
        val job = jobRepo.save(newJob(5L))

        resultHandler.markCompleted(job, resultRefs = "{}", llmStatus = "PENDING")

        assertEquals("PENDING", jobRepo.findById(5L)!!.llmStatus)
    }
}
