package de.littletrotter.application.invoice

import de.littletrotter.application.cache.port.outbound.InvoiceCachePort
import de.littletrotter.domain.compliance.ValidationFinding
import de.littletrotter.domain.event.DocumentGenerated
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTotals
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ValidationFindingRepository
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.fixture.PersistenceEmulation
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.edit
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.Money
import de.littletrotter.shared.ValidationMessage
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * [GenerationJobService.markCompleted] used to write [Invoice.validationStatus] and
 * persist [ValidationFinding] rows inline, in the SAME transaction as its own status write (site
 * of the 26.08.2026 optimistic-lock incident). Both reactions moved here, as
 * [DocumentGenerated] observers — this test suite is the direct successor of the
 * scenarios that used to live in `GenerationJobServiceTest`, now driving
 * [DocumentGeneratedReactionHandler] directly against the same job/invoice fixtures instead of
 * asserting on `markCompleted`'s own (now narrower) side effects.
 */
class DocumentGeneratedReactionHandlerTest {

    private class InMemoryGenerationJobRepository : GenerationJobRepository {
        val jobs = mutableListOf<GenerationJob>()
        private var nextId = 1L

        override fun findByInvoiceId(invoiceId: Long): List<GenerationJob> = jobs.filter { it.invoice?.id == invoiceId }
        override fun findByInvoiceIdWithTaxRates(invoiceId: Long): List<GenerationJob> = findByInvoiceId(invoiceId)
        override fun findPending(): List<GenerationJob> = emptyList()
        override fun findPendingWithInvoice(): List<GenerationJob> = emptyList()
        override fun findById(id: Long): GenerationJob? = jobs.find { it.id == id }
        override fun tryMarkDelegated(id: Long): Boolean = true

        override fun save(job: GenerationJob): GenerationJob {
            if (job.id == null) {
                job.edit { this.id = nextId++ }
            }
            if (jobs.none { it.id == job.id }) jobs.add(job)
            return job
        }
    }

    private class InMemoryInvoiceRepository(vararg invoices: Invoice) : InvoiceRepository, de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository, de.littletrotter.application.invoice.port.outbound.InvoiceListingRepository {
        private val store = invoices.associateBy { it.id!! }.toMutableMap()
        var throwOnSave: Boolean = false

        override fun findById(id: Long): Invoice? = store[id]
        override fun findByIdWithItems(id: Long): Invoice? = store[id]
        override fun findByInvoiceNumber(number: String): Invoice? = store.values.find { it.invoiceNumber.value == number }
        override fun findBySourceQuoteId(quoteId: java.util.UUID): Invoice? = null
        override fun findAll(pageIndex: Int, pageSize: Int): List<Invoice> = store.values.toList()
        override fun count(): Long = store.size.toLong()
        override fun countByStatus(status: InvoiceStatus): Long = store.values.count { it.status == status }.toLong()
        override fun findByRecipientId(recipientId: Long, pageIndex: Int, pageSize: Int): List<Invoice> = emptyList()
        override fun findBySenderProfileId(senderProfileId: Long, pageIndex: Int, pageSize: Int): List<Invoice> = emptyList()
        override fun existsByBillingAddressId(addressId: Long): Boolean = false
        override fun existsByDeliveryAddressId(addressId: Long): Boolean = false
        override fun countForOverview(filterText: String?, status: InvoiceStatus?): Long = count()
        override fun findAllForRecalculation(): List<Invoice> = store.values.toList()
        override fun findInvoiceByItemId(itemId: Long): Invoice? = null
        override fun countOpenInvoicesForSellerVatCountry(senderProfileId: Long, country: de.littletrotter.shared.country.Country): Long = 0
        override fun existsByB2bProfileId(b2bProfileId: Long): Boolean = false
        override fun findAllIdsForRecalculation(): List<Long> = findAllForRecalculation().mapNotNull { it.id }
        override fun findByOriginalInvoiceNumber(number: String): List<de.littletrotter.domain.invoice.Invoice> =
            findAll(0, Int.MAX_VALUE).filter { it.originalInvoiceNumber?.value == number }
        override fun findFinalizedInvoiceNumberAssignments(): List<de.littletrotter.domain.repository.InvoiceNumberAssignment> = emptyList()
        override fun findItemPositions(itemIds: Collection<Long>): Map<Long, String> = emptyMap()
        override fun save(invoice: Invoice): Invoice {
            if (throwOnSave) throw RuntimeException("simulated write failure")
            return invoice.also { store[it.id!!] = it }
        }
        override fun forceVersionIncrement(invoice: Invoice) { PersistenceEmulation.assignVersion(invoice, (invoice.version ?: 0L) + 1L) }
    }

    /** F5 — records every saveAll() call as-is so tests can inspect both the rows and how many
     *  times/batches they arrived in, instead of collapsing everything into one flat list. */
    private class InMemoryValidationFindingRepository : ValidationFindingRepository {
        val saveAllCalls = mutableListOf<List<ValidationFinding>>()
        var throwOnSave: Boolean = false

        override fun saveAll(findings: List<ValidationFinding>) {
            if (throwOnSave) throw RuntimeException("simulated write failure")
            saveAllCalls.add(findings)
        }

        override fun findByInvoiceId(invoiceId: Long): List<ValidationFinding> =
            saveAllCalls.flatten().filter { it.invoiceId == invoiceId }

        override fun findByGenerationJobId(generationJobId: Long): List<ValidationFinding> =
            saveAllCalls.flatten().filter { it.generationJobId == generationJobId }
    }

    private fun validInvoice(id: Long): Invoice = anInvoice(
        id = id,
        invoiceNumber = InvoiceNumber("RE-2026-01-${id.toString().padStart(5, '0')}"),
        invoiceDate = LocalDate.now(),
        totals = InvoiceTotals(Money(BigDecimal("100.00")), Money(BigDecimal("19.00")), Money(BigDecimal("119.00"))),
    )

    private fun completedJob(id: Long, invoice: Invoice, kositReport: String?, createdAt: OffsetDateTime = OffsetDateTime.now()): GenerationJob =
        aGenerationJob {
            this.id = id
            this.invoice = invoice
            this.jobType = "PDF_EXPORT"
            this.status = GenerationJobStatus.COMPLETED
            this.createdAt = createdAt
            this.finishedAt = OffsetDateTime.now()
            this.kositReport = kositReport
        }

    private fun handler(
        jobRepo: GenerationJobRepository,
        invoiceRepo: InvoiceRepository,
        findingRepo: ValidationFindingRepository = InMemoryValidationFindingRepository(),
        cache: InvoiceCachePort = mock(),
        metrics: DocumentGeneratedReactionMetrics = DocumentGeneratedReactionMetrics(SimpleMeterRegistry()),
    ): DocumentGeneratedReactionHandler = DocumentGeneratedReactionHandler(jobRepo, invoiceRepo, findingRepo, cache, metrics)

    /** Item 6 (Welle C) — attaches a plain [java.util.logging.Handler] directly to the JUL logger
     *  backing [DocumentGeneratedReactionHandler]'s `org.jboss.logging.Logger` (jboss-logging
     *  delegates straight through to `java.util.logging` under jboss-logmanager, the backend this
     *  module's surefire config installs — see `<java.util.logging.manager>` in `pom.xml`) so a
     *  test can assert the exact level a catch block logged at, not just that *something* was
     *  logged. */
    private fun captureLogRecords(block: () -> Unit): List<java.util.logging.LogRecord> {
        val julLogger = java.util.logging.Logger.getLogger(DocumentGeneratedReactionHandler::class.java.name)
        val records = mutableListOf<java.util.logging.LogRecord>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) {
                records.add(record)
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val previousLevel = julLogger.level
        julLogger.level = java.util.logging.Level.ALL
        julLogger.addHandler(handler)
        try {
            block()
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = previousLevel
        }
        return records
    }

    private fun eventFor(job: GenerationJob, invoice: Invoice, issued: Boolean = true): DocumentGenerated = DocumentGenerated(
        invoiceId = invoice.id!!,
        invoiceNumber = invoice.invoiceNumber.value,
        generationJobId = job.id!!,
        resultRefs = job.resultRefs,
        issued = issued,
    )

    // ── Draft jobs — DocumentGenerated also fires for a draft's job (GenerationJobUiPushHandler
    // needs to hear about it). Findings stay issued-only; validationStatus and cache eviction run
    // for drafts too, because the draft's own publish marked the invoice PENDING. ──────────────

    @Test
    fun `a draft job completing resolves the PENDING validationStatus its publish wrote`() {
        val invoice = validInvoice(1250L).also { it.transitionValidationStatusTo(ValidationStatus.PENDING) }
        val jobRepo = InMemoryGenerationJobRepository()
        val json = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))
        val job = completedJob(1L, invoice, json).also { jobRepo.save(it) }

        handler(jobRepo, InMemoryInvoiceRepository(invoice))
            .onDocumentGeneratedUpdateValidationStatus(eventFor(job, invoice, issued = false))

        assertEquals(ValidationStatus.VALID, invoice.validationStatus, "a draft must not stay PENDING once its job completed")
    }

    @Test
    fun `a draft job with findings resolves the draft to INVALID`() {
        val invoice = validInvoice(1253L).also { it.transitionValidationStatusTo(ValidationStatus.PENDING) }
        val jobRepo = InMemoryGenerationJobRepository()
        val json = KositReportPayload.encode(
            KositValidationResult(status = KositOutcome.INVALID, details = listOf(ValidationMessage("ERROR", "BR-01 missing", null))),
        )
        val job = completedJob(1L, invoice, json).also { jobRepo.save(it) }

        handler(jobRepo, InMemoryInvoiceRepository(invoice))
            .onDocumentGeneratedUpdateValidationStatus(eventFor(job, invoice, issued = false))

        assertEquals(ValidationStatus.INVALID, invoice.validationStatus)
    }

    @Test
    fun `L24 an issued=false event persists no validation findings`() {
        val invoice = validInvoice(1251L)
        val jobRepo = InMemoryGenerationJobRepository()
        val json = KositReportPayload.encode(
            KositValidationResult(status = KositOutcome.INVALID, details = listOf(ValidationMessage("ERROR", "BR-01 missing", null))),
        )
        val job = completedJob(1L, invoice, json).also { jobRepo.save(it) }
        val findingRepo = InMemoryValidationFindingRepository()

        handler(jobRepo, InMemoryInvoiceRepository(invoice), findingRepo = findingRepo)
            .onDocumentGeneratedPersistFindings(eventFor(job, invoice, issued = false))

        assertTrue(findingRepo.saveAllCalls.isEmpty(), "a draft's job completing must not persist ValidationFinding rows")
    }

    @Test
    fun `a draft job completing evicts the overview cache`() {
        val invoice = validInvoice(1252L)
        val jobRepo = InMemoryGenerationJobRepository()
        val job = completedJob(1L, invoice, null).also { jobRepo.save(it) }
        val cache: InvoiceCachePort = mock()

        handler(jobRepo, InMemoryInvoiceRepository(invoice), cache = cache)
            .onDocumentGeneratedEvictCache(eventFor(job, invoice, issued = false))

        verify(cache).evictOverviewPages()
    }

    @Test
    fun `L24 an issued=true event still runs every reaction, unchanged from pre-L24 behavior`() {
        val invoice = validInvoice(1254L).also { it.transitionValidationStatusTo(ValidationStatus.PENDING) }
        val jobRepo = InMemoryGenerationJobRepository()
        val json = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))
        val job = completedJob(1L, invoice, json).also { jobRepo.save(it) }
        val cache: InvoiceCachePort = mock()

        val h = handler(jobRepo, InMemoryInvoiceRepository(invoice), cache = cache)
        h.onDocumentGeneratedUpdateValidationStatus(eventFor(job, invoice, issued = true))
        h.onDocumentGeneratedEvictCache(eventFor(job, invoice, issued = true))

        assertEquals(ValidationStatus.VALID, invoice.validationStatus)
        verify(cache).evictOverviewPages()
    }

    // ── validationStatus ──────────────────────────────────────────────────────

    @Test
    fun `writes INVALID validationStatus from a JSON kositReport`() {
        // PENDING is the only real precondition (set by DocumentJobPublisher.publish
        // before any job can complete); the guard on Invoice.validationStatus now
        // enforces that PENDING -> INVALID/VALID/UNAVAILABLE edge.
        val invoice = validInvoice(125L).also { it.transitionValidationStatusTo(ValidationStatus.PENDING) }
        val jobRepo = InMemoryGenerationJobRepository()
        val json = KositReportPayload.encode(
            KositValidationResult(
                status = KositOutcome.INVALID,
                summary = "1 validation error(s)",
                details = listOf(ValidationMessage("ERROR", "BR-01 missing", null)),
            )
        )
        val job = completedJob(1L, invoice, json).also { jobRepo.save(it) }

        handler(jobRepo, InMemoryInvoiceRepository(invoice)).onDocumentGeneratedUpdateValidationStatus(eventFor(job, invoice))

        assertEquals(ValidationStatus.INVALID, invoice.validationStatus)
    }

    @Test
    fun `writes VALID validationStatus for a VALID kositReport`() {
        val invoice = validInvoice(126L).also { it.transitionValidationStatusTo(ValidationStatus.PENDING) }
        val jobRepo = InMemoryGenerationJobRepository()
        val json = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))
        val job = completedJob(1L, invoice, json).also { jobRepo.save(it) }

        handler(jobRepo, InMemoryInvoiceRepository(invoice)).onDocumentGeneratedUpdateValidationStatus(eventFor(job, invoice))

        assertEquals(ValidationStatus.VALID, invoice.validationStatus)
    }

    @Test
    fun `leaves validationStatus untouched when the job carries no kositReport`() {
        val invoice = validInvoice(127L)
        val jobRepo = InMemoryGenerationJobRepository()
        val job = completedJob(1L, invoice, kositReport = null).also { jobRepo.save(it) }

        handler(jobRepo, InMemoryInvoiceRepository(invoice)).onDocumentGeneratedUpdateValidationStatus(eventFor(job, invoice))

        assertEquals(ValidationStatus.NOT_VALIDATED, invoice.validationStatus, "no report to decode — nothing to derive a status from")
    }

    @Test
    fun `an older job does not override a newer job's validationStatus`() {
        val invoice = validInvoice(128L).also { it.transitionValidationStatusTo(ValidationStatus.PENDING) }
        val jobRepo = InMemoryGenerationJobRepository()
        val invoiceRepo = InMemoryInvoiceRepository(invoice)
        val validJson = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))
        val invalidJson = KositReportPayload.encode(KositValidationResult(status = KositOutcome.INVALID))
        val olderJob = completedJob(1L, invoice, invalidJson, createdAt = OffsetDateTime.now().minusMinutes(5)).also { jobRepo.save(it) }
        val newerJob = completedJob(2L, invoice, validJson, createdAt = OffsetDateTime.now()).also { jobRepo.save(it) }
        val h = handler(jobRepo, invoiceRepo)

        h.onDocumentGeneratedUpdateValidationStatus(eventFor(newerJob, invoice))
        assertEquals(ValidationStatus.VALID, invoice.validationStatus)

        // The older job finishing its reaction later must not regress the status.
        h.onDocumentGeneratedUpdateValidationStatus(eventFor(olderJob, invoice))
        assertEquals(ValidationStatus.VALID, invoice.validationStatus)
    }

    @Test
    fun `does nothing when the job exists but carries no invoice`() {
        val jobRepo = InMemoryGenerationJobRepository()
        val orphanJob = aGenerationJob {
            jobType = "PDF_EXPORT"
            status = GenerationJobStatus.COMPLETED
            kositReport = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))
        }
        jobRepo.save(orphanJob)
        val h = handler(jobRepo, InMemoryInvoiceRepository())

        assertDoesNotThrow {
            h.onDocumentGeneratedUpdateValidationStatus(
                DocumentGenerated(invoiceId = 999L, invoiceNumber = "RE-2026-00999", generationJobId = orphanJob.id!!, resultRefs = null),
            )
        }
    }

    @Test
    fun `resolves UNAVAILABLE via the ERROR status mapping, not just the decode-failure fallback`() {
        val invoice = validInvoice(131L).also { it.transitionValidationStatusTo(ValidationStatus.PENDING) }
        val jobRepo = InMemoryGenerationJobRepository()
        val json = KositReportPayload.encode(KositValidationResult(status = KositOutcome.ERROR))
        val job = completedJob(1L, invoice, json).also { jobRepo.save(it) }

        handler(jobRepo, InMemoryInvoiceRepository(invoice)).onDocumentGeneratedUpdateValidationStatus(eventFor(job, invoice))

        assertEquals(ValidationStatus.UNAVAILABLE, invoice.validationStatus)
    }

    @Test
    fun `resolves UNAVAILABLE for an unrecognized status string from a report the reader still decodes`() {
        val invoice = validInvoice(132L).also { it.transitionValidationStatusTo(ValidationStatus.PENDING) }
        val jobRepo = InMemoryGenerationJobRepository()
        // Hand-crafted rather than KositReportPayload.encode(): the typed KositOutcome enum can
        // only ever produce VALID/INVALID/ERROR, so this is the one way to reach the `else` arm
        // of toValidationStatus() — a value a future producer might send that this reader
        // predates, which the tolerant decoder still accepts.
        val json = """{"status":"SOMETHING_FUTURE_PRODUCERS_MIGHT_SEND"}"""
        val job = completedJob(1L, invoice, json).also { jobRepo.save(it) }

        handler(jobRepo, InMemoryInvoiceRepository(invoice)).onDocumentGeneratedUpdateValidationStatus(eventFor(job, invoice))

        assertEquals(ValidationStatus.UNAVAILABLE, invoice.validationStatus)
    }

    @Test
    fun `bug 2026-08-30 - a newer job with an undecodable report no longer leaves the invoice stuck PENDING`() {
        // Degrade path: GenerationJobResultConsumer.resolveKositReport returns null on an
        // expired/missing Valkey ref, and markCompleted persists that null. The newest job's own
        // observer must not give up before checking whether an OLDER completed job has a usable
        // report to fall back to.
        val invoice = validInvoice(500L).also { it.transitionValidationStatusTo(ValidationStatus.PENDING) }
        val jobRepo = InMemoryGenerationJobRepository()
        val invoiceRepo = InMemoryInvoiceRepository(invoice)
        val invalidJson = KositReportPayload.encode(KositValidationResult(status = KositOutcome.INVALID))
        val olderJob = completedJob(1L, invoice, invalidJson, createdAt = OffsetDateTime.now().minusMinutes(5)).also { jobRepo.save(it) }
        val newerJobWithoutReport = completedJob(2L, invoice, kositReport = null, createdAt = OffsetDateTime.now()).also { jobRepo.save(it) }
        val h = handler(jobRepo, invoiceRepo)

        // The newest job's own DocumentGenerated fires first — exactly the ordering the bug report
        // reproduced.
        h.onDocumentGeneratedUpdateValidationStatus(eventFor(newerJobWithoutReport, invoice))

        assertEquals(
            ValidationStatus.INVALID,
            invoice.validationStatus,
            "must fall back to the older job's decodable report instead of staying PENDING forever",
        )

        // The older job's own (now redundant) observer running afterwards must not throw or regress.
        h.onDocumentGeneratedUpdateValidationStatus(eventFor(olderJob, invoice))
        assertEquals(ValidationStatus.INVALID, invoice.validationStatus)
    }

    @Test
    fun `bug 2026-08-30 - resolves UNAVAILABLE when no completed job has a decodable report`() {
        val invoice = validInvoice(501L).also { it.transitionValidationStatusTo(ValidationStatus.PENDING) }
        val jobRepo = InMemoryGenerationJobRepository()
        val invoiceRepo = InMemoryInvoiceRepository(invoice)
        completedJob(1L, invoice, kositReport = null, createdAt = OffsetDateTime.now().minusMinutes(5)).also { jobRepo.save(it) }
        val newerJobWithoutReport = completedJob(2L, invoice, kositReport = null, createdAt = OffsetDateTime.now()).also { jobRepo.save(it) }
        val h = handler(jobRepo, invoiceRepo)

        h.onDocumentGeneratedUpdateValidationStatus(eventFor(newerJobWithoutReport, invoice))

        assertEquals(ValidationStatus.UNAVAILABLE, invoice.validationStatus)
    }

    @Test
    fun `bug 2026-08-30 - a still-running sibling job blocks resolution until it too finishes`() {
        val invoice = validInvoice(502L).also { it.transitionValidationStatusTo(ValidationStatus.PENDING) }
        val jobRepo = InMemoryGenerationJobRepository()
        val invoiceRepo = InMemoryInvoiceRepository(invoice)
        val validJson = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))
        val completedFirstJob = completedJob(1L, invoice, validJson, createdAt = OffsetDateTime.now().minusMinutes(5)).also { jobRepo.save(it) }
        val stillRunningJob = aGenerationJob {
            id = 2L
            this.invoice = invoice
            jobType = "PDF_EXPORT"
            status = GenerationJobStatus.RUNNING
            createdAt = OffsetDateTime.now()
        }.also { jobRepo.save(it) }
        val h = handler(jobRepo, invoiceRepo)

        h.onDocumentGeneratedUpdateValidationStatus(eventFor(completedFirstJob, invoice))

        assertEquals(
            ValidationStatus.PENDING,
            invoice.validationStatus,
            "must wait for the still-running sibling job before resolving",
        )

        // The still-running job now finishes with a worse outcome — resolution must pick it up.
        stillRunningJob.edit { this.status = GenerationJobStatus.COMPLETED }
        val invalidJson = KositReportPayload.encode(KositValidationResult(status = KositOutcome.INVALID))
        stillRunningJob.edit { this.kositReport = invalidJson }
        jobRepo.save(stillRunningJob)

        h.onDocumentGeneratedUpdateValidationStatus(eventFor(stillRunningJob, invoice))
        assertEquals(ValidationStatus.INVALID, invoice.validationStatus)
    }

    @Test
    fun `bug 2026-08-30 - identical createdAt timestamps break ties deterministically by id`() {
        // Two jobs created in the same action can share a createdAt at typical column precision;
        // the higher id (the job created later within that same action) must win deterministically,
        // regardless of the order the repository happens to return them in.
        val tie = OffsetDateTime.now()
        val invoice = validInvoice(503L).also { it.transitionValidationStatusTo(ValidationStatus.PENDING) }
        val jobRepo = InMemoryGenerationJobRepository()
        val invoiceRepo = InMemoryInvoiceRepository(invoice)
        val validJson = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))
        val invalidJson = KositReportPayload.encode(KositValidationResult(status = KositOutcome.INVALID))
        val lowerId = completedJob(10L, invoice, validJson, createdAt = tie).also { jobRepo.save(it) }
        completedJob(11L, invoice, invalidJson, createdAt = tie).also { jobRepo.save(it) }
        val h = handler(jobRepo, invoiceRepo)

        // Triggered from the LOWER id's own event — the tiebreaker must still resolve to the
        // higher id's outcome, proving the resolution re-scans all completed jobs rather than
        // trusting whichever event happened to fire.
        h.onDocumentGeneratedUpdateValidationStatus(eventFor(lowerId, invoice))

        assertEquals(ValidationStatus.INVALID, invoice.validationStatus, "the higher id must win a createdAt tie")
    }

    @Test
    fun `boundary - a failed sibling job is excluded from resolution, only completed jobs are considered`() {
        val invoice = validInvoice(504L).also { it.transitionValidationStatusTo(ValidationStatus.PENDING) }
        val jobRepo = InMemoryGenerationJobRepository()
        val invoiceRepo = InMemoryInvoiceRepository(invoice)
        val validJson = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))
        val invalidJson = KositReportPayload.encode(KositValidationResult(status = KositOutcome.INVALID))
        val completed = completedJob(1L, invoice, validJson, createdAt = OffsetDateTime.now().minusMinutes(5)).also { jobRepo.save(it) }
        aGenerationJob {
            id = 2L
            this.invoice = invoice
            jobType = "PDF_EXPORT"
            status = GenerationJobStatus.FAILED
            createdAt = OffsetDateTime.now()
            kositReport = invalidJson
        }.also { jobRepo.save(it) }
        val h = handler(jobRepo, invoiceRepo)

        h.onDocumentGeneratedUpdateValidationStatus(eventFor(completed, invoice))

        assertEquals(
            ValidationStatus.VALID,
            invoice.validationStatus,
            "a FAILED job's report must never influence resolution, even though it is the newer one",
        )
    }

    @Test
    fun `does nothing when the job no longer exists (best-effort, never throws)`() {
        val invoice = validInvoice(129L)
        val jobRepo = InMemoryGenerationJobRepository()
        val h = handler(jobRepo, InMemoryInvoiceRepository(invoice))

        h.onDocumentGeneratedUpdateValidationStatus(
            DocumentGenerated(invoiceId = invoice.id!!, invoiceNumber = invoice.invoiceNumber.value, generationJobId = 999L, resultRefs = null),
        )

        assertEquals(ValidationStatus.NOT_VALIDATED, invoice.validationStatus)
    }

    // ── findings persistence ─────────────────────────────────────────────────────

    @Test
    fun `F5-T01 writes one row per finding with stage and ruleId`() {
        val invoice = validInvoice(300L)
        val jobRepo = InMemoryGenerationJobRepository()
        val findingRepo = InMemoryValidationFindingRepository()
        val json = KositReportPayload.encode(
            KositValidationResult(
                status = KositOutcome.INVALID,
                summary = "2 validation error(s)",
                details = listOf(
                    ValidationMessage("ERROR", "BR-CO-25 missing", ruleId = "BR-CO-25", stage = "KOSIT", validator = "kosit"),
                    ValidationMessage("WARNING", "BR-DE-17 recommendation", ruleId = "BR-DE-17", stage = "KOSIT", validator = "kosit"),
                ),
            )
        )
        val job = completedJob(1L, invoice, json).also { jobRepo.save(it) }

        handler(jobRepo, InMemoryInvoiceRepository(invoice), findingRepo).onDocumentGeneratedPersistFindings(eventFor(job, invoice))

        val rows = findingRepo.findByGenerationJobId(job.id!!)
        assertEquals(2, rows.size)
        assertEquals(setOf("BR-CO-25", "BR-DE-17"), rows.map { it.ruleId }.toSet())
        assertTrue(rows.all { it.stage == "KOSIT" })
        assertTrue(rows.all { it.invoiceId == invoice.id })
    }

    @Test
    fun `F5-T02 VALID with no findings writes no rows and does not throw`() {
        val invoice = validInvoice(301L)
        val jobRepo = InMemoryGenerationJobRepository()
        val findingRepo = InMemoryValidationFindingRepository()
        val json = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))
        val job = completedJob(1L, invoice, json).also { jobRepo.save(it) }

        handler(jobRepo, InMemoryInvoiceRepository(invoice), findingRepo).onDocumentGeneratedPersistFindings(eventFor(job, invoice))

        assertTrue(findingRepo.findByGenerationJobId(job.id!!).isEmpty())
    }

    @Test
    fun `onDocumentGeneratedPersistFindings does nothing when the job no longer exists`() {
        val jobRepo = InMemoryGenerationJobRepository()
        val findingRepo = InMemoryValidationFindingRepository()
        val h = handler(jobRepo, InMemoryInvoiceRepository(), findingRepo)

        assertDoesNotThrow {
            h.onDocumentGeneratedPersistFindings(
                DocumentGenerated(invoiceId = 303L, invoiceNumber = "RE-2026-00303", generationJobId = 999L, resultRefs = null),
            )
        }
        assertTrue(findingRepo.findByGenerationJobId(999L).isEmpty())
    }

    @Test
    fun `onDocumentGeneratedPersistFindings does nothing when the job's kositReport does not decode`() {
        val invoice = validInvoice(304L)
        val jobRepo = InMemoryGenerationJobRepository()
        val findingRepo = InMemoryValidationFindingRepository()
        val job = completedJob(1L, invoice, kositReport = "not-json").also { jobRepo.save(it) }

        handler(jobRepo, InMemoryInvoiceRepository(invoice), findingRepo).onDocumentGeneratedPersistFindings(eventFor(job, invoice))

        assertTrue(findingRepo.findByGenerationJobId(job.id!!).isEmpty())
    }

    @Test
    fun `F5-T03 a legacy payload without ruleIds writes rows with a null ruleId, no throw`() {
        val invoice = validInvoice(302L)
        val jobRepo = InMemoryGenerationJobRepository()
        val findingRepo = InMemoryValidationFindingRepository()
        val json = KositReportPayload.encode(
            KositValidationResult(
                status = KositOutcome.INVALID,
                summary = "1 validation error(s)",
                details = listOf(ValidationMessage("ERROR", "some legacy message")),
            )
        )
        val job = completedJob(1L, invoice, json).also { jobRepo.save(it) }

        handler(jobRepo, InMemoryInvoiceRepository(invoice), findingRepo).onDocumentGeneratedPersistFindings(eventFor(job, invoice))

        val rows = findingRepo.findByGenerationJobId(job.id!!)
        assertEquals(1, rows.size)
        assertEquals(null, rows.first().ruleId)
        assertEquals("some legacy message", rows.first().message)
    }

    @Test
    fun `F5-T04 findings from two jobs of the same invoice stay distinguishable`() {
        val invoice = validInvoice(303L)
        val jobRepo = InMemoryGenerationJobRepository()
        val findingRepo = InMemoryValidationFindingRepository()
        val invalidJson = KositReportPayload.encode(
            KositValidationResult(status = KositOutcome.INVALID, details = listOf(ValidationMessage("ERROR", "older finding", ruleId = "BR-A"))),
        )
        val validJson = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))
        val olderJob = completedJob(1L, invoice, invalidJson).also { jobRepo.save(it) }
        val newerJob = completedJob(2L, invoice, validJson).also { jobRepo.save(it) }
        val h = handler(jobRepo, InMemoryInvoiceRepository(invoice), findingRepo)

        h.onDocumentGeneratedPersistFindings(eventFor(olderJob, invoice))
        h.onDocumentGeneratedPersistFindings(eventFor(newerJob, invoice))

        assertEquals(1, findingRepo.findByGenerationJobId(olderJob.id!!).size)
        assertEquals("BR-A", findingRepo.findByGenerationJobId(olderJob.id!!).first().ruleId)
        assertTrue(findingRepo.findByGenerationJobId(newerJob.id!!).isEmpty())
    }

    @Test
    fun `F5-T08 a finding write failure does not propagate`() {
        val invoice = validInvoice(305L)
        val jobRepo = InMemoryGenerationJobRepository()
        val findingRepo = InMemoryValidationFindingRepository().apply { throwOnSave = true }
        val json = KositReportPayload.encode(
            KositValidationResult(status = KositOutcome.INVALID, details = listOf(ValidationMessage("ERROR", "boom", ruleId = "BR-X"))),
        )
        val job = completedJob(1L, invoice, json).also { jobRepo.save(it) }

        // Must not throw — a finding write failure is a side effect, not the point of the reaction.
        handler(jobRepo, InMemoryInvoiceRepository(invoice), findingRepo).onDocumentGeneratedPersistFindings(eventFor(job, invoice))
    }

    @Test
    fun `item 6 - a finding write failure logs ERROR and increments the failure counter, job completion unaffected`() {
        val invoice = validInvoice(307L)
        val jobRepo = InMemoryGenerationJobRepository()
        val findingRepo = InMemoryValidationFindingRepository().apply { throwOnSave = true }
        val registry = SimpleMeterRegistry()
        val metrics = DocumentGeneratedReactionMetrics(registry)
        val json = KositReportPayload.encode(
            KositValidationResult(status = KositOutcome.INVALID, details = listOf(ValidationMessage("ERROR", "boom", ruleId = "BR-X"))),
        )
        val job = completedJob(1L, invoice, json).also { jobRepo.save(it) }
        val h = handler(jobRepo, InMemoryInvoiceRepository(invoice), findingRepo, metrics = metrics)

        val records = captureLogRecords {
            // job completion (this call itself) must still succeed even though the reaction fails.
            h.onDocumentGeneratedPersistFindings(eventFor(job, invoice))
        }

        assertTrue(records.any { it.level.name == "ERROR" }, "a swallowed failure must be logged at ERROR, not WARN")
        assertEquals(
            1.0,
            metrics.failureCount(DocumentGeneratedReactionMetrics.Reaction.PERSIST_FINDINGS),
            "the failure counter for this reaction must increment exactly once",
        )
    }

    @Test
    fun `a finding write failure never affects the sibling validationStatus reaction`() {
        // The two reactions are separate CDI dispatch entry points/transactions now;
        // this pins that they are truly independent, not just "happen to not interfere today".
        val invoice = validInvoice(306L).also { it.transitionValidationStatusTo(ValidationStatus.PENDING) }
        val jobRepo = InMemoryGenerationJobRepository()
        val invoiceRepo = InMemoryInvoiceRepository(invoice)
        val findingRepo = InMemoryValidationFindingRepository().apply { throwOnSave = true }
        val json = KositReportPayload.encode(KositValidationResult(status = KositOutcome.INVALID))
        val job = completedJob(1L, invoice, json).also { jobRepo.save(it) }
        val h = handler(jobRepo, invoiceRepo, findingRepo)

        h.onDocumentGeneratedPersistFindings(eventFor(job, invoice))
        h.onDocumentGeneratedUpdateValidationStatus(eventFor(job, invoice))

        assertEquals(ValidationStatus.INVALID, invoice.validationStatus)
    }

    // ── The merged BUSINESS stage reaches both the invoice and the findings table ──────

    @Test
    fun `ARC-6 the merged BUSINESS finding reaches both the invoice and the findings table`() {
        val stages = listOf(
            de.littletrotter.shared.ValidationStageResult("SYNTAX", KositOutcome.VALID),
            de.littletrotter.shared.ValidationStageResult(
                "BUSINESS", KositOutcome.INVALID,
                findings = listOf(ValidationMessage("ERROR", "missing invoice date", ruleId = "CORE.INVOICE_DATE", stage = "BUSINESS", validator = "compliance", location = "header")),
            ),
        )
        val run = de.littletrotter.shared.ValidationRun(stages, de.littletrotter.shared.ValidationRun.overallOutcomeOf(stages), startedAt = "2026-08-22T00:00:00Z")
        val mergedReport = KositReportPayload.encode(
            KositValidationResult(status = run.overall, details = stages.flatMap { it.findings }, validationRun = run),
        )
        val invoice = validInvoice(700L).also { it.transitionValidationStatusTo(ValidationStatus.PENDING) }
        val jobRepo = InMemoryGenerationJobRepository()
        val findingRepo = InMemoryValidationFindingRepository()
        val job = completedJob(1L, invoice, mergedReport).also { jobRepo.save(it) }
        val h = handler(jobRepo, InMemoryInvoiceRepository(invoice), findingRepo)

        h.onDocumentGeneratedUpdateValidationStatus(eventFor(job, invoice))
        h.onDocumentGeneratedPersistFindings(eventFor(job, invoice))

        assertEquals(ValidationStatus.INVALID, invoice.validationStatus)
        val rows = findingRepo.findByGenerationJobId(job.id!!)
        assertTrue(rows.any { it.ruleId == "CORE.INVOICE_DATE" && it.stage == "BUSINESS" && it.location == "header" })
    }

    // ── cache eviction ────────────────────────────────────────────────────────

    @Test
    fun `evicts the overview cache`() {
        val invoice = validInvoice(400L)
        val jobRepo = InMemoryGenerationJobRepository()
        val job = completedJob(1L, invoice, null).also { jobRepo.save(it) }
        val cache: InvoiceCachePort = mock()

        handler(jobRepo, InMemoryInvoiceRepository(invoice), cache = cache).onDocumentGeneratedEvictCache(eventFor(job, invoice))

        verify(cache).evictOverviewPages()
    }

    @Test
    fun `a cache eviction failure does not propagate`() {
        val invoice = validInvoice(401L)
        val jobRepo = InMemoryGenerationJobRepository()
        val job = completedJob(1L, invoice, null).also { jobRepo.save(it) }
        val cache: InvoiceCachePort = mock()
        org.mockito.kotlin.whenever(cache.evictOverviewPages()).thenThrow(RuntimeException("valkey down"))

        // Must not throw.
        handler(jobRepo, InMemoryInvoiceRepository(invoice), cache = cache).onDocumentGeneratedEvictCache(eventFor(job, invoice))
    }

    @Test
    fun `item 6 - a cache eviction failure logs ERROR and increments the failure counter, job completion unaffected`() {
        val invoice = validInvoice(402L)
        val jobRepo = InMemoryGenerationJobRepository()
        val job = completedJob(1L, invoice, null).also { jobRepo.save(it) }
        val cache: InvoiceCachePort = mock()
        org.mockito.kotlin.whenever(cache.evictOverviewPages()).thenThrow(RuntimeException("valkey down"))
        val registry = SimpleMeterRegistry()
        val metrics = DocumentGeneratedReactionMetrics(registry)
        val h = handler(jobRepo, InMemoryInvoiceRepository(invoice), cache = cache, metrics = metrics)

        val records = captureLogRecords {
            // job completion (this call itself) must still succeed even though the reaction fails.
            h.onDocumentGeneratedEvictCache(eventFor(job, invoice))
        }

        assertTrue(records.any { it.level.name == "ERROR" }, "a swallowed failure must be logged at ERROR, not WARN")
        assertEquals(
            1.0,
            metrics.failureCount(DocumentGeneratedReactionMetrics.Reaction.EVICT_CACHE),
            "the failure counter for this reaction must increment exactly once",
        )
    }

    // ── counter visibility before any failure (item 6) ───────────────────────────

    @Test
    fun `item 6 - all three failure counters are registered at zero before any failure occurs`() {
        val registry = SimpleMeterRegistry()

        DocumentGeneratedReactionMetrics(registry)

        // The Punkt-6 recommendation is "read the counter before building anything". That only
        // works if the series exists while the count is still zero: Micrometer creates a meter on
        // first use, so a counter registered inside recordFailure would be absent from /q/metrics
        // until the first failure, making "no failures yet" indistinguishable from "this build has
        // no counter". Absence must mean not-deployed, 0 must mean no-failures.
        DocumentGeneratedReactionMetrics.Reaction.entries.forEach { reaction ->
            val counter = registry.find("document_generated.reaction.failures")
                .tag("reaction", reaction.metricTag)
                .counter()
            assertNotNull(counter, "counter for ${reaction.metricTag} must exist before any failure")
            assertEquals(0.0, counter!!.count(), "a freshly registered counter must read 0")
        }
    }

    // ── validationStatus failure visibility (item 6) ─────────────────────────────

    @Test
    fun `item 6 - a validationStatus write failure logs ERROR and increments the failure counter, job completion unaffected`() {
        val invoice = validInvoice(504L).also { it.transitionValidationStatusTo(ValidationStatus.PENDING) }
        val jobRepo = InMemoryGenerationJobRepository()
        val invoiceRepo = InMemoryInvoiceRepository(invoice).apply { throwOnSave = true }
        val json = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))
        val job = completedJob(1L, invoice, json).also { jobRepo.save(it) }
        val registry = SimpleMeterRegistry()
        val metrics = DocumentGeneratedReactionMetrics(registry)
        val h = handler(jobRepo, invoiceRepo, metrics = metrics)

        val records = captureLogRecords {
            // job completion (this call itself) must still succeed even though the reaction fails.
            h.onDocumentGeneratedUpdateValidationStatus(eventFor(job, invoice))
        }

        assertTrue(records.any { it.level.name == "ERROR" }, "a swallowed failure must be logged at ERROR, not WARN")
        assertEquals(
            1.0,
            metrics.failureCount(DocumentGeneratedReactionMetrics.Reaction.UPDATE_VALIDATION_STATUS),
            "the failure counter for this reaction must increment exactly once",
        )
        // The invoice's in-memory state was mutated (transitionValidationStatusTo ran before the
        // failing save()), but the important pin is that no exception escaped the reaction.
    }
}
