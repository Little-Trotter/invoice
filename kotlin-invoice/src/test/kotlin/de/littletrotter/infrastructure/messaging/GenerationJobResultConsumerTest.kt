package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.cache.InvoiceCachePort
import de.littletrotter.application.cache.NoopInvoiceCachePort
import de.littletrotter.application.invoice.GenerationJobEventDispatcher
import de.littletrotter.application.invoice.GenerationJobLlmResultHandler
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.GenerationJobRecoveryService
import de.littletrotter.application.invoice.GenerationJobResultHandler
import de.littletrotter.application.invoice.NoOpInvoiceRepository
import de.littletrotter.application.invoice.NoOpLlmAnalysisRepository
import de.littletrotter.SynchronousEventProducer
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.repository.GenerationJobRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import de.littletrotter.test.FIXED_TIME_SOURCE

class GenerationJobResultConsumerTest {

    private fun stubRepo(findById: (Long) -> GenerationJob?): GenerationJobRepository = object : GenerationJobRepository {
        override fun findByInvoiceId(invoiceId: Long): List<GenerationJob> = emptyList()
        override fun findByInvoiceIdWithTaxRates(invoiceId: Long): List<GenerationJob> = emptyList()
        override fun findPending(): List<GenerationJob> = emptyList()
        override fun findPendingWithInvoice(): List<GenerationJob> = emptyList()
        override fun findById(id: Long): GenerationJob? = findById(id)
        override fun save(job: GenerationJob): GenerationJob = job
        override fun tryMarkDelegated(id: Long): Boolean = true
    }

    private fun eventBus() = SynchronousEventProducer().produceDomainEvent()

    @Test
    fun `malformed payload does not call service`() {
        val repo1 = stubRepo { throw AssertionError("should not be called") }
        val eventDispatcher1 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo1) {
            override fun findById(id: Long): GenerationJob? { throw AssertionError("should not be called") }
        }
        val resultHandler = object : GenerationJobResultHandler(repo1, eventDispatcher1, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo1, mock(), mock(), eventDispatcher1, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo1, mock(), eventDispatcher1, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)
        consumer.consume("not-json") // should not throw
    }

    @Test
    fun `missing id in payload does not call service`() {
        val repo2 = stubRepo { throw AssertionError("should not be called") }
        val eventDispatcher2 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo2) {
            override fun findById(id: Long): GenerationJob? { throw AssertionError("should not be called") }
        }
        val resultHandler = object : GenerationJobResultHandler(repo2, eventDispatcher2, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo2, mock(), mock(), eventDispatcher2, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo2, mock(), eventDispatcher2, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)
        val payload = "{ \"status\": \"COMPLETED\" }"
        consumer.consume(payload)
    }

    // The consumer no longer publishes a UI event nor writes
    // Invoice.validationStatus itself; both now happen off the DocumentGenerated event
    // markCompleted fires (GenerationJobUiPushHandler/DocumentGeneratedReactionHandler, each
    // covered by its own dedicated test). This test now only pins the consumer's own dispatch:
    // a COMPLETED payload calls markCompleted.
    @Test
    fun `completed payload marks completed`() {
        var markedCompleted = false
        val job = GenerationJob().apply { id = 123L; invoice = Invoice().apply { id = 77L } }
        val repo3 = stubRepo { job }
        val eventDispatcher3 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo3) {
            override fun findById(id: Long): GenerationJob? = if (id < 0) null else job
        }
        val resultHandler = object : GenerationJobResultHandler(repo3, eventDispatcher3, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob {
                            markedCompleted = true
                            return job
                        }
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo3, mock(), mock(), eventDispatcher3, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo3, mock(), eventDispatcher3, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)
        val payload = "{ \"id\": \"123\", \"status\": \"COMPLETED\", \"resultRefs\": { \"pdf\": \"s3://x" + "\" } }"
        consumer.consume(payload)
        assertTrue(markedCompleted)
    }

    // ── the 2026-09-17 live incident: GenerationJobProcessor.resetStaleDelegatedJobs
    // resets a job to PENDING while its original worker attempt is still genuinely in flight; that
    // attempt's later, real COMPLETED result must be recorded, not thrown away as an illegal
    // PENDING -> COMPLETED transition routed to the DLQ. Uses the REAL (non-overridden)
    // GenerationJobResultHandler/GenerationJobRecoveryService, so this exercises the actual reclaimAndTransitionTo bridge end to end,
    // not a mocked markCompleted. ─────────────────────────────────────────────────────────────

    @Test
    fun `ARC91-06 a late genuine COMPLETED result for a job reset back to PENDING is recorded, never routed to the DLQ`() {
        val job = GenerationJob().apply {
            id = 930L
            invoice = Invoice().apply { id = 93L }
            status = de.littletrotter.domain.invoice.GenerationJobStatus.PENDING
        }
        val repo = stubRepo { job }
        val eventDispatcher = GenerationJobEventDispatcher(eventBus(), null)
        val queryService = GenerationJobQueryService(repo)
        val resultHandler = GenerationJobResultHandler(repo, eventDispatcher, time = FIXED_TIME_SOURCE)
        val llmResultHandler = GenerationJobLlmResultHandler(repo, NoOpLlmAnalysisRepository, NoOpInvoiceRepository, eventDispatcher, time = FIXED_TIME_SOURCE)
        val recoveryService = GenerationJobRecoveryService(repo, NoOpInvoiceRepository, eventDispatcher, llmResultHandler, time = FIXED_TIME_SOURCE)
        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        consumer.consume(
            """{ "id": "930", "status": "COMPLETED", "resultRefs": {"objectKey":"invoice/930.pdf","downloadUrl":"http://rustfs:9000/documents/invoice/930.pdf"} }"""
        )

        assertEquals(
            de.littletrotter.domain.invoice.GenerationJobStatus.COMPLETED,
            job.status,
            "a genuine worker result for a job the app's own stale-reset put back to PENDING must be recorded as COMPLETED, never lost to the DLQ",
        )
        assertTrue(job.resultRefs?.contains("invoice/930.pdf") == true, "the real objectKey must survive into resultRefs")
    }

    @Test
    fun `ARC91-06 a late genuine FAILED result for a job reset back to PENDING is recorded, never routed to the DLQ`() {
        val job = GenerationJob().apply {
            id = 931L
            invoice = Invoice().apply { id = 94L }
            status = de.littletrotter.domain.invoice.GenerationJobStatus.PENDING
        }
        val repo = stubRepo { job }
        val eventDispatcher = GenerationJobEventDispatcher(eventBus(), null)
        val queryService = GenerationJobQueryService(repo)
        val resultHandler = GenerationJobResultHandler(repo, eventDispatcher, time = FIXED_TIME_SOURCE)
        val llmResultHandler = GenerationJobLlmResultHandler(repo, NoOpLlmAnalysisRepository, NoOpInvoiceRepository, eventDispatcher, time = FIXED_TIME_SOURCE)
        val recoveryService = GenerationJobRecoveryService(repo, NoOpInvoiceRepository, eventDispatcher, llmResultHandler, time = FIXED_TIME_SOURCE)
        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        consumer.consume("""{ "id": "931", "status": "FAILED", "resultRefs": {"error":"engine rejected the document"} }""")

        assertEquals(
            de.littletrotter.domain.invoice.GenerationJobStatus.FAILED,
            job.status,
            "a genuine worker FAILED result for a job the app's own stale-reset put back to PENDING must be recorded, never lost to the DLQ",
        )
    }

    @Test
    fun `completed payload resolves kositReportRef and forwards it to markCompleted`() {
        var capturedKositReport: String? = null
        val job = GenerationJob().apply { id = 321L; invoice = Invoice().apply { id = 11L } }
        val repo4 = stubRepo { job }
        val eventDispatcher4 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo4) {
            override fun findById(id: Long): GenerationJob? = if (id < 0) null else job
        }
        val resultHandler = object : GenerationJobResultHandler(repo4, eventDispatcher4, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob {
                            capturedKositReport = kositReport
                            return job
                        }
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo4, mock(), mock(), eventDispatcher4, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo4, mock(), eventDispatcher4, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        // AP-M6 — the report no longer travels inline; the consumer resolves it from Valkey via
        // the kositReportRef.
        val payloadExchange = org.mockito.kotlin.mock<de.littletrotter.shared.PayloadExchange>()
        org.mockito.kotlin.whenever(payloadExchange.get("lt:kosit:321")).thenReturn("validation-ok")
        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService, payloadExchange)
        val payload = """{ "id": "321", "status": "COMPLETED", "kositReportRef": {"key": "lt:kosit:321"} }"""
        consumer.consume(payload)
        assertEquals("validation-ok", capturedKositReport)
    }

    @Test
    fun `AP-M6 - a missing kositReportRef key resolves to null (unavailable), not a processing failure`() {
        var capturedKositReport: String? = "not-yet-called"
        val job = GenerationJob().apply { id = 322L; invoice = Invoice().apply { id = 12L } }
        val repo5 = stubRepo { job }
        val eventDispatcher5 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo5) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo5, eventDispatcher5, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob {
                            capturedKositReport = kositReport
                            return job
                        }
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo5, mock(), mock(), eventDispatcher5, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo5, mock(), eventDispatcher5, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val payloadExchange = org.mockito.kotlin.mock<de.littletrotter.shared.PayloadExchange>()
        org.mockito.kotlin.whenever(payloadExchange.get("lt:kosit:322")).thenReturn(null)
        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService, payloadExchange)
        val payload = """{ "id": "322", "status": "COMPLETED", "kositReportRef": {"key": "lt:kosit:322"} }"""

        consumer.consume(payload)

        assertNull(capturedKositReport, "an expired/missing ref must degrade to null, never throw or block COMPLETED")
    }

    @Test
    fun `AP-M6 - no kositReportRef at all (blank report) resolves to null without touching PayloadExchange`() {
        var capturedKositReport: String? = "not-yet-called"
        val job = GenerationJob().apply { id = 323L; invoice = Invoice().apply { id = 13L } }
        val repo6 = stubRepo { job }
        val eventDispatcher6 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo6) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo6, eventDispatcher6, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob {
                            capturedKositReport = kositReport
                            return job
                        }
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo6, mock(), mock(), eventDispatcher6, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo6, mock(), eventDispatcher6, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val payloadExchange = org.mockito.kotlin.mock<de.littletrotter.shared.PayloadExchange>()
        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService, payloadExchange)

        consumer.consume("""{ "id": "323", "status": "COMPLETED" }""")

        assertNull(capturedKositReport)
        org.mockito.kotlin.verify(payloadExchange, org.mockito.kotlin.never()).get(org.mockito.kotlin.any())
    }

    // See "completed payload marks completed" above: the UI push moved to
    // GenerationJobUiPushHandler, observing the GenerationJobFailed event markFailed fires.
    @Test
    fun `failed payload marks failed`() {
        var markedFailed = false
        val job = GenerationJob().apply { id = 555L; invoice = Invoice().apply { id = 99L } }
        val repo7 = stubRepo { job }
        val eventDispatcher7 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo7) {
            override fun findById(id: Long): GenerationJob? = if (id < 0) null else job
        }
        val resultHandler = object : GenerationJobResultHandler(repo7, eventDispatcher7, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo7, mock(), mock(), eventDispatcher7, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo7, mock(), eventDispatcher7, llmResultHandler, time = FIXED_TIME_SOURCE) {
            override fun markFailed(job: GenerationJob, message: String?, kositReport: String?, extraResultRefs: Map<String, String>?): GenerationJob {
                            markedFailed = true
                            return job
                        }
        }

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)
        // AP-M6 — the report travels as kositReportRef now; this test only cares about the
        // markFailed side effect, so no ref is needed on the wire at all.
        val payload = "{ \"id\": \"555\", \"status\": \"FAILED\" }"
        consumer.consume(payload)
        assertTrue(markedFailed)
    }

    @Test
    fun `transient findById failures are retried`() {
        var calls = 0
        var markedCompleted = false
        val job = GenerationJob().apply { id = 200L; invoice = Invoice().apply { id = 33L } }
        val repo8 = stubRepo { job }
        val eventDispatcher8 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo8) {
            override fun findById(id: Long): GenerationJob? {
                            calls++
                            if (id < 0) return null
                            if (calls < 3) throw RuntimeException("db hiccup")
                            return job
                        }
        }
        val resultHandler = object : GenerationJobResultHandler(repo8, eventDispatcher8, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob {
                            markedCompleted = true
                            return job
                        }
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo8, mock(), mock(), eventDispatcher8, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo8, mock(), eventDispatcher8, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)
        val payload = "{ \"id\": \"200\", \"status\": \"COMPLETED\", \"resultRefs\": {\"a\":\"b\"} }"
        consumer.consume(payload)
        assertTrue(markedCompleted)
        assertTrue(calls >= 3)
    }

    // ── AP-M10 — closing the gap to 100 % line+branch ─────────────────────────────────────

    @Test
    fun `a missing status field is treated the same as an unrecognized one`() {
        val repo9 = stubRepo { throw AssertionError("markCompleted/markFailed must not be reached") }
        val eventDispatcher9 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo9) {
            override fun findById(id: Long): GenerationJob? = GenerationJob().also { it.id = 701L }
        }
        val resultHandler = object : GenerationJobResultHandler(repo9, eventDispatcher9, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo9, mock(), mock(), eventDispatcher9, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo9, mock(), eventDispatcher9, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)
        // "status" key absent entirely -> payload.status is null, not just a non-matching string
        consumer.consume("""{ "id": "701" }""")
    }

    @Test
    fun `an unknown status is logged and does not call any service method`() {
        val repo10 = stubRepo { throw AssertionError("markCompleted/markFailed must not be reached") }
        val eventDispatcher10 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo10) {
            override fun findById(id: Long): GenerationJob? = GenerationJob().also { it.id = 700L }
        }
        val resultHandler = object : GenerationJobResultHandler(repo10, eventDispatcher10, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo10, mock(), mock(), eventDispatcher10, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo10, mock(), eventDispatcher10, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)
        // must not throw, must not call markCompleted/markFailed (would fail the stubRepo assertion)
        consumer.consume("""{ "id": "700", "status": "SOMETHING_ELSE" }""")
    }

    @Test
    fun `a job id that is never found exhausts all three retries and then gives up`() {
        var calls = 0
        val repo11 = stubRepo { null }
        val eventDispatcher11 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo11) {
            override fun findById(id: Long): GenerationJob? {
                            calls++
                            return null
                        }
        }
        val resultHandler = object : GenerationJobResultHandler(repo11, eventDispatcher11, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo11, mock(), mock(), eventDispatcher11, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo11, mock(), eventDispatcher11, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)
        consumer.consume("""{ "id": "999999", "status": "COMPLETED" }""")

        assertEquals(3, calls, "must retry exactly 3 times before giving up (DLQ, simulated)")
    }

    @Test
    fun `an interrupted retry backoff sets the interrupt flag but this consumer keeps retrying anyway`() {
        // Unlike JobConsumer (kotlin-xpdf) and GenerationJobProcessor, this class's catch block
        // does not `return` after catching InterruptedException from the backoff sleep -- it
        // only re-marks the thread interrupted and falls through to the next attempt. The
        // retry count is therefore still governed solely by the `attempt >= 3` check, same as an
        // uninterrupted run; what this test actually proves is that the interrupted sleep is
        // caught (not left to propagate and crash the consumer) and the flag ends up set.
        var calls = 0
        val repo12 = stubRepo { null }
        val eventDispatcher12 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo12) {
            override fun findById(id: Long): GenerationJob? {
                            calls++
                            return null
                        }
        }
        val resultHandler = object : GenerationJobResultHandler(repo12, eventDispatcher12, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo12, mock(), mock(), eventDispatcher12, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo12, mock(), eventDispatcher12, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        Thread.currentThread().interrupt()
        try {
            consumer.consume("""{ "id": "999998", "status": "COMPLETED" }""")
            assertTrue(Thread.interrupted(), "the interrupt flag must end up set, not silently dropped")
        } finally {
            Thread.interrupted() // clear defensively even if the assertion above already did
        }
        assertEquals(3, calls, "an interrupted backoff does not shorten this consumer's fixed retry count")
    }

    @Test
    fun `a COMPLETED result without resultRefs still marks completed with a null resultRefsJson`() {
        var capturedResultRefsJson: String? = "not-yet-called"
        val job = GenerationJob().apply { id = 601L; invoice = Invoice().apply { id = 61L } }
        val repo13 = stubRepo { job }
        val eventDispatcher13 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo13) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo13, eventDispatcher13, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob {
                            capturedResultRefsJson = resultRefs
                            return job
                        }
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo13, mock(), mock(), eventDispatcher13, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo13, mock(), eventDispatcher13, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService).consume("""{ "id": "601", "status": "COMPLETED" }""")

        assertNull(capturedResultRefsJson, "no resultRefs on the wire must reach markCompleted as null, not an empty string")
    }

    @Test
    fun `a COMPLETED result for a job without an invoice skips the LLM analysis update`() {
        val job = GenerationJob().apply { id = 602L } // invoice stays null
        var llmUpdateInvoiceId: Long? = -1L
        val repo14 = stubRepo { job }
        val eventDispatcher14 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo14) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo14, eventDispatcher14, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob = job
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo14, mock(), mock(), eventDispatcher14, time = FIXED_TIME_SOURCE) {
            override fun updateLlmAnalysisFromResult(invoiceId: Long?, resultRefs: Map<String, String>) {
                            llmUpdateInvoiceId = invoiceId
                        }
        }
        val recoveryService = object : GenerationJobRecoveryService(repo14, mock(), eventDispatcher14, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService).consume("""{ "id": "602", "status": "COMPLETED" }""")

        assertNull(llmUpdateInvoiceId)
    }

    @Test
    fun `a FAILED result for a job without an invoice skips the LLM analysis update`() {
        val job = GenerationJob().apply { id = 603L } // invoice stays null
        var llmFailInvoiceId: Long? = -1L
        val repo15 = stubRepo { job }
        val eventDispatcher15 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo15) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo15, eventDispatcher15, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo15, mock(), mock(), eventDispatcher15, time = FIXED_TIME_SOURCE) {
            override fun failPendingAnalysesForInvoice(invoiceId: Long?) {
                            llmFailInvoiceId = invoiceId
                        }
        }
        val recoveryService = object : GenerationJobRecoveryService(repo15, mock(), eventDispatcher15, llmResultHandler, time = FIXED_TIME_SOURCE) {
            override fun markFailed(job: GenerationJob, message: String?, kositReport: String?, extraResultRefs: Map<String, String>?): GenerationJob = job
        }

        GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService).consume("""{ "id": "603", "status": "FAILED" }""")

        assertNull(llmFailInvoiceId)
    }

    @Test
    fun `a COMPLETED result with a correlationId caches the terminal status`() {
        val job = GenerationJob().apply { id = 604L; invoice = Invoice().apply { id = 64L } }
        val repo16 = stubRepo { job }
        val eventDispatcher16 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo16) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo16, eventDispatcher16, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob = job
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo16, mock(), mock(), eventDispatcher16, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo16, mock(), eventDispatcher16, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val cache: InvoiceCachePort = mock()
        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService, cache)

        consumer.consume("""{ "id": "604", "status": "COMPLETED", "correlationId": "corr-604" }""")

        // A COMPLETED result no longer evicts the overview cache directly here;
        // that now happens off the DocumentGenerated event (DocumentGeneratedReactionHandler),
        // its own test.
        verify(cache).cacheJobStatus(org.mockito.kotlin.eq("corr-604"), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `a FAILED result with a correlationId caches the terminal status`() {
        val job = GenerationJob().apply { id = 605L; invoice = Invoice().apply { id = 65L } }
        val repo17 = stubRepo { job }
        val eventDispatcher17 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo17) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo17, eventDispatcher17, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo17, mock(), mock(), eventDispatcher17, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo17, mock(), eventDispatcher17, llmResultHandler, time = FIXED_TIME_SOURCE) {
            override fun markFailed(job: GenerationJob, message: String?, kositReport: String?, extraResultRefs: Map<String, String>?): GenerationJob = job
        }

        val cache: InvoiceCachePort = mock()
        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService, cache)

        consumer.consume("""{ "id": "605", "status": "FAILED", "correlationId": "corr-605" }""")

        verify(cache).evictAllOverview()
        verify(cache).cacheJobStatus(org.mockito.kotlin.eq("corr-605"), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    // ── decision 2 — the deferred oMLX stage's own SECOND, later result message must merge
    // into the existing job row without regressing job status or re-running completion. ─────────

    @Test
    fun `an LLM_COMPLETED result merges llm refs and updates the analysis, without re-marking the job completed`() {
        val job = GenerationJob().apply { id = 700L; invoice = Invoice().apply { id = 70L } }
        var mergeCalledWith: Pair<GenerationJob, Map<String, String>>? = null
        var llmUpdateCalledWith: Pair<Long?, Map<String, String>>? = null
        val repo18 = stubRepo { job }
        val eventDispatcher18 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo18) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo18, eventDispatcher18, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob {
                            throw AssertionError("LLM_COMPLETED must never re-run markCompleted")
                        }
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo18, mock(), mock(), eventDispatcher18, time = FIXED_TIME_SOURCE) {
            override fun mergeLlmResult(job: GenerationJob, llmResultRefs: Map<String, String>): GenerationJob {
                            mergeCalledWith = job to llmResultRefs
                            return job
                        }
            override fun updateLlmAnalysisFromResult(invoiceId: Long?, resultRefs: Map<String, String>) {
                            llmUpdateCalledWith = invoiceId to resultRefs
                        }
        }
        val recoveryService = object : GenerationJobRecoveryService(repo18, mock(), eventDispatcher18, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val cache: InvoiceCachePort = mock()
        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService, cache)

        consumer.consume(
            """{ "id": "700", "status": "LLM_COMPLETED", "resultRefs": { "llmStatus": "COMPLETED", "llmModel": "qwen", "llmReportKey": "invoice/700-llm-report.md" } }""",
        )

        assertEquals(job, mergeCalledWith?.first)
        assertEquals("COMPLETED", mergeCalledWith?.second?.get("llmStatus"))
        assertEquals("invoice/700-llm-report.md", mergeCalledWith?.second?.get("llmReportKey"))
        assertEquals(70L, llmUpdateCalledWith?.first)
        assertEquals("qwen", llmUpdateCalledWith?.second?.get("llmModel"))
        verify(cache).evictAllOverview()
    }

    @Test
    fun `an LLM_FAILED result without resultRefs and without an invoice still merges defensively`() {
        // the elvis/empty-map branch (no resultRefs on the wire) and the null-invoice
        // branch: both defensive paths must not throw and must still run the merge chain.
        val job = GenerationJob().apply { id = 701L; invoice = null }
        var mergeCalledWith: Map<String, String>? = null
        var llmUpdateCalledWith: Pair<Long?, Map<String, String>>? = null
        val repo19 = stubRepo { job }
        val eventDispatcher19 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo19) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo19, eventDispatcher19, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo19, mock(), mock(), eventDispatcher19, time = FIXED_TIME_SOURCE) {
            override fun mergeLlmResult(job: GenerationJob, llmResultRefs: Map<String, String>): GenerationJob {
                            mergeCalledWith = llmResultRefs
                            return job
                        }
            override fun updateLlmAnalysisFromResult(invoiceId: Long?, resultRefs: Map<String, String>) {
                            llmUpdateCalledWith = invoiceId to resultRefs
                        }
        }
        val recoveryService = object : GenerationJobRecoveryService(repo19, mock(), eventDispatcher19, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val cache: InvoiceCachePort = mock()
        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService, cache)

        consumer.consume("""{ "id": "701", "status": "LLM_FAILED" }""")

        assertEquals(emptyMap<String, String>(), mergeCalledWith, "absent resultRefs merge as an empty map")
        assertEquals(null, llmUpdateCalledWith?.first, "no invoice attached: the analysis update receives a null invoice id")
        verify(cache).evictAllOverview()
    }

    @Test
    fun `an LLM_FAILED result also merges without re-marking the job completed or failed`() {
        val job = GenerationJob().apply { id = 701L; invoice = Invoice().apply { id = 71L } }
        var mergedRefs: Map<String, String>? = null
        val repo20 = stubRepo { job }
        val eventDispatcher20 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo20) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo20, eventDispatcher20, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob {
                            throw AssertionError("LLM_FAILED must never re-run markCompleted")
                        }
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo20, mock(), mock(), eventDispatcher20, time = FIXED_TIME_SOURCE) {
            override fun mergeLlmResult(job: GenerationJob, llmResultRefs: Map<String, String>): GenerationJob {
                            mergedRefs = llmResultRefs
                            return job
                        }
        }
        val recoveryService = object : GenerationJobRecoveryService(repo20, mock(), eventDispatcher20, llmResultHandler, time = FIXED_TIME_SOURCE) {
            override fun markFailed(job: GenerationJob, message: String?, kositReport: String?, extraResultRefs: Map<String, String>?): GenerationJob {
                            throw AssertionError("LLM_FAILED must never re-run markFailed — the job itself already completed")
                        }
        }

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        consumer.consume(
            """{ "id": "701", "status": "LLM_FAILED", "resultRefs": { "llmStatus": "FAILED", "llmModel": "qwen", "llmError": "Zeitüberschreitung" } }""",
        )

        assertEquals("FAILED", mergedRefs?.get("llmStatus"))
        assertEquals("Zeitüberschreitung", mergedRefs?.get("llmError"))
    }

    @Test
    fun `a FAILED result forwards the worker's own llmStatus into markFailed's extraResultRefs`() {
        val job = GenerationJob().apply { id = 702L; invoice = Invoice().apply { id = 72L } }
        var capturedExtraRefs: Map<String, String>? = null
        val repo21 = stubRepo { job }
        val eventDispatcher21 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo21) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo21, eventDispatcher21, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo21, mock(), mock(), eventDispatcher21, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo21, mock(), eventDispatcher21, llmResultHandler, time = FIXED_TIME_SOURCE) {
            override fun markFailed(job: GenerationJob, message: String?, kositReport: String?, extraResultRefs: Map<String, String>?): GenerationJob {
                            capturedExtraRefs = extraResultRefs
                            return job
                        }
        }

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        consumer.consume(
            """{ "id": "702", "status": "FAILED", "resultRefs": { "llmStatus": "FAILED", "error": "should not leak into extraResultRefs" } }""",
        )

        assertEquals(mapOf("llmStatus" to "FAILED"), capturedExtraRefs, "only the llmStatus key is forwarded, not the worker's own 'error' key")
    }

    @Test
    fun `H27-34 - a FAILED result extracts the worker's actual error text into the failure message`() {
        val job = GenerationJob().apply { id = 703L; invoice = Invoice().apply { id = 73L } }
        var capturedFailureMessage: String? = null
        val repo22 = stubRepo { job }
        val eventDispatcher22 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo22) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo22, eventDispatcher22, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo22, mock(), mock(), eventDispatcher22, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo22, mock(), eventDispatcher22, llmResultHandler, time = FIXED_TIME_SOURCE) {
            override fun markFailed(job: GenerationJob, message: String?, kositReport: String?, extraResultRefs: Map<String, String>?): GenerationJob {
                            capturedFailureMessage = message
                            return job
                        }
        }

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        // Worker publishes the actual error (e.g., from JobConsumer.publishRetryExhaustedFailure)
        consumer.consume(
            """{ "id": "703", "status": "FAILED", "resultRefs": { "error": "KoSIT service unreachable: connection timeout after 5000ms" } }""",
        )

        // Before , this would be hardcoded "external worker reported failure"
        // After the fix, it should be the actual error from the worker
        assertEquals(
            "KoSIT service unreachable: connection timeout after 5000ms",
            capturedFailureMessage,
            "markFailed should receive the worker's actual error text, not a hardcoded generic message"
        )
    }

    @Test
    fun `H27-34 - a FAILED result with missing error detail falls back to generic message`() {
        val job = GenerationJob().apply { id = 704L; invoice = Invoice().apply { id = 74L } }
        var capturedFailureMessage: String? = null
        val repo23 = stubRepo { job }
        val eventDispatcher23 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo23) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo23, eventDispatcher23, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo23, mock(), mock(), eventDispatcher23, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo23, mock(), eventDispatcher23, llmResultHandler, time = FIXED_TIME_SOURCE) {
            override fun markFailed(job: GenerationJob, message: String?, kositReport: String?, extraResultRefs: Map<String, String>?): GenerationJob {
                            capturedFailureMessage = message
                            return job
                        }
        }

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        // Payload with no error detail (shouldn't normally happen, but be defensive)
        consumer.consume(
            """{ "id": "704", "status": "FAILED", "resultRefs": {} }""",
        )

        assertTrue(
            capturedFailureMessage?.contains("external worker reported failure") ?: false,
            "should fall back to generic message when no error detail is provided"
        )
    }

    // ── Bug production 2026-08-26 — the L1-cache-poisoning fix: every attempt in the retry loop
    // must start from a genuinely clean Hibernate Session, not just the first one. ─────────────

    @Test
    fun `a wired entityManager is cleared before the single successful attempt`() {
        val job = GenerationJob().apply { id = 606L; invoice = Invoice().apply { id = 66L } }
        val repo24 = stubRepo { job }
        val eventDispatcher24 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo24) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo24, eventDispatcher24, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob = job
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo24, mock(), mock(), eventDispatcher24, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo24, mock(), eventDispatcher24, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val em: EntityManager = mock()
        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService, NoopInvoiceCachePort(), null, em)

        consumer.consume("""{ "id": "606", "status": "COMPLETED" }""")

        verify(em).clear()
    }

    @Test
    fun `the entityManager is cleared again before every retry attempt, not just the first`() {
        var calls = 0
        val repo25 = stubRepo { null }
        val eventDispatcher25 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo25) {
            override fun findById(id: Long): GenerationJob? { calls++; return null }
        }
        val resultHandler = object : GenerationJobResultHandler(repo25, eventDispatcher25, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo25, mock(), mock(), eventDispatcher25, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo25, mock(), eventDispatcher25, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val em: EntityManager = mock()
        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService, NoopInvoiceCachePort(), null, em)

        consumer.consume("""{ "id": "999996", "status": "COMPLETED" }""")

        assertEquals(3, calls, "sanity: this scenario must still exhaust all 3 attempts")
        verify(em, times(3)).clear()
    }

    @Test
    fun `no entityManager wired (the default in every other test here) never throws on clear`() {
        // Every other test in this file already exercises the null-entityManager branch of
        // clearSessionBeforeAttempt via the 1-/2-/3-arg constructors' plain-unit-test default;
        // this test names that branch explicitly so its coverage isn't only an incidental
        // side effect of unrelated assertions.
        val job = GenerationJob().apply { id = 607L; invoice = Invoice().apply { id = 67L } }
        val repo26 = stubRepo { job }
        val eventDispatcher26 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo26) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo26, eventDispatcher26, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob = job
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo26, mock(), mock(), eventDispatcher26, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo26, mock(), eventDispatcher26, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService).consume("""{ "id": "607", "status": "COMPLETED" }""") // must not throw
    }

    // ── decision 4 — idempotent result processing: a re-delivered/old-cycle result for an
    // already-terminal job is a no-op, never a second write. ───────────────────────────────────

    @Test
    fun `a COMPLETED result for an already-COMPLETED job is ignored without any write`() {
        val job = GenerationJob().apply { id = 800L; invoice = Invoice().apply { id = 80L } }
        job.status = de.littletrotter.domain.invoice.GenerationJobStatus.COMPLETED
        val repo27 = stubRepo { job }
        val eventDispatcher27 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo27) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo27, eventDispatcher27, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob =
                            throw AssertionError("a re-delivered COMPLETED result for an already-COMPLETED job must never call markCompleted")
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo27, mock(), mock(), eventDispatcher27, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo27, mock(), eventDispatcher27, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val cache: InvoiceCachePort = mock()
        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService, cache)

        consumer.consume("""{ "id": "800", "status": "COMPLETED", "correlationId": "corr-800" }""")

        // A re-delivered result being ignored means markCompleted (and, with it,
        // DocumentGenerated/GenerationJobFailed) never fires, so neither the UI push nor the
        // cache eviction/validationStatus/findings reactions run either — no separate "must not
        // publish" wiring is needed here any more, the queryService override above already proves it.
        org.mockito.kotlin.verifyNoInteractions(cache)
    }

    @Test
    fun `a COMPLETED result for an already-FAILED job is ignored without any write`() {
        val job = GenerationJob().apply { id = 801L; invoice = Invoice().apply { id = 81L } }
        job.status = de.littletrotter.domain.invoice.GenerationJobStatus.FAILED
        val repo28 = stubRepo { job }
        val eventDispatcher28 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo28) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo28, eventDispatcher28, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob =
                            throw AssertionError("must never call markCompleted for an already-terminal job")
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo28, mock(), mock(), eventDispatcher28, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo28, mock(), eventDispatcher28, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        assertDoesNotThrow { consumer.consume("""{ "id": "801", "status": "COMPLETED" }""") }
    }

    @Test
    fun `a FAILED result for an already-terminal job is ignored without any write`() {
        val job = GenerationJob().apply { id = 802L; invoice = Invoice().apply { id = 82L } }
        job.status = de.littletrotter.domain.invoice.GenerationJobStatus.COMPLETED
        val repo29 = stubRepo { job }
        val eventDispatcher29 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo29) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo29, eventDispatcher29, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo29, mock(), mock(), eventDispatcher29, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo29, mock(), eventDispatcher29, llmResultHandler, time = FIXED_TIME_SOURCE) {
            override fun markFailed(job: GenerationJob, message: String?, kositReport: String?, extraResultRefs: Map<String, String>?): GenerationJob =
                            throw AssertionError("must never call markFailed for an already-terminal job")
        }

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        assertDoesNotThrow { consumer.consume("""{ "id": "802", "status": "FAILED" }""") }
    }

    @Test
    fun `a first-callback result for a job that is still DELEGATED is NOT treated as a re-delivery`() {
        // Sanity/negative case: DELEGATED (not terminal) must still reach markCompleted normally —
        // proof the idempotency guard is keyed on terminal status, not merely "job already exists".
        var markedCompleted = false
        val job = GenerationJob().apply { id = 803L; invoice = Invoice().apply { id = 83L } }
        val repo30 = stubRepo { job }
        val eventDispatcher30 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo30) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo30, eventDispatcher30, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob {
                            markedCompleted = true
                            return job
                        }
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo30, mock(), mock(), eventDispatcher30, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo30, mock(), eventDispatcher30, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService).consume("""{ "id": "803", "status": "COMPLETED" }""")

        assertTrue(markedCompleted)
    }

    @Test
    fun `an LLM_COMPLETED result for a job whose llmStatus is already COMPLETED is ignored`() {
        val job = GenerationJob().apply { id = 804L; invoice = Invoice().apply { id = 84L }; llmStatus = "COMPLETED" }
        val repo31 = stubRepo { job }
        val eventDispatcher31 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo31) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo31, eventDispatcher31, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo31, mock(), mock(), eventDispatcher31, time = FIXED_TIME_SOURCE) {
            override fun mergeLlmResult(job: GenerationJob, llmResultRefs: Map<String, String>): GenerationJob =
                            throw AssertionError("must never re-merge once llmStatus is already terminal")
        }
        val recoveryService = object : GenerationJobRecoveryService(repo31, mock(), eventDispatcher31, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        assertDoesNotThrow {
            consumer.consume("""{ "id": "804", "status": "LLM_COMPLETED", "resultRefs": { "llmStatus": "COMPLETED" } }""")
        }
    }

    @Test
    fun `an LLM_FAILED result for a job whose llmStatus is already FAILED is ignored`() {
        val job = GenerationJob().apply { id = 805L; invoice = Invoice().apply { id = 85L }; llmStatus = "FAILED" }
        val repo32 = stubRepo { job }
        val eventDispatcher32 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo32) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo32, eventDispatcher32, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo32, mock(), mock(), eventDispatcher32, time = FIXED_TIME_SOURCE) {
            override fun mergeLlmResult(job: GenerationJob, llmResultRefs: Map<String, String>): GenerationJob =
                            throw AssertionError("must never re-merge once llmStatus is already terminal")
        }
        val recoveryService = object : GenerationJobRecoveryService(repo32, mock(), eventDispatcher32, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        assertDoesNotThrow {
            consumer.consume("""{ "id": "805", "status": "LLM_FAILED", "resultRefs": { "llmStatus": "FAILED" } }""")
        }
    }

    @Test
    fun `an LLM_COMPLETED result for a job whose llmStatus is still PENDING is merged normally`() {
        var merged = false
        val job = GenerationJob().apply { id = 806L; invoice = Invoice().apply { id = 86L }; llmStatus = "PENDING" }
        val repo33 = stubRepo { job }
        val eventDispatcher33 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo33) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo33, eventDispatcher33, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo33, mock(), mock(), eventDispatcher33, time = FIXED_TIME_SOURCE) {
            override fun mergeLlmResult(job: GenerationJob, llmResultRefs: Map<String, String>): GenerationJob {
                            merged = true
                            return job
                        }
        }
        val recoveryService = object : GenerationJobRecoveryService(repo33, mock(), eventDispatcher33, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService).consume(
            """{ "id": "806", "status": "LLM_COMPLETED", "resultRefs": { "llmStatus": "COMPLETED" } }""",
        )

        assertTrue(merged, "a still-PENDING llmStatus must not be treated as a re-delivery")
    }

    // ── the DLQ path also records last_error on the job, best effort. ─────────────────

    // L3 (fix-generation-job-failure-visibility.md) — the job here is never found at all
    // (findById returns null on every attempt, including inside the real, non-overridden
    // markResultProcessingFailed's own failDlq lookup), so markResultProcessingFailed is a genuine
    // no-op (false) and recordLastError stays the fallback — same wiring/outcome as before this fix.
    @Test
    fun `giving up after 3 attempts records last_error on the job`() {
        var recordedJobId: Long? = null
        var recordedMessage: String? = null
        val repo34 = stubRepo { null }
        val eventDispatcher34 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo34) {
            override fun findById(id: Long): GenerationJob? = null
        }
        val resultHandler = object : GenerationJobResultHandler(repo34, eventDispatcher34, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo34, mock(), mock(), eventDispatcher34, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo34, mock(), eventDispatcher34, llmResultHandler, time = FIXED_TIME_SOURCE) {
            override fun recordLastError(jobId: Long, message: String): Boolean {
                            recordedJobId = jobId
                            recordedMessage = message
                            return true
                        }
        }

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        consumer.consume("""{ "id": "900", "status": "COMPLETED" }""")

        assertEquals(900L, recordedJobId)
        assertTrue(recordedMessage!!.contains("3 attempts"), "message should name the attempt count: $recordedMessage")
        assertTrue(recordedMessage!!.contains("GenerationJob not found"), "message should carry the underlying error: $recordedMessage")
    }

    // ── L3 (fix-generation-job-failure-visibility.md) — the DLQ path now persists a terminal,
    // user-visible FAILED state instead of leaving the job DELEGATED/RUNNING ("Weitergeleitet"/
    // "In Bearbeitung") forever with only the diagnostic recordLastError note above. ─────────────

    @Test
    fun `giving up after 3 attempts persists a terminal FAILED status, not just a diagnostic note`() {
        val job = GenerationJob().apply {
            id = 910L
            invoice = Invoice().apply { id = 91L }
            status = de.littletrotter.domain.invoice.GenerationJobStatus.DELEGATED
        }
        var recordLastErrorCalled = false
        // Only markCompleted is overridden — findById and (crucially) markResultProcessingFailed
        // both run their REAL implementation against stubRepo { job }, exactly like production
        // wiring, so this proves the full DLQ->FAILED write path, not just the consumer's dispatch.
        val repo35 = stubRepo { job }
        val eventDispatcher35 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo35) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo35, eventDispatcher35, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob {
                            // Simulates the observed symptom: a worker result arrives for a DELEGATED job, but
                            // this app's own processing of it keeps failing (e.g. the L2 stale-version
                            // write-back bug) until the consumer gives up.
                            throw RuntimeException("simulated repeated processing failure")
                        }
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo35, mock(), mock(), eventDispatcher35, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo35, mock(), eventDispatcher35, llmResultHandler, time = FIXED_TIME_SOURCE) {
            override fun recordLastError(jobId: Long, message: String): Boolean {
                            recordLastErrorCalled = true
                            return true
                        }
        }

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        consumer.consume("""{ "id": "910", "status": "COMPLETED", "resultRefs": {"pdf":"x"} }""")

        assertEquals(
            de.littletrotter.domain.invoice.GenerationJobStatus.FAILED, job.status,
            "the job must end up terminally FAILED, not stuck DELEGATED forever",
        )
        assertTrue(job.lastError?.contains("3 attempts") == true, "lastError should carry the give-up reason: ${job.lastError}")
        assertFalse(
            recordLastErrorCalled,
            "once the terminal FAILED transition itself succeeds, the best-effort diagnostic fallback must not also fire",
        )
    }

    @Test
    fun `a DLQ give-up for an already-terminal job never resurrects it, and falls back to recordLastError`() {
        val job = GenerationJob().apply {
            id = 911L
            invoice = Invoice().apply { id = 92L }
            status = de.littletrotter.domain.invoice.GenerationJobStatus.COMPLETED
        }
        var recordedMessage: String? = null
        val repo36 = stubRepo { job }
        val eventDispatcher36 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo36) {
            override fun findById(id: Long): GenerationJob? = throw RuntimeException("simulated repeated processing failure")
        }
        val resultHandler = object : GenerationJobResultHandler(repo36, eventDispatcher36, time = FIXED_TIME_SOURCE) {}
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo36, mock(), mock(), eventDispatcher36, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo36, mock(), eventDispatcher36, llmResultHandler, time = FIXED_TIME_SOURCE) {
            override fun recordLastError(jobId: Long, message: String): Boolean {
                            recordedMessage = message
                            return true
                        }
        }

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        consumer.consume("""{ "id": "911", "status": "COMPLETED" }""")

        assertEquals(
            de.littletrotter.domain.invoice.GenerationJobStatus.COMPLETED, job.status,
            "an already-terminal job must never be overwritten by the DLQ give-up path",
        )
        assertTrue(recordedMessage?.contains("3 attempts") == true, "the fallback diagnostic write must still happen: $recordedMessage")
    }

    // ── PO decision 2026-09-13 — the trigger-driven completion path: consume() calls
    // resolveSendOutcome as a plain, synchronous, sequential continuation right after
    // markCompleted/markFailed, but ONLY for a SEND-triggered job. ──────────────────────────────

    @Test
    fun `completed payload calls resolveSendOutcome for a SEND-triggered job`() {
        val job = GenerationJob().apply {
            id = 920L
            invoice = Invoice().apply { id = 190L }
            trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND
        }
        var resolveSendOutcomeCalledWith: Long? = null
        val repo37 = stubRepo { job }
        val eventDispatcher37 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo37) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo37, eventDispatcher37, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob = job
            override fun resolveSendOutcome(job: GenerationJob) {
                            resolveSendOutcomeCalledWith = job.id
                        }
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo37, mock(), mock(), eventDispatcher37, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo37, mock(), eventDispatcher37, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        consumer.consume("""{ "id": "920", "status": "COMPLETED", "resultRefs": {"pdf":"x"} }""")

        assertEquals(920L, resolveSendOutcomeCalledWith, "resolveSendOutcome must run for a SEND-triggered job's COMPLETED result")
    }

    @Test
    fun `completed payload does not call resolveSendOutcome for a plain MANUAL job`() {
        val job = GenerationJob().apply {
            id = 921L
            invoice = Invoice().apply { id = 191L }
            trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.MANUAL
        }
        var resolveSendOutcomeCalled = false
        val repo38 = stubRepo { job }
        val eventDispatcher38 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo38) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo38, eventDispatcher38, time = FIXED_TIME_SOURCE) {
            override fun markCompleted(job: GenerationJob, resultRefs: String?, kositReport: String?, llmStatus: String?): GenerationJob = job
            override fun resolveSendOutcome(job: GenerationJob) {
                            resolveSendOutcomeCalled = true
                        }
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo38, mock(), mock(), eventDispatcher38, time = FIXED_TIME_SOURCE) {}
        val recoveryService = object : GenerationJobRecoveryService(repo38, mock(), eventDispatcher38, llmResultHandler, time = FIXED_TIME_SOURCE) {}

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        consumer.consume("""{ "id": "921", "status": "COMPLETED", "resultRefs": {"pdf":"x"} }""")

        assertFalse(resolveSendOutcomeCalled, "resolveSendOutcome must be a no-op for every non-SEND job")
    }

    @Test
    fun `failed payload calls resolveSendOutcome for a SEND-triggered job`() {
        val job = GenerationJob().apply {
            id = 922L
            invoice = Invoice().apply { id = 192L }
            trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND
        }
        var resolveSendOutcomeCalledWith: Long? = null
        val repo39 = stubRepo { job }
        val eventDispatcher39 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo39) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo39, eventDispatcher39, time = FIXED_TIME_SOURCE) {
            override fun resolveSendOutcome(job: GenerationJob) {
                            resolveSendOutcomeCalledWith = job.id
                        }
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo39, mock(), mock(), eventDispatcher39, time = FIXED_TIME_SOURCE) {
            override fun failPendingAnalysesForInvoice(invoiceId: Long?) {}
        }
        val recoveryService = object : GenerationJobRecoveryService(repo39, mock(), eventDispatcher39, llmResultHandler, time = FIXED_TIME_SOURCE) {
            override fun markFailed(job: GenerationJob, message: String?, kositReport: String?, extraResultRefs: Map<String, String>?): GenerationJob = job
        }

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        consumer.consume("""{ "id": "922", "status": "FAILED" }""")

        assertEquals(922L, resolveSendOutcomeCalledWith, "resolveSendOutcome must run for a SEND-triggered job's FAILED result")
    }

    @Test
    fun `failed payload does not call resolveSendOutcome for a plain MANUAL job`() {
        val job = GenerationJob().apply {
            id = 923L
            invoice = Invoice().apply { id = 193L }
            trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.MANUAL
        }
        var resolveSendOutcomeCalled = false
        val repo40 = stubRepo { job }
        val eventDispatcher40 = de.littletrotter.application.invoice.GenerationJobEventDispatcher(eventBus(), null)
        val queryService = object : GenerationJobQueryService(repo40) {
            override fun findById(id: Long): GenerationJob? = job
        }
        val resultHandler = object : GenerationJobResultHandler(repo40, eventDispatcher40, time = FIXED_TIME_SOURCE) {
            override fun resolveSendOutcome(job: GenerationJob) {
                            resolveSendOutcomeCalled = true
                        }
        }
        val llmResultHandler = object : GenerationJobLlmResultHandler(repo40, mock(), mock(), eventDispatcher40, time = FIXED_TIME_SOURCE) {
            override fun failPendingAnalysesForInvoice(invoiceId: Long?) {}
        }
        val recoveryService = object : GenerationJobRecoveryService(repo40, mock(), eventDispatcher40, llmResultHandler, time = FIXED_TIME_SOURCE) {
            override fun markFailed(job: GenerationJob, message: String?, kositReport: String?, extraResultRefs: Map<String, String>?): GenerationJob = job
        }

        val consumer = GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)

        consumer.consume("""{ "id": "923", "status": "FAILED" }""")

        assertFalse(resolveSendOutcomeCalled, "resolveSendOutcome must be a no-op for every non-SEND job")
    }
}
