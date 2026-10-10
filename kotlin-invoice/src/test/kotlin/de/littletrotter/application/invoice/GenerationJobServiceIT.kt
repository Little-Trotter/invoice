package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.invoice.forceStatusForTest

import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.compliance.ValidationFinding
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.ValidationFindingRepository
import de.littletrotter.shared.Money
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.ValidationRun
import de.littletrotter.shared.ValidationStageResult
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * The real persistence path: [GenerationJobResultHandler.markCompleted], through the actual
 * CDI-wired [PanacheValidationFindingRepository][de.littletrotter.infrastructure.persistence
 * .PanacheValidationFindingRepository] and a real Postgres, writes one `validation_finding` row
 * per finding while leaving the `generation_job.kosit_report` blob untouched — the row-level
 * reporting view and the JSON blob are additive, not a migration of one into the other.
 *
 * Deliberately NOT `@Transactional` on the test methods (unlike most other ITs in this module):
 * [de.littletrotter.infrastructure.persistence.PanacheValidationFindingRepository.saveAll] runs
 * in its own `REQUIRES_NEW` transaction (see that class's own comment for why — a findings-write
 * failure must not roll back the job completion it's a side effect of). A `REQUIRES_NEW`
 * transaction under Postgres' READ COMMITTED isolation cannot see rows an *outer, still-open*
 * transaction inserted — so wrapping this whole test in one `@Transactional` block (as most ITs
 * do, for automatic-commit-then-inspect convenience) would make the just-created
 * `generation_job` row invisible to `saveAll`'s own transaction and fail with a spurious FK
 * violation. Each setup/service call below runs in its own top-level transaction instead,
 * exactly as it does in production (a job is created and later completed as two separate calls,
 * never inside one caller-supplied transaction).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
// staleDelegatedResetCoordinator pulls in the real NatsDelegatedMessageProbe, which needs
// a live Connection; declared explicitly rather than relying on another test class in the same
// JVM run having already set nats.url first.
@QuarkusTestResource(NatsTestResource::class)
class GenerationJobServiceIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobPublishingService: GenerationJobPublishingService

    @Inject
    lateinit var generationJobResultHandler: GenerationJobResultHandler

    @Inject
    lateinit var generationJobRecoveryService: GenerationJobRecoveryService

    @Inject
    lateinit var staleDelegatedResetCoordinator: StaleDelegatedResetCoordinator

    @Inject
    lateinit var validationFindingRepository: ValidationFindingRepository

    @Inject
    lateinit var documentGeneratedObservationRecorder: DocumentGeneratedObservationRecorder

    /**
     * Finding persistence moved from `markCompleted` (this test's original subject)
     * to [DocumentGeneratedReactionHandler]'s own `DocumentGenerated` observer, a real
     * `@ObservesAsync` CDI listener. This is a genuine `@QuarkusTest` with a real CDI container,
     * so `markCompleted`'s `fireAsync(DocumentGenerated(...))` really does dispatch to that
     * handler on the CDI async executor — polling for the row(s) to appear is the correct wait
     * here, not a manual re-invocation of the handler (that would double-write: one run from the
     * real async dispatch, one from the manual call).
     */
    private fun awaitFindings(jobId: Long, expectedCount: Int, timeoutMs: Long = 5_000): List<ValidationFinding> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var rows = validationFindingRepository.findByGenerationJobId(jobId)
        while (rows.size != expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            rows = validationFindingRepository.findByGenerationJobId(jobId)
        }
        return rows
    }

    /** Runs in its own, immediately-committed transaction (see the class KDoc) so the row is
     *  visible to the separately-transacted service calls that follow it in each test. [status]
     *  defaults to SENT (N5: DocumentGenerated/GenerationJobFailed/etc. now gate on
     *  status.isIssued, not invoiceNumber.isAssigned()) — pass DRAFT for a test that needs the
     *  The single-generation lock's "review-time draft job" carve-out instead. */
    private fun persistInvoice(number: String, status: InvoiceStatus = InvoiceStatus.SENT): Invoice = QuarkusTransaction.requiringNew().call<Invoice> {
        val sender = SenderProfile().apply {
            name = "Muster GmbH"
            email = Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            invoicePrefix = "IP${number.takeLast(4)}-"
        }
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("KD-$number")
            name = "Kunde AG"
            email = Email("kunde@kunde-ag.example")
            vatId = VatId("DE987654321")
            iban = Iban("DE02120300000000202051")
        }
        val taxRate = TaxRate().apply {
            name = "19%"
            rate = TaxRatePercent(BigDecimal("19.00"))
            code = "IT_TEST_19_$number"
            category = "S"
            // A fixture, not a selectable reference rule: `isSelectable` defaults to true, and
            // the row would otherwise show up in TaxRuleMigrationIT, which checks the seed data.
            isSelectable = false
        }
        em.persist(sender)
        em.persist(recipient)
        em.persist(taxRate)
        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber(number)
            this.forceStatusForTest(status)
            invoiceDate = LocalDate.now()
            senderProfile = sender
            this.recipient = recipient
            totals.netAmount = Money(BigDecimal("100.00"))
            totals.vatAmount = Money(BigDecimal("19.00"))
            totals.grossAmount = Money(BigDecimal("119.00"))
        }
        val item = InvoiceItem().apply {
            positionId = "1"; description = "Beratung"
            quantity = BigDecimal("1.00")
            unitPriceNet = Money(BigDecimal("100.00"))
            netAmount = Money(BigDecimal("100.00"))
            vatAmount = Money(BigDecimal("19.00"))
            grossAmount = Money(BigDecimal("119.00"))
            taxRateRef = taxRate
        }
        invoice.itemList.asMutableForTest().add(item.also { it.invoice = invoice })
        em.persist(invoice)
        em.flush()
        invoice
    }

    /**
     * Persists a PENDING [de.littletrotter.domain.invoice.GenerationJob] directly,
     * bypassing [GenerationJobCreationService.createJob]. `createJob` fires the real
     * `GenerationJobCreated` CDI event, which [GenerationJobProcessor.onGenerationJobCreated]
     * dispatches onto a real background thread against this genuine `@QuarkusTest` container — a
     * job created that way races that background fast-path publish's own
     * [GenerationJobPublishingService.tryMarkDelegated] claim against whatever synchronous
     * status-mutating call a test makes immediately afterward (usually losing under an isolated
     * run, sometimes winning under a full, heavily-loaded `make test` run, flipping the job to
     * DELEGATED right when a test expects it to still be PENDING). Callers that immediately follow
     * creation with [GenerationJobRecoveryService.failExhaustedJob] use this instead, so the job is
     * deterministically still PENDING at that call.
     */
    private fun persistPendingJob(invoice: Invoice, jobType: String = "PDF_EXPORT"): de.littletrotter.domain.invoice.GenerationJob =
        QuarkusTransaction.requiringNew().call<de.littletrotter.domain.invoice.GenerationJob> {
            // Re-find, not the (by now detached, from persistInvoice's own already-committed
            // transaction) invoice parameter directly: this transaction's own persistence context
            // needs a managed instance to associate the new job with.
            val managedInvoice = em.find(Invoice::class.java, invoice.id)
            val job = de.littletrotter.domain.invoice.GenerationJob().apply {
                this.invoice = managedInvoice
                this.jobType = jobType
            }
            em.persist(job)
            em.flush()
            job
        }

    @Test
    fun `markCompleted persists one row per finding via the real repository, blob unchanged`() {
        val invoice = persistInvoice("ITGJS-2026-0001")
        val job = generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        generationJobPublishingService.markDelegated(job)

        val kositReport = KositReportPayload.encode(
            KositValidationResult(
                status = KositOutcome.INVALID,
                summary = "1 validation error(s)",
                details = listOf(ValidationMessage("ERROR", "BR-CO-25 missing", ruleId = "BR-CO-25", stage = "KOSIT", validator = "kosit")),
            )
        )
        val completed = generationJobResultHandler.markCompleted(job, "{\"pdf\":\"obj\"}", kositReport)

        val rows = awaitFindings(completed.id!!, expectedCount = 1)
        assertEquals(1, rows.size)
        assertEquals("BR-CO-25", rows.first().ruleId)
        assertEquals("KOSIT", rows.first().stage)
        assertEquals(invoice.id, rows.first().invoiceId)

        // the blob is untouched — an old job/reader that only knows the blob still works.
        val reloaded = QuarkusTransaction.requiringNew().call<de.littletrotter.domain.invoice.GenerationJob> {
            em.find(de.littletrotter.domain.invoice.GenerationJob::class.java, completed.id)
        }
        assertNotNull(reloaded.kositReport)
        assertEquals(kositReport, reloaded.kositReport)
    }

    /**
     * L25 (`fix-l25-business-stage-never-evaluated.md`) — the real reproduction: [createJob],
     * [GenerationJobPublishingService.markDelegated] and [GenerationJobResultHandler.markCompleted] each run in
     * their own, separately-committed top-level transaction (see this class's own KDoc), so by the
     * time `markCompleted` runs, `job` is genuinely detached JPA-wise — exactly the shape
     * [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer] hands it after
     * `clearSessionBeforeAttempt()`. Before the fix, evaluating compliance against the detached
     * `job.invoice` threw [org.hibernate.LazyInitializationException] the moment
     * [de.littletrotter.domain.compliance.evaluateCompliance] touched a lazy field
     * (`Invoice.selectedSeller ?: senderProfile`), caught by `mergeBusinessStage`'s own defensive
     * catch, leaving the BUSINESS stage the worker's `skipped` placeholder forever — silently, with
     * no test failure. This asserts the opposite: a real, evaluated outcome.
     */
    @Test
    fun `L25 - markCompleted evaluates the BUSINESS stage for real against a job that crossed a transaction boundary detached`() {
        val invoice = persistInvoice("ITGJS-2026-0025")
        val job = generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        generationJobPublishingService.markDelegated(job)

        // Shape a real worker (kotlin-xpdf) sends: SYNTAX/KOSIT/PDFA already evaluated, BUSINESS
        // always `skipped` — the placeholder this fix closes.
        val stages = listOf(
            ValidationStageResult("SYNTAX", KositOutcome.VALID),
            ValidationStageResult("KOSIT", KositOutcome.VALID),
            ValidationStageResult("PDFA", KositOutcome.VALID),
            ValidationStageResult("BUSINESS", KositOutcome.VALID, skipped = true, skipReason = "worker cannot evaluate compliance"),
        )
        val run = ValidationRun(stages = stages, overall = ValidationRun.overallOutcomeOf(stages), startedAt = "2026-09-07T00:00:00Z")
        val workerReport = KositReportPayload.encode(KositValidationResult(status = run.overall, validationRun = run))

        val completed = generationJobResultHandler.markCompleted(job, "{\"pdf\":\"obj\"}", workerReport)

        val mergedRun = KositReportPayload.decode(completed.kositReport)!!.validationRun!!
        val businessStage = mergedRun.stages.single { it.stage == "BUSINESS" }
        assertEquals(
            false, businessStage.skipped,
            "L25 - the BUSINESS stage must carry a real, evaluated outcome, never the worker's " +
                "skipped placeholder, even though the job crossed a transaction boundary detached",
        )
        // Re-read the persisted row directly — proves the real outcome actually landed in the DB,
        // not just on the in-memory return value.
        val reloaded = QuarkusTransaction.requiringNew().call<de.littletrotter.domain.invoice.GenerationJob> {
            em.find(de.littletrotter.domain.invoice.GenerationJob::class.java, completed.id)
        }
        val reloadedBusinessStage = KositReportPayload.decode(reloaded.kositReport)!!.validationRun!!.stages.single { it.stage == "BUSINESS" }
        assertEquals(false, reloadedBusinessStage.skipped, "the real BUSINESS outcome must be persisted, not just returned in-memory")
    }

    @Test
    fun `repeated print job of the same invoice does not duplicate the previous run's findings`() {
        // N5 — job1 is created a "review-time draft job" (invoice still DRAFT), same as the
        // comment on GenerationJobCreationService.createJob describes: its own GenerationJob
        // .issued is frozen false at creation and never counts toward the single-generation lock
        // (hasCompletedIssuedJob), however the invoice's status by the time job1 actually
        // COMPLETES below. The invoice transitions to SENT right after job1 is created — matching
        // a real send happening while a print job is in flight — so job1's markCompleted still
        // sees an issued invoice (status.isIssued gates DocumentGenerated/finding persistence
        // since N5) while job2, created fresh afterwards, is NOT blocked by job1 (issued=false).
        val invoice = persistInvoice("ITGJS-2026-0002", status = InvoiceStatus.DRAFT)
        val job1 = generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        generationJobPublishingService.markDelegated(job1)
        // A bulk JPQL update, not forceStatusForTest on the loaded managed entity: the latter
        // mutates the field via reflection, which Hibernate's enhanced dirty-tracking never
        // observes (it instruments PUTFIELD instructions inside Invoice's own bytecode, not raw
        // Field.set() calls from outside it), so the change never reached the database — found as
        // a real regression while proving this task's real-IT evidence. markCompleted below reloads
        // the invoice in its own transaction and must see the persisted SENT status.
        QuarkusTransaction.requiringNew().run {
            em.createQuery("UPDATE Invoice i SET i.status = :status WHERE i.id = :id")
                .setParameter("status", InvoiceStatus.SENT)
                .setParameter("id", invoice.id)
                .executeUpdate()
        }
        val report = KositReportPayload.encode(
            KositValidationResult(
                status = KositOutcome.INVALID,
                details = listOf(ValidationMessage("ERROR", "first run", ruleId = "BR-X", stage = "KOSIT", validator = "kosit")),
            )
        )
        generationJobResultHandler.markCompleted(job1, "{}", report)
        awaitFindings(job1.id!!, expectedCount = 1)

        val job2 = generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        generationJobPublishingService.markDelegated(job2)
        generationJobResultHandler.markCompleted(job2, "{}", report)
        awaitFindings(job2.id!!, expectedCount = 1)

        assertEquals(1, validationFindingRepository.findByGenerationJobId(job1.id!!).size)
        assertEquals(1, validationFindingRepository.findByGenerationJobId(job2.id!!).size)
        assertEquals(2, validationFindingRepository.findByInvoiceId(invoice.id!!).size)
    }

    /**
     * fix-einvoice-status-stale-after-completion regression — the regression test for the
     * race `fireAsyncAfterCommit` closes: through the real CDI/Narayana stack,
     * [DocumentGeneratedObservationRecorder]'s own `@ObservesAsync DocumentGenerated` listener must
     * only ever observe the `generation_job` row already COMPLETED, never the pre-commit DELEGATED
     * value `markCompleted`'s transaction held at the moment it (synchronously) fired the event
     * before this fix.
     */
    @Test
    fun `markCompleted's DocumentGenerated observer sees the job already COMPLETED, never the pre-commit status`() {
        val invoice = persistInvoice("ITGJS-2026-0007")
        val job = generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        generationJobPublishingService.markDelegated(job)

        documentGeneratedObservationRecorder.arm(job.id!!)
        generationJobResultHandler.markCompleted(job, "{\"pdf\":\"obj\"}")

        assertEquals(true, documentGeneratedObservationRecorder.await(job.id!!), "DocumentGenerated observer did not run within the timeout")
        assertEquals(
            de.littletrotter.domain.invoice.GenerationJobStatus.COMPLETED,
            documentGeneratedObservationRecorder.statusObservedFor(job.id!!),
        )
    }

    /**
     * L24 (fix-l24-draft-job-status-never-pushed) — the real-infrastructure counterpart of
     * `GenerationJobServiceTest`'s mocked "issued=false for a draft" unit test: against a REAL
     * Postgres transaction and a REAL CDI `@ObservesAsync` async dispatch (this recorder stands in
     * for [GenerationJobUiPushHandler], which observes the exact same event the exact same way),
     * a DRAFT invoice's job completion must still reach the observer. Before this fix,
     * `markCompleted`'s emission guard (`invoice.status.isIssued || trigger == SEND`) made this
     * structurally impossible for a DRAFT invoice — [DocumentGeneratedObservationRecorder.await]
     * would time out, never having anything to observe at all.
     */
    @Test
    fun `L24 markCompleted fires DocumentGenerated for a DRAFT invoice's job too, with issued=false`() {
        val invoice = persistInvoice("ITGJS-2026-0024", status = InvoiceStatus.DRAFT)
        val job = generationJobCreationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        generationJobPublishingService.markDelegated(job)

        documentGeneratedObservationRecorder.arm(job.id!!)
        generationJobResultHandler.markCompleted(job, "{\"pdf\":\"obj\"}")

        assertEquals(true, documentGeneratedObservationRecorder.await(job.id!!), "L24 — a draft's completed job must still fire DocumentGenerated for the UI push observer")
        assertEquals(de.littletrotter.domain.invoice.GenerationJobStatus.COMPLETED, documentGeneratedObservationRecorder.statusObservedFor(job.id!!))
        assertEquals(false, documentGeneratedObservationRecorder.issuedObservedFor(job.id!!), "a plain draft print job is not the issued document")
    }

    @Test
    fun `markFailed with a plain message commits against the jsonb result_refs column`() {
        // Regression: result_refs is jsonb, and markFailed used to write the bare message string
        // into it — Postgres rejected the flush ("Token ... is invalid") and the rollback undid
        // the FAILED transition. Only a real Postgres exercises this; H2/mocks accept anything.
        val invoice = persistInvoice("ITGJS-2026-0003")
        val job = generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        val failed = generationJobRecoveryService.markFailed(job, message = "external worker reported failure")

        val reloaded = QuarkusTransaction.requiringNew().call<de.littletrotter.domain.invoice.GenerationJob> {
            em.find(de.littletrotter.domain.invoice.GenerationJob::class.java, failed.id)
        }
        assertEquals(de.littletrotter.domain.invoice.GenerationJobStatus.FAILED, reloaded.status)
        // jsonb normalizes whitespace on storage — parse instead of comparing the raw string.
        val parsed = com.fasterxml.jackson.databind.ObjectMapper().readTree(reloaded.resultRefs)
        assertEquals("external worker reported failure", parsed.get("error")?.asText())
    }

    // ── Real-Postgres reproduction of the 2026-09-17 live incident's safety net: the
    // REAL stale-DELEGATED reset (StaleDelegatedResetCoordinator, not a hand-set field) resets a
    // job back to PENDING, then a late-but-genuine worker result must still be recorded as a real
    // completion, never discarded as an illegal transition. ──────────────────────────────────

    @Test
    fun `ARC91-06 a real stale-DELEGATED reset followed by a late genuine COMPLETED result still commits COMPLETED with its resultRefs`() {
        val invoice = persistInvoice("ITGJS-2026-0091")
        val job = generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        generationJobPublishingService.markDelegated(job)

        // The real production reset, not a hand-set field: staleCutoffMinutes = 0 so the job
        // (delegatedAt already a few milliseconds in the past by the time this runs, startedAt
        // still null) is picked up immediately — reproducing StaleDelegatedResetCoordinator
        // firing while the original worker attempt is still genuinely in flight, exactly like the
        // incident this task fixes. markDelegated never records a delegatedStreamSeq, so this is
        // a MISSING_SEQ candidate — reset outright, no NATS round trip needed.
        val resetCount = staleDelegatedResetCoordinator.resetStale(0)
        assertTrue(resetCount >= 1, "the real reset must have picked up at least this job")

        val afterReset = QuarkusTransaction.requiringNew().call<de.littletrotter.domain.invoice.GenerationJob> {
            em.find(de.littletrotter.domain.invoice.GenerationJob::class.java, job.id)
        }
        assertEquals(
            de.littletrotter.domain.invoice.GenerationJobStatus.PENDING, afterReset.status,
            "the reset must have genuinely happened in Postgres before the late result below",
        )

        // The original worker's late-but-genuine result, arriving after the app-side reset already
        // moved the job back to PENDING — job is passed by id/detached, same as the real
        // GenerationJobResultConsumer's own reloadManaged path.
        val completed = generationJobResultHandler.markCompleted(
            afterReset, resultRefs = """{"objectKey":"invoice/${job.id}.pdf","downloadUrl":"http://rustfs:9000/documents/invoice/${job.id}.pdf"}""",
        )

        assertEquals(de.littletrotter.domain.invoice.GenerationJobStatus.COMPLETED, completed.status)

        val reloaded = QuarkusTransaction.requiringNew().call<de.littletrotter.domain.invoice.GenerationJob> {
            em.find(de.littletrotter.domain.invoice.GenerationJob::class.java, job.id)
        }
        assertEquals(de.littletrotter.domain.invoice.GenerationJobStatus.COMPLETED, reloaded.status)
        assertNotNull(reloaded.resultRefs, "the real objectKey must be committed, never discarded to the DLQ")
        assertTrue(reloaded.resultRefs!!.contains("invoice/${job.id}.pdf"))
    }

    @Test
    fun `ARC91-06 a real stale-DELEGATED reset followed by a late genuine FAILED result still commits FAILED`() {
        val invoice = persistInvoice("ITGJS-2026-0092")
        val job = generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        generationJobPublishingService.markDelegated(job)

        val resetCount = staleDelegatedResetCoordinator.resetStale(0)
        assertTrue(resetCount >= 1, "the real reset must have picked up at least this job")

        val afterReset = QuarkusTransaction.requiringNew().call<de.littletrotter.domain.invoice.GenerationJob> {
            em.find(de.littletrotter.domain.invoice.GenerationJob::class.java, job.id)
        }

        val failed = generationJobRecoveryService.markFailed(afterReset, message = "worker crashed after the app had already reset this job")

        assertEquals(de.littletrotter.domain.invoice.GenerationJobStatus.FAILED, failed.status)
        val reloaded = QuarkusTransaction.requiringNew().call<de.littletrotter.domain.invoice.GenerationJob> {
            em.find(de.littletrotter.domain.invoice.GenerationJob::class.java, job.id)
        }
        assertEquals(de.littletrotter.domain.invoice.GenerationJobStatus.FAILED, reloaded.status)
    }

    // ── The real stale-DELEGATED reset always bumps attempts and clears
    //    delegatedAt/delegatedStreamSeq, unconditionally (the former worker-liveness gate is gone —
    //    see StaleDelegatedResetCoordinator's own KDoc for why an undelivered message is never
    //    even a reset candidate in the first place, which is what makes the unconditional
    //    increment safe). ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a real stale-DELEGATED reset bumps attempts and clears delegatedAt-delegatedStreamSeq in real Postgres`() {
        val invoice = persistInvoice("ITGJS-2026-0101")
        val job = generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        generationJobPublishingService.markDelegated(job)

        staleDelegatedResetCoordinator.resetStale(0)

        val reloaded = QuarkusTransaction.requiringNew().call<de.littletrotter.domain.invoice.GenerationJob> {
            em.find(de.littletrotter.domain.invoice.GenerationJob::class.java, job.id)
        }
        assertEquals(de.littletrotter.domain.invoice.GenerationJobStatus.PENDING, reloaded.status)
        assertEquals(1, reloaded.attempts, "a reset that fires at all always bumps attempts -- it always means the message is gone")
        assertNull(reloaded.delegatedAt, "delegatedAt must be cleared on the DELEGATED->PENDING reset")
        assertNull(reloaded.delegatedStreamSeq, "delegatedStreamSeq must be cleared on the DELEGATED->PENDING reset")
    }

    @Test
    fun `ST-A3 reopenExhaustedJobsWithoutDocument reopens a budget-exhausted job whose invoice has no document`() {
        val invoice = persistInvoice("ITGJS-2026-0102")
        // persistPendingJob, not createJob -- see that helper's own KDoc for the real-CDI
        // fast-path race this call to failExhaustedJob right below would otherwise risk.
        val job = persistPendingJob(invoice)
        generationJobRecoveryService.failExhaustedJob(job, maxAttempts = 5)

        val reopened = generationJobPublishingService.reopenExhaustedJobsWithoutDocument()

        assertTrue(reopened >= 1, "the real NOT-EXISTS/LIKE query must have found this job")
        val reloaded = QuarkusTransaction.requiringNew().call<de.littletrotter.domain.invoice.GenerationJob> {
            em.find(de.littletrotter.domain.invoice.GenerationJob::class.java, job.id)
        }
        assertEquals(de.littletrotter.domain.invoice.GenerationJobStatus.PENDING, reloaded.status, "ST-A3's one FAILED->PENDING edge must have fired")
        assertEquals(0, reloaded.attempts, "a reopened job gets a full fresh attempt budget")
    }

    @Test
    fun `ST-A3 reopenExhaustedJobsWithoutDocument never reopens a job whose invoice already has a completed document`() {
        // DRAFT, not the class default SENT: two jobs against the SAME issued invoice would hit
        // The single-generation gate (hasCompletedIssuedJob) the moment the first completes,
        // and this test needs a second, later job for the same invoice to still be createable.
        val invoice = persistInvoice("ITGJS-2026-0103", status = InvoiceStatus.DRAFT)
        val completedJob = generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        generationJobPublishingService.markDelegated(completedJob)
        generationJobResultHandler.markCompleted(completedJob, resultRefs = """{"objectKey":"invoice/${completedJob.id}.pdf"}""")

        // persistPendingJob, not createJob -- see that helper's own KDoc for the real-CDI
        // fast-path race this call to failExhaustedJob right below would otherwise risk.
        val exhaustedJob = persistPendingJob(invoice)
        generationJobRecoveryService.failExhaustedJob(exhaustedJob, maxAttempts = 5)

        generationJobPublishingService.reopenExhaustedJobsWithoutDocument()

        val reloaded = QuarkusTransaction.requiringNew().call<de.littletrotter.domain.invoice.GenerationJob> {
            em.find(de.littletrotter.domain.invoice.GenerationJob::class.java, exhaustedJob.id)
        }
        assertEquals(
            de.littletrotter.domain.invoice.GenerationJobStatus.FAILED, reloaded.status,
            "the invoice already has a real, completed document -- reopening this job would risk overwriting it",
        )
    }

    @Test
    fun `ST-A3 reopenExhaustedJobsWithoutDocument never reopens a FAILED job that was not budget-exhausted`() {
        val invoice = persistInvoice("ITGJS-2026-0104")
        val job = generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        generationJobRecoveryService.markFailed(job, message = "external worker reported a genuine rejection")

        generationJobPublishingService.reopenExhaustedJobsWithoutDocument()

        val reloaded = QuarkusTransaction.requiringNew().call<de.littletrotter.domain.invoice.GenerationJob> {
            em.find(de.littletrotter.domain.invoice.GenerationJob::class.java, job.id)
        }
        assertEquals(
            de.littletrotter.domain.invoice.GenerationJobStatus.FAILED, reloaded.status,
            "only the exact attempt-budget-exhaustion marker may be reopened, never a generic FAILED reason",
        )
    }
}
