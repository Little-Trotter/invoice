package de.littletrotter.application

import de.littletrotter.application.invoice.GenerationJobEventDispatcher
import de.littletrotter.application.invoice.GenerationJobLlmResultHandler
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.GenerationJobRecoveryService
import de.littletrotter.application.invoice.GenerationJobResultHandler
import de.littletrotter.application.invoice.NoOpInvoiceRepository
import de.littletrotter.application.invoice.NoOpLlmAnalysisRepository
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer
import de.littletrotter.shared.PayloadExchange
import jakarta.enterprise.event.Event
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

class GenerationJobResultConsumerTest {

    private class InMemoryGenerationJobRepository : de.littletrotter.domain.repository.GenerationJobRepository {
        private val jobs = mutableMapOf<Long, de.littletrotter.domain.invoice.GenerationJob>()
        private var nextId = 1L

        override fun findByInvoiceId(invoiceId: Long): List<de.littletrotter.domain.invoice.GenerationJob> = jobs.values.filter { it.invoice?.id == invoiceId }
        override fun findByInvoiceIdWithTaxRates(invoiceId: Long): List<de.littletrotter.domain.invoice.GenerationJob> = findByInvoiceId(invoiceId)
        override fun findPending(): List<de.littletrotter.domain.invoice.GenerationJob> = jobs.values.filter { it.status == de.littletrotter.domain.invoice.GenerationJobStatus.PENDING }
        override fun findPendingWithInvoice(): List<de.littletrotter.domain.invoice.GenerationJob> = findPending()
        override fun findById(id: Long): de.littletrotter.domain.invoice.GenerationJob? = jobs[id]
        override fun tryMarkDelegated(id: Long): Boolean = true
        override fun save(job: de.littletrotter.domain.invoice.GenerationJob): de.littletrotter.domain.invoice.GenerationJob {
            if (job.id == null) {
                job.id = nextId++
            }
            jobs[job.id!!] = job
            return job
        }
    }

    /** `GenerationJobService` no longer exists: wires the real collaborators it used to build
     *  internally (all sharing [repository]/[invoiceRepository]/[domainEventBus]), mirroring its
     *  own former constructor exactly. */
    private class GenerationJobCollaborators(
        repository: GenerationJobRepository,
        invoiceRepository: InvoiceRepository = NoOpInvoiceRepository,
        domainEventBus: Event<DomainEvent> = mock(),
    ) {
        val queryService = GenerationJobQueryService(repository)
        private val eventDispatcher = GenerationJobEventDispatcher(domainEventBus, null)
        val resultHandler = GenerationJobResultHandler(repository, eventDispatcher, time = FIXED_TIME_SOURCE)
        val llmResultHandler = GenerationJobLlmResultHandler(repository, NoOpLlmAnalysisRepository, invoiceRepository, eventDispatcher, time = FIXED_TIME_SOURCE)
        val recoveryService = GenerationJobRecoveryService(repository, invoiceRepository, eventDispatcher, llmResultHandler, time = FIXED_TIME_SOURCE)
    }

    private fun makeConsumer(ops: GenerationJobCollaborators = GenerationJobCollaborators(InMemoryGenerationJobRepository())) =
        GenerationJobResultConsumer(ops.queryService, ops.resultHandler, ops.llmResultHandler, ops.recoveryService)

    @Test
    fun `malformed json returns gracefully`() {
        val consumer = makeConsumer()
        assertDoesNotThrow { consumer.consume("{ bad json }") }
    }

    @Test
    fun `missing id returns gracefully`() {
        val consumer = makeConsumer()
        assertDoesNotThrow { consumer.consume("{\"status\":\"COMPLETED\"}") }
    }

    @Test
    fun `processing unknown job id returns gracefully after retries`() {
        val consumer = makeConsumer()
        // will retry 3 times internally
        assertDoesNotThrow { consumer.consume("{\"id\":\"999\",\"status\":\"COMPLETED\"}") }
    }

    @Test
    fun `completed updates generation job state`() {
        val repository = InMemoryGenerationJobRepository()
        val ops = GenerationJobCollaborators(repository)
        val invoice = de.littletrotter.domain.invoice.Invoice().apply { id = 1L }
        val job = de.littletrotter.domain.invoice.GenerationJob().apply {
            this.invoice = invoice
            this.jobType = "PDF_EXPORT"
            // A real result can only ever arrive for a DELEGATED/RUNNING job; PENDING
            // (never published yet) would make the COMPLETED transition below illegal.
            this.status = de.littletrotter.domain.invoice.GenerationJobStatus.DELEGATED
            this.createdAt = java.time.OffsetDateTime.now()
        }
        repository.save(job)
        val jobId = job.id!!
        val consumer = makeConsumer(ops)

        consumer.consume("{\"id\":\"$jobId\",\"status\":\"COMPLETED\",\"resultRefs\":{\"pdf\":\"objkey\"}}")

        val updated = ops.queryService.findById(jobId)
        assertNotNull(updated)
        assertEquals(de.littletrotter.domain.invoice.GenerationJobStatus.COMPLETED, updated?.status)
        assertTrue(updated?.resultRefs?.contains("objkey") == true)
    }

    @Test
    fun `failed updates generation job state`() {
        val repository = InMemoryGenerationJobRepository()
        val ops = GenerationJobCollaborators(repository)
        val invoice = de.littletrotter.domain.invoice.Invoice().apply { id = 1L }
        val job = de.littletrotter.domain.invoice.GenerationJob().apply {
            this.invoice = invoice
            this.jobType = "PDF_EXPORT"
            this.status = de.littletrotter.domain.invoice.GenerationJobStatus.PENDING
            this.createdAt = java.time.OffsetDateTime.now()
        }
        repository.save(job)
        val jobId = job.id!!
        // The report no longer travels inline; the consumer resolves it from Valkey via
        // the kositReportRef, so a mock PayloadExchange stands in for the real store here.
        val payloadExchange = mock<PayloadExchange>()
        whenever(payloadExchange.get("lt:kosit:$jobId")).thenReturn("errmsg")
        val consumer = GenerationJobResultConsumer(ops.queryService, ops.resultHandler, ops.llmResultHandler, ops.recoveryService, payloadExchange)

        consumer.consume("{\"id\":\"$jobId\",\"status\":\"FAILED\",\"kositReportRef\":{\"key\":\"lt:kosit:$jobId\"}}")

        val updated = ops.queryService.findById(jobId)
        assertNotNull(updated)
        assertEquals(de.littletrotter.domain.invoice.GenerationJobStatus.FAILED, updated?.status)
        assertEquals("errmsg", updated?.kositReport)
    }

    @Test
    fun `AP-M6 - a missing kositReportRef key degrades to unavailable (null), job still marked FAILED`() {
        val repository = InMemoryGenerationJobRepository()
        val ops = GenerationJobCollaborators(repository)
        val invoice = de.littletrotter.domain.invoice.Invoice().apply { id = 1L }
        val job = de.littletrotter.domain.invoice.GenerationJob().apply {
            this.invoice = invoice
            this.jobType = "PDF_EXPORT"
            this.status = de.littletrotter.domain.invoice.GenerationJobStatus.PENDING
            this.createdAt = java.time.OffsetDateTime.now()
        }
        repository.save(job)
        val jobId = job.id!!
        // No PayloadExchange wired at all (null) — same degrade as a genuine Valkey outage.
        val consumer = makeConsumer(ops)

        consumer.consume("{\"id\":\"$jobId\",\"status\":\"FAILED\",\"kositReportRef\":{\"key\":\"lt:kosit:$jobId\"}}")

        val updated = ops.queryService.findById(jobId)
        assertNotNull(updated)
        assertEquals(de.littletrotter.domain.invoice.GenerationJobStatus.FAILED, updated?.status)
        assertNull(updated?.kositReport, "a missing/unresolvable ref must degrade to null (\"unavailable\"), never throw")
    }

    // ── A message shaped like it was written before the ruleId/stage/validator,
    // generatorVersion/profile/checkedAt and validationRun fields existed ──────────

    @Test
    fun `G1-T13 - a legacy-shaped kosit report (pre-F1-F2-F3 fields) is processed as legacy, not rejected`() {
        val repository = InMemoryGenerationJobRepository()
        val invoice = de.littletrotter.domain.invoice.Invoice().apply {
            id = 1L
            invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("LEGACY-2026-0001")
            // PENDING is the real precondition for the PENDING -> VALID edge this test
            // exercises via DocumentGeneratedReactionHandler below (see Invoice
            // .transitionValidationStatusTo's state-machine guard).
            validationStatus = de.littletrotter.domain.shared.ValidationStatus.PENDING
        }
        val invoiceRepository = mock<de.littletrotter.domain.repository.InvoiceRepository>()
        org.mockito.kotlin.whenever(invoiceRepository.save(org.mockito.kotlin.any())).thenAnswer { it.arguments[0] }
        val ops = GenerationJobCollaborators(repository, invoiceRepository)
        val job = de.littletrotter.domain.invoice.GenerationJob().apply {
            this.invoice = invoice
            this.jobType = "PDF_EXPORT"
            // See the "completed updates generation job state" test above for why.
            this.status = de.littletrotter.domain.invoice.GenerationJobStatus.DELEGATED
            this.createdAt = java.time.OffsetDateTime.now()
        }
        repository.save(job)
        val jobId = job.id!!
        // No ruleId/stage/validator, no generatorVersion/profile/checkedAt, no
        // validationRun — exactly the shape an older worker would have sent. The Valkey ref only
        // changed how this string reaches the consumer (via a Valkey ref instead of inline); the
        // legacy-shape tolerance itself (KositReportPayload.decode) is unaffected.
        val legacyKositReport = """{"status":"VALID","summary":"ok","details":[]}"""
        val payloadExchange = mock<PayloadExchange>()
        whenever(payloadExchange.get("lt:kosit:$jobId")).thenReturn(legacyKositReport)
        val consumer = GenerationJobResultConsumer(ops.queryService, ops.resultHandler, ops.llmResultHandler, ops.recoveryService, payloadExchange)
        val raw = """{"id":"$jobId","status":"COMPLETED","resultRefs":{"pdf":"objkey"},"kositReportRef":{"key":"lt:kosit:$jobId"}}"""

        assertDoesNotThrow { consumer.consume(raw) }

        val updated = ops.queryService.findById(jobId)
        assertEquals(de.littletrotter.domain.invoice.GenerationJobStatus.COMPLETED, updated?.status)

        // markCompleted no longer updates Invoice.validationStatus inline; that
        // follow-up reaction now runs off the DocumentGenerated event it fires
        // (DocumentGeneratedReactionHandler), a separate CDI dispatch entry point/transaction.
        // This unit test has no real CDI container to dispatch that event, so it invokes the
        // handler explicitly with the same repositories, mirroring what production wiring does.
        val reactionHandler = de.littletrotter.application.invoice.DocumentGeneratedReactionHandler(
            repository, invoiceRepository, mock(),
        )
        reactionHandler.onDocumentGeneratedUpdateValidationStatus(
            de.littletrotter.domain.event.DocumentGenerated(
                invoiceId = 1L,
                invoiceNumber = invoice.invoiceNumber.value,
                generationJobId = jobId,
                resultRefs = updated?.resultRefs,
            ),
        )
        assertEquals(de.littletrotter.domain.shared.ValidationStatus.VALID, invoice.validationStatus)
    }
}
