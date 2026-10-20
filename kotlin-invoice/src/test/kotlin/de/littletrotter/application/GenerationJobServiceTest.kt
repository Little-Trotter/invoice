package de.littletrotter.application

import de.littletrotter.application.shared.DomainEventDispatcher
import de.littletrotter.domain.invoice.recordLlmAnalysis
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.InvoiceTotals
import de.littletrotter.domain.message.SystemMessageContent
import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.invoice.forceStatusForTest

import de.littletrotter.application.invoice.B2bProfileRuleSet
import de.littletrotter.application.invoice.LlmPendingRecoveryBatchTransaction
import de.littletrotter.application.invoice.NoOpInvoiceRepository
import de.littletrotter.domain.exception.InvoiceImmutableGenerationBlockedException
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.exception.InvoiceVoidedGenerationBlockedException
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.domain.event.DocumentGenerated
import de.littletrotter.domain.event.GenerationJobCreated
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.fixture.PersistenceEmulation
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import de.littletrotter.domain.shared.ValidationStatus
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import jakarta.enterprise.event.Event
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TIME_SOURCE

class GenerationJobServiceTest {

    // ── in-memory stubs ────────────────────────────────────────────────────────

    private class InMemoryGenerationJobRepository : GenerationJobRepository {
        private val jobs = mutableListOf<GenerationJob>()
        private var nextId = 1L

        override fun findByInvoiceId(invoiceId: Long): List<GenerationJob> = jobs.filter { it.invoice?.id == invoiceId }
        override fun findByInvoiceIdWithTaxRates(invoiceId: Long): List<GenerationJob> = findByInvoiceId(invoiceId)
        override fun findPending(): List<GenerationJob> = jobs.filter { it.status == GenerationJobStatus.PENDING }
        override fun findPendingWithInvoice(): List<GenerationJob> = findPending()
        override fun findById(id: Long): GenerationJob? = jobs.find { it.id == id }
        override fun tryMarkDelegated(id: Long): Boolean = true

        // A real (in-memory) implementation, not the interface's own
        // "nothing to do" default, so resetStaleLlmPending's own unit tests can exercise it.
        override fun findCompletedBetween(from: java.time.OffsetDateTime, to: java.time.OffsetDateTime): List<GenerationJob> =
            jobs.filter { it.status == GenerationJobStatus.COMPLETED && it.finishedAt != null && it.finishedAt!! >= from && it.finishedAt!! < to }

        override fun save(job: GenerationJob): GenerationJob {
            if (job.id == null) {
                job.edit { this.id = nextId++ }
                jobs.add(job)
            }
            return job
        }
    }

    private class InMemoryInvoiceRepository(vararg invoices: Invoice) : InvoiceRepository, de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository, de.littletrotter.application.invoice.port.outbound.InvoiceListingRepository {
        private val store = invoices.associateBy { it.id!! }.toMutableMap()

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
        override fun save(invoice: Invoice): Invoice = invoice.also { store[it.id!!] = it }
        override fun forceVersionIncrement(invoice: Invoice) { invoice.edit { version = (invoice.version ?: 0L) + 1L } }
    }

    // The former InMemoryValidationFindingRepository fixture moved to
    // DocumentGeneratedReactionHandlerTest, the class that now actually writes findings.

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun validInvoice(id: Long = 123L): Invoice {
        val sender = aSenderProfile {
            name = "Muster GmbH"
            email = de.littletrotter.domain.value.Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = de.littletrotter.domain.value.Iban("DE89370400440532013000")
        }
        val recipient = aRecipient {
            customerNumber = CustomerNumber("KD-001")
            name = "Kunde AG"
            email = de.littletrotter.domain.value.Email("kunde@kunde-ag.example")
            vatId = VatId("DE987654321")
            iban = de.littletrotter.domain.value.Iban("DE02120300000000202051")
        }
        val item = anInvoiceItem(
            positionId = "1",
            description = "Beratung",
            quantity = BigDecimal("1.00"),
            unitPriceNet = Money(BigDecimal("100.00")),
            netAmount = Money(BigDecimal("100.00")),
            vatAmount = Money(BigDecimal("19.00")),
            grossAmount = Money(BigDecimal("119.00")),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19.00")) },
        )
        return anInvoice(
            id = id,
            invoiceNumber = InvoiceNumber("RE-2025-01-${id.toString().padStart(5, '0')}"),
            invoiceDate = LocalDate.now(),
            status = InvoiceStatus.DRAFT,
            senderProfile = sender,
            recipient = recipient,
            items = listOf(item),
            totals = InvoiceTotals(netAmount = Money(BigDecimal("100.00")), vatAmount = Money(BigDecimal("19.00")), grossAmount = Money(BigDecimal("119.00"))),
        )
    }

    /**
     * `GenerationJobService` (a pure delegation facade) dissolved from production code;
     * this bundle is the test-only equivalent: the same six real collaborators the facade used to
     * build internally from these exact constructor arguments (see its own former KDoc), grouped
     * here purely so this file's ~150 pre-existing test bodies keep calling `service.creationService.createJob(...)`
     * -shaped names — `service.creationService.createJob(...)` etc. below — without every one of them
     * having to inject/construct all six collaborators individually. Each field is the real,
     * directly-testable `@ApplicationScoped` bean, never re-implemented here.
     */
    private class GenerationJobCollaborators(
        generationJobRepository: GenerationJobRepository,
        domainEventBus: Event<DomainEvent>,
        invoiceRepository: InvoiceRepository = NoOpInvoiceRepository,
        b2bProfileRuleSet: B2bProfileRuleSet? = null,
        transactionSynchronizationRegistry: jakarta.transaction.TransactionSynchronizationRegistry? = null,
        sendTransitionRunner: de.littletrotter.application.invoice.SendTransitionRunner? = null,
        messageCenterService: de.littletrotter.application.message.MessageCenterService? = null,
        // The document-size budget guard added to createJob: none of this file's ~150 pre-existing
        // test bodies care about it, so the default is a mock that reports an empty (Accepted)
        // budget for every invoice id — a real ReceiptBudgetService would need a ReceiptRepository
        // this bundle has no other use for.
        receiptBudgetService: de.littletrotter.application.invoice.ReceiptBudgetService =
            mock<de.littletrotter.application.invoice.ReceiptBudgetService>().also {
                whenever(it.invoiceDocumentBudget(any())).thenReturn(de.littletrotter.shared.receipt.InvoiceDocumentBudget.calculate(emptyList()))
            },
    ) {
        // The real system clock, not a fixed instant: several tests in this suite seed fixture
        // data with OffsetDateTime.now() (e.g. resetStaleLlmPending's stale-window tests) and
        // expect this collaborator bundle's own "now" to land in the same neighborhood.
        private val time = de.littletrotter.shared.time.ClockTimeSource(java.time.Clock.systemUTC())
        private val eventDispatcher = de.littletrotter.application.shared.DomainEventDispatcher(domainEventBus, transactionSynchronizationRegistry)
        val queryService = de.littletrotter.application.invoice.GenerationJobQueryService(generationJobRepository)
        val creationService = de.littletrotter.application.invoice.GenerationJobCreationService(generationJobRepository, invoiceRepository, domainEventBus, time, receiptBudgetService)
        val publishingService = de.littletrotter.application.invoice.GenerationJobPublishingService(generationJobRepository, time)
        val llmResultHandler = de.littletrotter.application.invoice.GenerationJobLlmResultHandler(generationJobRepository, invoiceRepository, eventDispatcher, time)
        val resultHandler = de.littletrotter.application.invoice.GenerationJobResultHandler(generationJobRepository, eventDispatcher, b2bProfileRuleSet, sendTransitionRunner, messageCenterService, time = FIXED_TIME_SOURCE)
        val recoveryService = de.littletrotter.application.invoice.GenerationJobRecoveryService(generationJobRepository, invoiceRepository, eventDispatcher, llmResultHandler, time)
    }

    private fun makeService(vararg invoices: Invoice): Pair<GenerationJobCollaborators, InMemoryGenerationJobRepository> {
        val jobRepo = InMemoryGenerationJobRepository()
        val svc = GenerationJobCollaborators(jobRepo, mock(), InMemoryInvoiceRepository(*invoices))
        return svc to jobRepo
    }

    /** Seeds and reads the analyses of the given in-memory invoices so tests can assert the
     *  LLM status chain gets closed (COMPLETED/FAILED/DISABLED, never left PENDING forever). */
    private class InMemoryLlmAnalysisRepository(vararg invoices: Invoice) {
        private val byId = invoices.associateBy { it.id!! }

        fun findByInvoiceId(invoiceId: Long): List<de.littletrotter.domain.invoice.InvoiceLlmAnalysis> =
            byId[invoiceId]?.llmAnalysisList.orEmpty()

        fun findByInvoiceIdAndModelName(invoiceId: Long, modelName: String): de.littletrotter.domain.invoice.InvoiceLlmAnalysis? =
            findByInvoiceId(invoiceId).firstOrNull { it.modelName == modelName }

        fun pendingRecord(invoiceId: Long, model: String = "oMLX"): de.littletrotter.domain.invoice.InvoiceLlmAnalysis =
            checkNotNull(byId[invoiceId]) { "invoice $invoiceId is not part of this fixture" }.recordLlmAnalysis(model)
    }

    private fun makeServiceWithLlm(
        vararg invoices: Invoice,
    ): Triple<GenerationJobCollaborators, InMemoryGenerationJobRepository, InMemoryLlmAnalysisRepository> {
        val jobRepo = InMemoryGenerationJobRepository()
        val llmRepo = InMemoryLlmAnalysisRepository(*invoices)
        val svc = GenerationJobCollaborators(jobRepo, mock(), InMemoryInvoiceRepository(*invoices))
        return Triple(svc, jobRepo, llmRepo)
    }

    // fix-einvoice-status-stale-after-completion — a fake TransactionSynchronizationRegistry that
    // lets these unit tests simulate an active/inactive JTA transaction without a real container:
    // reports whatever [status] is set to, and captures the single
    // [jakarta.transaction.Synchronization] publishAfterCommit registers so a test can invoke its
    // afterCompletion the way a real transaction manager would, at the point commit/rollback
    // actually happens. Constructor parameter is named `status`, not `transactionStatus` — naming
    // it after the interface's own `getTransactionStatus()` would generate a Kotlin property
    // getter with the exact same JVM signature as the override below (a platform declaration
    // clash), since Kotlin auto-derives `getTransactionStatus()` from a property named
    // `transactionStatus`.
    private class FakeTransactionSynchronizationRegistry(
        var status: Int = jakarta.transaction.Status.STATUS_ACTIVE,
    ) : jakarta.transaction.TransactionSynchronizationRegistry {
        var registered: jakarta.transaction.Synchronization? = null

        override fun getTransactionKey(): Any? = null
        override fun putResource(key: Any?, value: Any?) {}
        override fun getResource(key: Any?): Any? = null
        override fun registerInterposedSynchronization(sync: jakarta.transaction.Synchronization) {
            registered = sync
        }
        override fun getTransactionStatus(): Int = status
        override fun setRollbackOnly() {}
        override fun getRollbackOnly(): Boolean = false
    }

    // ── lifecycle tests ────────────────────────────────────────────────────────

    @Test
    fun `createJob creates and starts job correctly`() {
        val invoice = validInvoice(123L)
        val (service) = makeService(invoice)

        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        assertNotNull(job.id)
        assertEquals(GenerationJobStatus.PENDING, job.status)

        // markStarted (DELEGATED->RUNNING) requires the job to already be DELEGATED.
        service.publishingService.markDelegated(job)
        val startedJob = service.publishingService.markStarted(job)
        assertEquals(GenerationJobStatus.RUNNING, startedJob.status)

        val completedJob = service.resultHandler.markCompleted(startedJob, "{}").also {
            assertEquals(GenerationJobStatus.COMPLETED, it.status)
        }

        assertEquals(1, service.queryService.findByInvoiceId(invoice.id!!).size)
        assertEquals("{}", completedJob.resultRefs)
    }

    // findByInvoiceIdWithTaxRates/findPendingWithInvoice/findById are plain one-line
    // repository delegations with no test of their own anywhere in this file.
    @Test
    fun `happy path - findByInvoiceIdWithTaxRates delegates to the repository`() {
        val invoice = validInvoice(140L)
        val (service) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        assertEquals(listOf(job), service.queryService.findByInvoiceIdWithTaxRates(invoice.id!!))
    }

    @Test
    fun `happy path - findPendingWithInvoice delegates to the repository`() {
        val invoice = validInvoice(141L)
        val (service) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        assertEquals(listOf(job), service.queryService.findPendingWithInvoice())
    }

    @Test
    fun `happy path - findById delegates to the repository`() {
        val invoice = validInvoice(142L)
        val (service) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        assertEquals(job, service.queryService.findById(job.id!!))
    }

    @Test
    fun `id-based createJob reloads the invoice and delegates to the entity overload`() {
        val invoice = validInvoice(123L)
        val (service) = makeService(invoice)

        val job = service.creationService.createJob(123L, "PDF_EXPORT", DocumentJobLane.BULK)

        assertNotNull(job.id)
        assertEquals(GenerationJobStatus.PENDING, job.status)
        assertEquals(123L, job.invoice?.id)
    }

    @Test
    fun `id-based createJob throws InvoiceNotFoundException for an unknown id`() {
        val (service) = makeService(validInvoice(123L))

        assertThrows<InvoiceNotFoundException> { service.creationService.createJob(999L, "PDF_EXPORT", DocumentJobLane.BULK) }
    }

    @Test
    fun `createJob rejects a VOIDED invoice with a readable German message, no job persisted`() {
        val invoice = validInvoice(123L).apply { forceStatusForTest(InvoiceStatus.VOIDED) }
        val (service, jobRepo) = makeService(invoice)

        val ex = assertThrows<de.littletrotter.domain.exception.InvoiceVoidedGenerationBlockedException> {
            service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        }
        assertTrue(ex.message!!.contains(invoice.invoiceNumber.value))
        assertTrue(jobRepo.findByInvoiceId(123L).isEmpty())
    }

    @Test
    fun `id-based createJob also rejects a VOIDED invoice`() {
        val invoice = validInvoice(123L).apply { forceStatusForTest(InvoiceStatus.VOIDED) }
        val (service) = makeService(invoice)

        assertThrows<de.littletrotter.domain.exception.InvoiceVoidedGenerationBlockedException> {
            service.creationService.createJob(123L, "PDF_EXPORT", DocumentJobLane.BULK)
        }
    }

    // ST-8 — the rule table's whole point: CANCELLED (Cancellation) is not VOIDED. A cancelled
    // invoice really was sent, so a new e-invoice job must still be placeable for it -- for both
    // the cancelled original and the credit note that replaced it -- while a voided one still
    // rejects above. Neither document goes through recalculateTotals() (isReadOnly() is true for
    // both), matching the "cannot be edited" half of the rule table.

    @Test
    fun `createJob still places a job for a CANCELLED original -- only VOIDED is blocked`() {
        val invoice = validInvoice(123L).apply { forceStatusForTest(InvoiceStatus.CANCELLED) }
        val (service, jobRepo) = makeService(invoice)

        // A throw here would fail the test on its own; no assertDoesNotThrow wrapper needed --
        // and its Executable overload would erase createJob's return type to Unit anyway.
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        assertNotNull(job.id)
        assertEquals(1, jobRepo.findByInvoiceId(123L).size)
    }

    @Test
    fun `createJob also places a job for the CANCELLED cancellation document`() {
        val creditNote = validInvoice(124L).edit {
            forceStatusForTest(InvoiceStatus.CANCELLED)
            typeCode = de.littletrotter.domain.invoice.InvoiceTypeCode.CORRECTED_INVOICE
            originalInvoiceNumber = "RE-2025-01-00123"
        }
        val (service, jobRepo) = makeService(creditNote)

        val job = service.creationService.createJob(creditNote, "PDF_EXPORT", DocumentJobLane.BULK)

        assertNotNull(job.id)
        assertEquals(1, jobRepo.findByInvoiceId(124L).size)
    }

    @Test
    fun `findPending returns pending jobs only`() {
        val invoice = validInvoice(123L)
        val (service) = makeService(invoice)

        service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        service.publishingService.markStarted(job)

        assertEquals(1, service.queryService.findPending().size)
    }

    @Test
    fun `markCompleted emits DocumentGenerated for assigned invoice number`() {
        // N5 — DocumentGenerated now gates on invoice.status.isIssued, not invoiceNumber
        // .isAssigned() (every invoice is numbered from birth since N5); ISSUED is what makes this
        // a genuinely finalized document.
        val invoice = validInvoice(124L).also { it.forceStatusForTest(InvoiceStatus.ISSUED) }
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))

        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        service.resultHandler.markCompleted(job, "{\"pdf\":\"obj\"}")

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fireAsync(eventCaptor.capture())
        val emitted = eventCaptor.firstValue
        assertTrue(emitted is DocumentGenerated)
        emitted as DocumentGenerated
        assertEquals(invoice.id, emitted.invoiceId)
        assertEquals(invoice.invoiceNumber.value, emitted.invoiceNumber)
        assertEquals(job.id, emitted.generationJobId)
        assertTrue(emitted.issued, "an ISSUED invoice's job is the issued document")
    }

    // ── L24 (fix-l24-draft-job-status-never-pushed) — DocumentGenerated/GenerationJobFailed
    // now fire for EVERY completed/failed job with a resolvable invoice, draft included: a draft's
    // job finishing/failing is a real event GenerationJobUiPushHandler must still push to the UI.
    // `issued` carries what used to be the emission guard itself, for the OTHER consumers
    // (DocumentGeneratedReactionHandler, MessageCenterEventHandler) that still only act on a
    // genuinely issued/SEND document — see DocumentGeneratedReactionHandlerTest/
    // MessageCenterEventHandlerTest for their own "a draft event is a no-op" coverage. ──────────

    @Test
    fun `L24 markCompleted emits DocumentGenerated with issued=false for a draft invoice's job`() {
        val invoice = validInvoice(1240L) // DRAFT by default (validInvoice's own fixture)
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))
        val job = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)

        service.resultHandler.markCompleted(job, "{\"pdf\":\"obj\"}")

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fireAsync(eventCaptor.capture())
        val emitted = eventCaptor.firstValue
        assertTrue(emitted is DocumentGenerated, "L24 — a draft's completed job must still fire DocumentGenerated for the UI push")
        emitted as DocumentGenerated
        assertEquals(invoice.id, emitted.invoiceId)
        assertEquals(job.id, emitted.generationJobId)
        assertFalse(emitted.issued, "a plain draft print job is not the issued document")
    }

    @Test
    fun `L24 markCompleted emits DocumentGenerated with issued=true for a SEND-triggered job while the invoice is still IN_REVIEW`() {
        val invoice = validInvoice(1241L).also { it.forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))
        val job = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)
        service.publishingService.markDelegated(job)

        service.resultHandler.markCompleted(job, "{\"pdf\":\"obj\"}")

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fireAsync(eventCaptor.capture())
        val emitted = eventCaptor.firstValue as DocumentGenerated
        assertTrue(emitted.issued, "PO decision 2026-09-13 — a SEND-triggered job counts as issued even while IN_REVIEW")
    }

    // ── A late-but-genuine worker result for a job GenerationJobProcessor
    // .resetStaleDelegatedJobs already reset back to PENDING out from under a still-in-flight
    // worker attempt must be recorded as a real completion, not discarded as an illegal
    // PENDING -> COMPLETED/FAILED transition. `job.status = PENDING` below simulates that reset
    // directly, the same way the real bulk-JPQL `resetStaleDelegated` update bypasses
    // GenerationJob.transitionTo entirely (see PanacheGenerationJobRepository.resetStaleDelegated's
    // own KDoc). ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `ARC91-06 markCompleted records a late genuine result for a job the stale-reset already put back to PENDING`() {
        val invoice = validInvoice(1250L).also { it.forceStatusForTest(InvoiceStatus.ISSUED) }
        val (service) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        // Simulates GenerationJobProcessor.resetStaleDelegatedJobs firing while the original
        // worker attempt was still genuinely in flight (the 2026-09-17 incident).
        job.edit { this.status = GenerationJobStatus.PENDING }

        // A direct call, not wrapped in assertDoesNotThrow: an uncaught exception here already
        // fails the test, and asserting on the returned job's status also proves the reclaim
        // bridge actually landed COMPLETED, not merely that nothing threw.
        val completed = service.resultHandler.markCompleted(job, "{\"pdf\":\"obj\",\"objectKey\":\"invoice/1250.pdf\"}")

        assertEquals(GenerationJobStatus.COMPLETED, completed.status)
    }

    @Test
    fun `ARC91-06 markFailed records a late genuine failure for a job the stale-reset already put back to PENDING`() {
        val invoice = validInvoice(1251L)
        val (service) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        job.edit { this.status = GenerationJobStatus.PENDING }

        val failed = service.recoveryService.markFailed(job, message = "worker crashed after the app had already reset this job")

        assertEquals(GenerationJobStatus.FAILED, failed.status)
    }

    // ── mergeLlmResult merges the deferred stage's SECOND callback into the
    // job row without touching anything the FIRST callback (markCompleted) already established. ─

    @Test
    fun `failExhaustedJob is a no-op false for an unsaved job without id`() {
        val (service) = makeService()
        assertFalse(service.recoveryService.failExhaustedJob(GenerationJob(), 3))
    }

    @Test
    fun `failExhaustedJob on a PENDING job without invoice fails it and fires no event`() {
        val (service, jobRepo) = makeService()
        val job = aGenerationJob { jobType = "PDF_EXPORT" }
        jobRepo.save(job)

        assertTrue(service.recoveryService.failExhaustedJob(job, 3))
        assertEquals(GenerationJobStatus.FAILED, jobRepo.findById(job.id!!)?.status)
    }

    // ── L3 (fix-generation-job-failure-visibility.md) — markResultProcessingFailed is the DLQ
    // "give up" transition GenerationJobResultConsumer calls once its own 3-attempt retry loop
    // could not durably process a worker result; before this it left the job DELEGATED/RUNNING
    // forever with only a diagnostic note on lastError, no terminal user-visible failure state. ──

    @Test
    fun `markResultProcessingFailed transitions a DELEGATED job to FAILED and records the reason`() {
        val invoice = validInvoice(900L)
        val (service, jobRepo) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)

        val ok = service.recoveryService.markResultProcessingFailed(job.id!!, "processing failed after 3 attempts: db timeout")

        assertTrue(ok)
        val updated = jobRepo.findById(job.id!!)
        assertEquals(GenerationJobStatus.FAILED, updated?.status)
        assertEquals("processing failed after 3 attempts: db timeout", updated?.lastError)
        assertNotNull(updated?.finishedAt)
    }

    @Test
    fun `markResultProcessingFailed also transitions a RUNNING job to FAILED`() {
        val invoice = validInvoice(901L)
        val (service, jobRepo) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        service.publishingService.markStarted(job)

        val ok = service.recoveryService.markResultProcessingFailed(job.id!!, "processing failed after 3 attempts: db timeout")

        assertTrue(ok)
        assertEquals(GenerationJobStatus.FAILED, jobRepo.findById(job.id!!)?.status)
    }

    @Test
    fun `markResultProcessingFailed is a no-op false for a job id that does not resolve`() {
        val (service) = makeService()
        assertFalse(service.recoveryService.markResultProcessingFailed(999_999L, "processing failed after 3 attempts: not found"))
    }

    @Test
    fun `markResultProcessingFailed is a no-op false for an already-terminal job and leaves it untouched`() {
        val invoice = validInvoice(902L)
        val (service, jobRepo) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        service.resultHandler.markCompleted(job, resultRefs = "{}")

        val ok = service.recoveryService.markResultProcessingFailed(job.id!!, "processing failed after 3 attempts: redelivered")

        assertFalse(ok, "an already-COMPLETED job must never be resurrected into FAILED by a redelivered/late DLQ give-up")
        assertEquals(GenerationJobStatus.COMPLETED, jobRepo.findById(job.id!!)?.status)
    }

    @Test
    fun `markResultProcessingFailed clears a PENDING invoice validation status to UNAVAILABLE`() {
        val invoice = validInvoice(903L).edit { validationStatus = ValidationStatus.PENDING }
        val (service) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)

        service.recoveryService.markResultProcessingFailed(job.id!!, "processing failed after 3 attempts: stale version")

        assertEquals(ValidationStatus.UNAVAILABLE, invoice.validationStatus)
    }

    @Test
    fun `markResultProcessingFailed leaves an already VALID invoice untouched`() {
        val invoice = validInvoice(904L).edit { validationStatus = ValidationStatus.VALID }
        val (service) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)

        service.recoveryService.markResultProcessingFailed(job.id!!, "processing failed after 3 attempts: stale version")

        assertEquals(ValidationStatus.VALID, invoice.validationStatus)
    }

    @Test
    fun `markResultProcessingFailed emits GenerationJobResultProcessingFailed for assigned invoice number`() {
        // N5 — gates on invoice.status.isIssued now, see the markCompleted test above.
        val invoice = validInvoice(905L).also { it.forceStatusForTest(InvoiceStatus.ISSUED) }
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)

        service.recoveryService.markResultProcessingFailed(job.id!!, "processing failed after 3 attempts: db timeout")

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fireAsync(eventCaptor.capture())
        val emitted = eventCaptor.firstValue
        assertTrue(emitted is de.littletrotter.domain.event.GenerationJobResultProcessingFailed)
        emitted as de.littletrotter.domain.event.GenerationJobResultProcessingFailed
        assertEquals(invoice.id, emitted.invoiceId)
        assertEquals(invoice.invoiceNumber.value, emitted.invoiceNumber)
        assertEquals(job.id, emitted.generationJobId)
        assertEquals("processing failed after 3 attempts: db timeout", emitted.reason)
    }

    @Test
    fun `markResultProcessingFailed fires no event for a job without an invoice`() {
        val (service, jobRepo) = makeService()
        val job = aGenerationJob { jobType = "PDF_EXPORT" }
        jobRepo.save(job)
        service.publishingService.markDelegated(job)

        assertTrue(service.recoveryService.markResultProcessingFailed(job.id!!, "processing failed after 3 attempts: db timeout"))
        assertEquals(GenerationJobStatus.FAILED, jobRepo.findById(job.id!!)?.status)
    }

    @Test
    fun `markResultProcessingFailed still transitions the job but fires no event when the invoice number was never assigned`() {
        val invoice = validInvoice(908L).edit { invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber.UNASSIGNED }
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)

        val ok = service.recoveryService.markResultProcessingFailed(job.id!!, "processing failed after 3 attempts: db timeout")

        assertTrue(ok)
        assertEquals(GenerationJobStatus.FAILED, jobRepo.findById(job.id!!)?.status)
        verify(domainEventBus, never()).fireAsync(any())
    }

    @Test
    fun `markResultProcessingFailed returns true and fires no event if the job vanishes right after the write succeeded`() {
        // Defensive edge case: the guarded bulk UPDATE (failDlq) reports success, but a
        // subsequent re-fetch (to grab the invoice for the message-center event) no longer
        // resolves the row — the write itself must still count as a success.
        val vanishingRepo = object : GenerationJobRepository by InMemoryGenerationJobRepository() {
            override fun failDlq(id: Long, message: String, finishedAt: java.time.OffsetDateTime): Boolean = true
            override fun findById(id: Long): GenerationJob? = null
        }
        val domainEventBus: Event<DomainEvent> = mock()
        val service = GenerationJobCollaborators(vanishingRepo, domainEventBus)

        val ok = service.recoveryService.markResultProcessingFailed(1L, "processing failed after 3 attempts: db timeout")

        assertTrue(ok)
        verify(domainEventBus, never()).fireAsync(any())
    }

    @Test
    fun `markResultProcessingFailed swallows a repository exception and returns false instead of throwing`() {
        // L3 — deliberately never throws (mirrors recordLastError's own "best effort" contract):
        // this is the last line of defense before GenerationJobResultConsumer drops a poison
        // message, so a transient DB hiccup here must degrade gracefully, not crash the handler.
        val throwingRepo = object : GenerationJobRepository by InMemoryGenerationJobRepository() {
            override fun failDlq(id: Long, message: String, finishedAt: java.time.OffsetDateTime): Boolean = throw RuntimeException("simulated DB hiccup")
        }
        val service = GenerationJobCollaborators(throwingRepo, mock())

        assertDoesNotThrow {
            assertFalse(service.recoveryService.markResultProcessingFailed(1L, "processing failed after 3 attempts: db timeout"))
        }
    }

    @Test
    fun `markResultProcessingFailed resolves any still-PENDING LLM analysis for the invoice`() {
        val invoice = validInvoice(906L)
        val (service, jobRepo, llmRepo) = makeServiceWithLlm(invoice)
        llmRepo.pendingRecord(invoice.id!!, model = "Qwen-mini")
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)

        service.recoveryService.markResultProcessingFailed(job.id!!, "processing failed after 3 attempts: db timeout")

        assertEquals(
            LlmAnalysisStatus.FAILED,
            llmRepo.findByInvoiceIdAndModelName(invoice.id!!, "Qwen-mini")?.status,
            "a job that never durably completed must not leave an LLM analysis PENDING forever either",
        )
    }

    @Test
    fun `revertToPending is a no-op false for an unsaved job without id`() {
        val (service) = makeService(validInvoice(600L))
        assertFalse(service.publishingService.revertToPending(GenerationJob()))
    }

    // The success path (a saved DELEGATED job with an id) had no coverage: only the
    // null-id short-circuit above was ever tested.
    @Test
    fun `happy path - revertToPending flips a saved DELEGATED job back to PENDING`() {
        val invoice = validInvoice(601L)
        val (service, jobRepo) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)

        assertTrue(service.publishingService.revertToPending(job))

        assertEquals(GenerationJobStatus.PENDING, jobRepo.findById(job.id!!)!!.status)
    }

    // recordDelegatedStreamSeq had no coverage at all in this file.
    @Test
    fun `happy path - recordDelegatedStreamSeq stamps the stream sequence on a DELEGATED job`() {
        val invoice = validInvoice(602L)
        val (service, jobRepo) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)

        assertTrue(service.publishingService.recordDelegatedStreamSeq(job, 42L))

        assertEquals(42L, jobRepo.findById(job.id!!)!!.delegatedStreamSeq)
    }

    @Test
    fun `boundary - recordDelegatedStreamSeq is a no-op false for an unsaved job without id`() {
        val (service) = makeService(validInvoice(603L))
        assertFalse(service.publishingService.recordDelegatedStreamSeq(GenerationJob(), 1L))
    }

    @Test
    fun `mergeLlmResult starts from an empty map when the job has no resultRefs yet`() {
        val invoice = validInvoice(601L)
        val (service) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        val merged = service.llmResultHandler.mergeLlmResult(job, mapOf("llmStatus" to "COMPLETED"))

        val refs = com.fasterxml.jackson.databind.ObjectMapper().readValue(merged.resultRefs!!, Map::class.java)
        assertEquals("COMPLETED", refs["llmStatus"])
    }

    @Test
    fun `mergeLlmResult discards unparsable existing resultRefs instead of failing the merge`() {
        val invoice = validInvoice(602L)
        val (service) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        job.edit { resultRefs = "not-json" }

        val merged = service.llmResultHandler.mergeLlmResult(job, mapOf("llmStatus" to "FAILED"))

        val refs = com.fasterxml.jackson.databind.ObjectMapper().readValue(merged.resultRefs!!, Map::class.java)
        assertEquals(mapOf("llmStatus" to "FAILED"), refs)
    }

    @Test
    fun `markFailed on a job without invoice fails the job and touches no validation status`() {
        val (service, jobRepo) = makeService()
        val job = aGenerationJob { jobType = "PDF_EXPORT" }
        jobRepo.save(job)
        service.publishingService.markDelegated(job)
        service.publishingService.markStarted(job)

        val failed = service.recoveryService.markFailed(job, "boom")

        assertEquals(GenerationJobStatus.FAILED, failed.status)
    }

    @Test
    fun `mergeLlmResult overlays llm keys onto existing resultRefs without disturbing the rest`() {
        val invoice = validInvoice(507L)
        val (service, jobRepo) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        service.resultHandler.markCompleted(job, resultRefs = """{"objectKey":"invoice/1.pdf","llmStatus":"PENDING"}""")

        val merged = service.llmResultHandler.mergeLlmResult(
            job,
            mapOf("llmStatus" to "COMPLETED", "llmModel" to "qwen", "llmReportKey" to "invoice/1-llm-report.md", "llmDurationMs" to "4200"),
        )

        val refs = com.fasterxml.jackson.databind.ObjectMapper().readValue(merged.resultRefs!!, Map::class.java)
        assertEquals("invoice/1.pdf", refs["objectKey"], "the first callback's own keys must survive the merge")
        assertEquals("COMPLETED", refs["llmStatus"])
        assertEquals("qwen", refs["llmModel"])
        assertEquals("invoice/1-llm-report.md", refs["llmReportKey"])
        assertEquals(GenerationJobStatus.COMPLETED, merged.status, "mergeLlmResult must never change the job's own status")
        assertNotNull(jobRepo.findById(job.id!!))
    }

    // markCompleted's own follow-up reactions (Invoice.validationStatus, findings)
    // now run entirely off the DocumentGenerated event it fires, not inline; mergeLlmResult
    // (the SECOND, deferred callback) must never re-trigger them either — provable directly at
    // this level now: mergeLlmResult must fire no domain event of its own at all.
    @Test
    fun `mergeLlmResult never fires a domain event (the first callback's reactions are not re-triggered)`() {
        val invoice = validInvoice(508L)
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        service.resultHandler.markCompleted(job, resultRefs = """{"llmStatus":"PENDING"}""")
        org.mockito.kotlin.clearInvocations(domainEventBus)

        service.llmResultHandler.mergeLlmResult(job, mapOf("llmStatus" to "COMPLETED", "llmModel" to "qwen"))

        verify(domainEventBus, never()).fireAsync(any())
        verify(domainEventBus, never()).fire(any())
    }

    // ── validation-status-truthful-pending.md R3/R7 — markFailed clears a genuinely running
    // PENDING back to UNAVAILABLE; an invoice already resolved by a different/newer job is left
    // untouched. ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `markFailed clears a PENDING invoice to UNAVAILABLE`() {
        val invoice = validInvoice(500L).edit { validationStatus = ValidationStatus.PENDING }
        val (service) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        service.recoveryService.markFailed(job, message = "worker crashed")

        assertEquals(ValidationStatus.UNAVAILABLE, invoice.validationStatus)
    }

    @Test
    fun `markFailed leaves an already VALID invoice untouched`() {
        val invoice = validInvoice(501L).edit { validationStatus = ValidationStatus.VALID }
        val (service) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        service.recoveryService.markFailed(job, message = "worker crashed")

        assertEquals(ValidationStatus.VALID, invoice.validationStatus)
    }

    // ── markFailed/updateLlmAnalysisFromResult now also feed the message center's
    // curated observer via two new DomainEvents (see GenerationJobFailed/InvoiceLlmAnalysisResolved
    // KDoc for why they were added). ──────────────────────────────────────────────────────────

    @Test
    fun `markFailed emits GenerationJobFailed for assigned invoice number`() {
        // N5 — gates on invoice.status.isIssued now, see the markCompleted test above.
        val invoice = validInvoice(502L).also { it.forceStatusForTest(InvoiceStatus.ISSUED) }
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        service.recoveryService.markFailed(job, message = "worker crashed")

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fireAsync(eventCaptor.capture())
        val emitted = eventCaptor.firstValue
        assertTrue(emitted is de.littletrotter.domain.event.GenerationJobFailed)
        emitted as de.littletrotter.domain.event.GenerationJobFailed
        assertEquals(invoice.id, emitted.invoiceId)
        assertEquals(invoice.invoiceNumber.value, emitted.invoiceNumber)
        assertEquals(job.id, emitted.generationJobId)
        assertEquals("worker crashed", emitted.reason)
        assertTrue(emitted.issued, "an ISSUED invoice's failed job is the issued document")
    }

    @Test
    fun `L24 markFailed emits GenerationJobFailed with issued=false for a draft invoice's job`() {
        val invoice = validInvoice(1242L) // DRAFT by default
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))
        val job = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)

        service.recoveryService.markFailed(job, message = "worker crashed")

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fireAsync(eventCaptor.capture())
        val emitted = eventCaptor.firstValue
        assertTrue(emitted is de.littletrotter.domain.event.GenerationJobFailed, "L24 — a draft's failed job must still fire GenerationJobFailed for the UI push")
        emitted as de.littletrotter.domain.event.GenerationJobFailed
        assertFalse(emitted.issued, "a plain draft print job is not the issued document")
    }

    // ── DSC001-2025-09-00001 — markFailed's extraResultRefs carries the worker's own
    // llmStatus (kotlin-xpdf JobConsumer.llmStatusForFailedJob) through into resultRefs, alongside
    // the plain {"error": ...} it always wrote. ─────────────────────────────────────────────────

    @Test
    fun `markFailed with extraResultRefs merges llmStatus alongside the error message`() {
        val invoice = validInvoice(509L)
        val (service) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        val failed = service.recoveryService.markFailed(job, message = "Factur-X engine rejected", extraResultRefs = mapOf("llmStatus" to "FAILED"))

        val refs = com.fasterxml.jackson.databind.ObjectMapper().readValue(failed.resultRefs!!, Map::class.java)
        assertEquals("Factur-X engine rejected", refs["error"])
        assertEquals("FAILED", refs["llmStatus"])
    }

    @Test
    fun `markFailed without extraResultRefs behaves exactly as before ARC-24`() {
        val invoice = validInvoice(510L)
        val (service) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        val failed = service.recoveryService.markFailed(job, message = "worker crashed")

        assertEquals("""{"error":"worker crashed"}""", failed.resultRefs)
    }

    @Test
    fun `updateLlmAnalysisFromResult COMPLETED emits InvoiceLlmAnalysisResolved`() {
        val invoice = validInvoice(503L)
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val llmRepo = InMemoryLlmAnalysisRepository(invoice)
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))
        llmRepo.pendingRecord(invoice.id!!, model = "Qwen-mini")

        service.llmResultHandler.updateLlmAnalysisFromResult(invoice.id, mapOf("llmStatus" to "COMPLETED", "llmModel" to "Qwen-mini"))

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fireAsync(eventCaptor.capture())
        val emitted = eventCaptor.firstValue
        assertTrue(emitted is de.littletrotter.domain.event.InvoiceLlmAnalysisResolved)
        emitted as de.littletrotter.domain.event.InvoiceLlmAnalysisResolved
        assertEquals(invoice.id, emitted.invoiceId)
        assertEquals(invoice.invoiceNumber.value, emitted.invoiceNumber)
        assertEquals("Qwen-mini", emitted.modelName)
        assertEquals(LlmAnalysisStatus.COMPLETED, emitted.status)
    }

    @Test
    fun `updateLlmAnalysisFromResult NOT_REQUESTED (DISABLED) does not emit InvoiceLlmAnalysisResolved`() {
        val invoice = validInvoice(504L)
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val llmRepo = InMemoryLlmAnalysisRepository(invoice)
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))
        llmRepo.pendingRecord(invoice.id!!)

        service.llmResultHandler.updateLlmAnalysisFromResult(invoice.id, mapOf("llmStatus" to "NOT_REQUESTED"))

        verify(domainEventBus, never()).fireAsync(any())
    }

    // ── The FIRST callback now reports llmStatus "PENDING" itself once an analysis was
    // accepted; updateLlmAnalysisFromResult must treat that as a no-op, not demote the record. ──

    @Test
    fun `updateLlmAnalysisFromResult PENDING leaves an existing PENDING record untouched and fires no event`() {
        val invoice = validInvoice(506L)
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val llmRepo = InMemoryLlmAnalysisRepository(invoice)
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))
        val record = llmRepo.pendingRecord(invoice.id!!, model = "Qwen-mini")

        service.llmResultHandler.updateLlmAnalysisFromResult(invoice.id, mapOf("llmStatus" to "PENDING", "llmModel" to "Qwen-mini"))

        assertEquals(LlmAnalysisStatus.PENDING, llmRepo.findByInvoiceIdAndModelName(invoice.id!!, "Qwen-mini")?.status)
        assertEquals(record.summary, llmRepo.findByInvoiceIdAndModelName(invoice.id!!, "Qwen-mini")?.summary)
        verify(domainEventBus, never()).fireAsync(any())
    }

    // ── createJob fires GenerationJobCreated for the fast-path publish ──────────────

    @Test
    fun `createJob fires GenerationJobCreated synchronously with the persisted job id`() {
        val invoice = validInvoice(126L)
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))

        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        // fire() (synchronous), not fireAsync() — TransactionPhase.AFTER_SUCCESS observers only
        // apply to a synchronously fired event (GenerationJobCreated's own KDoc).
        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fire(eventCaptor.capture())
        val emitted = eventCaptor.firstValue
        assertTrue(emitted is GenerationJobCreated)
        emitted as GenerationJobCreated
        assertEquals(job.id, emitted.jobId)
    }

    @Test
    fun `createJob does not fire GenerationJobCreated when the saved job carries no id`() {
        val invoice = validInvoice(127L)
        val domainEventBus: Event<DomainEvent> = mock()
        // Deliberately never assigns an id — proof of the defensive null-check around fire().
        val jobRepo = object : GenerationJobRepository by InMemoryGenerationJobRepository() {
            override fun save(job: GenerationJob): GenerationJob = job
        }
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))

        service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        verify(domainEventBus, never()).fire(any())
    }

    // markCompleted no longer writes Invoice.validationStatus itself (that follow-up
    // reaction moved to DocumentGeneratedReactionHandler, observing the DocumentGenerated event
    // markCompleted now only *fires*); see DocumentGeneratedReactionHandlerTest for the equivalent
    // coverage ("writes INVALID/VALID validationStatus", "leaves NOT_VALIDATED default when no
    // report", "older job does not override newer job validationStatus").

    // ── validation guard tests ─────────────────────────────────────────────────

    @Test
    fun `createJob succeeds for a fully valid invoice`() {
        val invoice = validInvoice(200L)
        val (service) = makeService(invoice)

        val job = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)

        assertNotNull(job.id)
        assertEquals(GenerationJobStatus.PENDING, job.status)
    }

    @Test
    fun `createJob rejects invoice without sender profile`() {
        val invoice = validInvoice(201L).also { it.edit { senderProfile = null } }
        val (service) = makeService(invoice)

        assertThrows<InvoiceValidationException> {
            service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        }
    }

    @Test
    fun `createJob rejects invoice with missing tax rate reference on item`() {
        val invoice = validInvoice(202L).also { inv ->
            inv.itemList.forEach { it.edit { taxRateRef = null } }
        }
        val (service) = makeService(invoice)

        assertThrows<InvoiceValidationException> {
            service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        }
    }

    @Test
    fun `createJob rejects invoice with no items`() {
        val invoice = validInvoice(203L).also {
            it.itemList.asMutableForTest().clear()
            PersistenceEmulation.assignTotals(it, InvoiceTotals(netAmount = Money(BigDecimal.ZERO), vatAmount = Money(BigDecimal.ZERO), grossAmount = Money(BigDecimal.ZERO)))
        }
        val (service) = makeService(invoice)

        assertThrows<InvoiceValidationException> {
            service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        }
    }

    @Test
    fun `createJob rejects invoice that fails revalidation after reload`() {
        // createJob() reloads the invoice, calls recalculateTotals() (which derives net/vat/gross
        // fresh from the items and so always "repairs" any stale stored totals), and then
        // validate()s the result. A totals mismatch alone can therefore never survive to
        // validate() — use a defect recalculateTotals() does not touch instead. A due date before
        // the invoice date used to serve here; it is only a warning now, so this uses a service
        // period that ends before it starts, which validate() still rejects outright.
        val invoice = validInvoice(204L).also {
            it.edit { servicePeriodFrom = java.time.LocalDate.of(2026, 1, 10) }
            it.edit { servicePeriodTo = java.time.LocalDate.of(2026, 1, 1) }
        }
        val (service) = makeService(invoice)

        assertThrows<InvoiceValidationException> {
            service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        }
    }

    @Test
    fun `createJob accepts invoice when totals match recalculation despite scale differences`() {
        val invoice = validInvoice(205L).also {
            PersistenceEmulation.assignTotals(it, InvoiceTotals(netAmount = Money(BigDecimal("100.0000")), vatAmount = Money(BigDecimal("19.0000")), grossAmount = Money(BigDecimal("119.0000"))))
        }
        val (service) = makeService(invoice)

        val job = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)

        assertNotNull(job.id)
        assertEquals(GenerationJobStatus.PENDING, job.status)
    }

    @Test
    fun `createJob rejects invoice not found in repository`() {
        val invoice = validInvoice(999L)
        val (service) = makeService() // empty repo — invoice not stored

        assertThrows<InvoiceNotFoundException> {
            service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        }
    }

    // ── Task S: creating a print job must not silently change a finalized invoice's totals ──

    @Test
    fun `createJob does not recalculate a finalized invoice`() {
        // Locked-in totals as they were when the invoice was finalized. They deliberately do
        // NOT match quantity x unitPriceNet (+tax) any more, standing in for "the invoice was
        // legitimately amended/corrected once, then finalized with a value recalculateTotals()
        // would no longer reproduce". Before the fix, createJob() reloaded the invoice, called
        // recalculateTotals() unconditionally, and that mutation on the managed entity got
        // persisted on commit — overwriting the finalized amount with a different number.
        val invoice = validInvoice(555L).edit {
            forceStatusForTest(InvoiceStatus.ISSUED)
            totals = InvoiceTotals(netAmount = Money(BigDecimal("250.00")), vatAmount = Money(BigDecimal("47.50")), grossAmount = Money(BigDecimal("297.50")))
        }
        val (service) = makeService(invoice)

        service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)

        assertEquals(Money(BigDecimal("250.00")), invoice.totals.netAmount)
        assertEquals(Money(BigDecimal("47.50")), invoice.totals.vatAmount)
        assertEquals(Money(BigDecimal("297.50")), invoice.totals.grossAmount)
    }

    @Test
    fun `createJob still recalculates an open invoice`() {
        val invoice = validInvoice(556L).edit {
            forceStatusForTest(InvoiceStatus.DRAFT)
            // Stale totals a save() elsewhere forgot to refresh — createJob() must still fix
            // these for an invoice that is not read-only, exactly as before this task.
            totals = InvoiceTotals(netAmount = Money(BigDecimal("1.00")), vatAmount = Money(BigDecimal("1.00")), grossAmount = Money(BigDecimal("2.00")))
        }
        val (service) = makeService(invoice)

        service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)

        assertEquals(Money(BigDecimal("100.00")), invoice.totals.netAmount)
        assertEquals(Money(BigDecimal("19.00")), invoice.totals.vatAmount)
        assertEquals(Money(BigDecimal("119.00")), invoice.totals.grossAmount)
    }

    // ── Validation finding persistence ────────────────────────────────────
    // Finding persistence moved from markCompleted to
    // DocumentGeneratedReactionHandler.onDocumentGeneratedPersistFindings, its own CDI dispatch
    // entry point/transaction, observing the DocumentGenerated event markCompleted fires. All
    // these scenarios (one row per finding, VALID/no-findings, legacy payload without ruleIds,
    // two jobs staying distinguishable, an older job's late findings, a write failure not
    // affecting anything else) now live in DocumentGeneratedReactionHandlerTest.

    // ── BUSINESS stage merge ─────────

    /** The shape a real worker (kotlin-xpdf's PdfExportServiceImpl) sends: SYNTAX/KOSIT/PDFA
     *  already evaluated, BUSINESS always `skipped` — the placeholder closes. */
    private fun workerReportWithSkippedBusinessStage(): String {
        val stages = listOf(
            de.littletrotter.shared.ValidationStageResult("SYNTAX", de.littletrotter.shared.KositOutcome.VALID),
            de.littletrotter.shared.ValidationStageResult("KOSIT", de.littletrotter.shared.KositOutcome.VALID),
            de.littletrotter.shared.ValidationStageResult("PDFA", de.littletrotter.shared.KositOutcome.VALID),
            de.littletrotter.shared.ValidationStageResult(
                "BUSINESS", de.littletrotter.shared.KositOutcome.VALID,
                skipped = true, skipReason = "TAX-03/PARTY-01 noch nicht integriert",
            ),
        )
        val run = de.littletrotter.shared.ValidationRun(
            stages = stages,
            overall = de.littletrotter.shared.ValidationRun.overallOutcomeOf(stages),
            startedAt = "2026-08-22T00:00:00Z",
        )
        return de.littletrotter.shared.KositReportPayload.encode(
            de.littletrotter.shared.KositValidationResult(
                status = run.overall,
                details = stages.flatMap { it.findings },
                validationRun = run,
            )
        )
    }

    @Test
    fun `ARC-6 markCompleted replaces the skipped BUSINESS stage with the real compliance evaluation`() {
        // date invariants (PO 2026-09-02) — invoiceDate can no longer be null (BT-2 is now
        // mandatory from creation), so this can no longer be the missing mandatory field that
        // drives the BUSINESS-stage finding. CORE.BUYER_ADDRESS takes over that role instead:
        // it is an ERROR-severity CoreCompletenessRules check (unlike CORE.BUYER_EMAIL, it is
        // NOT also enforced by Recipient.validate()/Invoice.validate(), which never requires an
        // address to be set at all — only validates one if present — so createJob's own
        // loaded.validate() call still succeeds and lets this test reach markCompleted). The
        // fixture's recipient already carries no billing/delivery address by default.
        val invoice = validInvoice(700L)
        val (service) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)

        val completed = service.resultHandler.markCompleted(job, "{}", workerReportWithSkippedBusinessStage())

        val payload = de.littletrotter.shared.KositReportPayload.decode(completed.kositReport)
        assertNotNull(payload)
        val run = payload!!.validationRun
        assertNotNull(run)
        val businessStage = run!!.stages.first { it.stage == "BUSINESS" }
        assertEquals(false, businessStage.skipped)
        assertEquals(de.littletrotter.shared.KositOutcome.INVALID, businessStage.outcome)
        val finding = businessStage.findings.single { it.ruleId == "CORE.BUYER_ADDRESS" }
        assertEquals("error", finding.severity)
        // date invariants (PO 2026-09-02) — CORE.BUYER_ADDRESS targets the buyer, not the
        // header (unlike the CORE.INVOICE_DATE this test used to drive the finding with).
        assertEquals("buyer", finding.location)
        assertEquals("BUSINESS", finding.stage)
        assertEquals("compliance", finding.validator)

        // SYNTAX/KOSIT/PDFA stages are exactly what the worker sent — untouched.
        assertEquals(de.littletrotter.shared.KositOutcome.VALID, run.stages.first { it.stage == "SYNTAX" }.outcome)
        assertEquals(de.littletrotter.shared.KositOutcome.VALID, run.stages.first { it.stage == "KOSIT" }.outcome)
        assertEquals(de.littletrotter.shared.KositOutcome.VALID, run.stages.first { it.stage == "PDFA" }.outcome)
        assertTrue(run.stages.filter { it.stage != "BUSINESS" }.none { it.skipped })

        // The run's (and the flat legacy) overall outcome now reflects the BUSINESS finding too —
        // PO decision #2: OBSERVE still reports honestly, only FinalizationGate decides blocking.
        assertEquals(de.littletrotter.shared.KositOutcome.INVALID, run.overall)
        assertEquals("INVALID", payload.status)
        // Invoice.validationStatus and the persisted ValidationFinding rows are no
        // longer written by markCompleted itself; see DocumentGeneratedReactionHandlerTest for
        // the equivalent "the merged BUSINESS finding reaches both the invoice and the findings
        // table" coverage, built on top of exactly this same merged report shape.
    }

    @Test
    fun `ARC-6 markCompleted appends the BUSINESS stage when a run carries no placeholder at all`() {
        // A worker older than the F3 placeholder sends a run with only its own stages — the
        // compliance evaluation must be appended, not dropped because the slot is missing.
        // date invariants (PO 2026-09-02) — CORE.BUYER_ADDRESS substitutes for the no-longer-
        // nullable invoiceDate as the missing mandatory field, see the sibling test above.
        val invoice = validInvoice(702L)
        val (service) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        val stages = listOf(
            de.littletrotter.shared.ValidationStageResult("SYNTAX", de.littletrotter.shared.KositOutcome.VALID),
            de.littletrotter.shared.ValidationStageResult("KOSIT", de.littletrotter.shared.KositOutcome.VALID),
        )
        val run = de.littletrotter.shared.ValidationRun(
            stages = stages,
            overall = de.littletrotter.shared.ValidationRun.overallOutcomeOf(stages),
            startedAt = "2026-08-22T00:00:00Z",
        )
        val reportWithoutBusiness = de.littletrotter.shared.KositReportPayload.encode(
            de.littletrotter.shared.KositValidationResult(status = run.overall, validationRun = run)
        )

        service.publishingService.markDelegated(job)
        val completed = service.resultHandler.markCompleted(job, "{}", reportWithoutBusiness)

        val mergedRun = de.littletrotter.shared.KositReportPayload.decode(completed.kositReport)!!.validationRun!!
        val businessStage = mergedRun.stages.single { it.stage == "BUSINESS" }
        assertEquals(false, businessStage.skipped)
        assertEquals(de.littletrotter.shared.KositOutcome.INVALID, businessStage.outcome)
        assertTrue(businessStage.findings.any { it.ruleId == "CORE.BUYER_ADDRESS" })
        assertEquals(de.littletrotter.shared.KositOutcome.INVALID, mergedRun.overall)
    }

    @Test
    fun `ARC-6 markCompleted leaves a pre-F3 report without a validationRun byte-for-byte unchanged`() {
        val invoice = validInvoice(701L)
        val (service) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        // No `validationRun` at all — exactly what every other GenerationJob* test in this file
        // and GenerationJobServiceIT sends (a shape with no BUSINESS stage yet); there is no stage list to
        // slot BUSINESS into, so the report must pass through as-is.
        val legacyJson = de.littletrotter.shared.KositReportPayload.encode(
            de.littletrotter.shared.KositValidationResult(status = de.littletrotter.shared.KositOutcome.VALID)
        )

        service.publishingService.markDelegated(job)
        val completed = service.resultHandler.markCompleted(job, "{}", legacyJson)

        assertEquals(legacyJson, completed.kositReport)
    }

    @Test
    fun `L25 - a compliance rule set that throws still completes the job, returns the raw report, and logs at error`() {
        // A repository call inside b2bProfileRuleSet throwing must not fail job completion — the
        // document was already generated. Before this fix that stance was right but hidden behind
        // a warn log; the catch itself (still returning the raw, unmerged report) is unchanged.
        val invoice = validInvoice(703L).edit {
            b2bProfile = de.littletrotter.domain.reference.B2bProfile().edit { id = 55L }
        }
        val jobRepo = InMemoryGenerationJobRepository()
        val profileRepository = mock<de.littletrotter.domain.repository.B2bProfileRepository>()
        whenever(profileRepository.findFieldsByProfileIds(listOf(55L))).thenThrow(RuntimeException("b2b field catalog unavailable"))
        val throwingB2bRuleSet = B2bProfileRuleSet(profileRepository, mock(), mock())
        val service = GenerationJobCollaborators(
            jobRepo, mock(), InMemoryInvoiceRepository(invoice),
            b2bProfileRuleSet = throwingB2bRuleSet,
        )
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)

        val completed = assertDoesNotThrow<GenerationJob> {
            service.resultHandler.markCompleted(job, "{}", workerReportWithSkippedBusinessStage())
        }

        assertEquals(GenerationJobStatus.COMPLETED, completed.status, "the document was already generated — a rule-set failure must not fail job completion")
        assertEquals(
            workerReportWithSkippedBusinessStage(), completed.kositReport,
            "on a rule-set failure the raw, unmerged worker report must be returned unchanged",
        )
    }

    // ── L25/L2 — markCompleted/markFailed must never write through a detached parameter ────────
    // The real defect only surfaces against a genuine Hibernate session (see GenerationJobServiceIT
    // and GenerationJobServiceConcurrencyIT for the Postgres-backed reproductions); at the unit level
    // it is reproduced structurally: a fake repository whose `findById` returns a DIFFERENT,
    // "managed" instance than whatever the (possibly stale/detached) `job` parameter carries —
    // exactly the asymmetry L25's own diagnosis names (`job.invoice` vs `saved.invoice` used to
    // differ). Before the fix, markCompleted/markFailed trusted the parameter directly and never
    // called `findById` at all.

    /** `findById` always returns a freshly built "managed" job wired to [managedInvoice] —
     *  deliberately independent of whatever the caller's own `job` parameter carries, modelling
     *  `GenerationJobResultConsumer`'s own re-read after `clearSessionBeforeAttempt`. `save` is a
     *  passthrough so tests can assert on exactly what was written. */
    private class DetachReproRepository(private val jobId: Long, private val managedInvoice: Invoice) :
        GenerationJobRepository by InMemoryGenerationJobRepository() {
        var lastSaved: GenerationJob? = null
        override fun findById(id: Long): GenerationJob? {
            if (id != jobId) return null
            return aGenerationJob {
                this.id = jobId
                this.invoice = managedInvoice
                this.status = GenerationJobStatus.DELEGATED
            }
        }
        override fun findByIdForUpdate(id: Long): GenerationJob? = findById(id)
        override fun save(job: GenerationJob): GenerationJob {
            lastSaved = job
            return job
        }
    }

    @Test
    fun `L25 - markCompleted evaluates BUSINESS against the freshly reloaded managed job, never a detached parameter`() {
        // The caller's own reference (as GenerationJobResultConsumer hands it over after clearing
        // its session) carries no usable invoice at all here — modelling a detached lazy
        // association that can no longer be initialised. This test must fail against the
        // pre-L25 code: it used `job.invoice` (null here) directly, so the BUSINESS stage stayed
        // the worker's skipped placeholder forever.
        val managedInvoice = validInvoice(9001L)
        val repo = DetachReproRepository(jobId = 42L, managedInvoice = managedInvoice)
        val service = GenerationJobCollaborators(repo, mock(), InMemoryInvoiceRepository(managedInvoice))
        val detachedJob = aGenerationJob {
            id = 42L
            invoice = null
            status = GenerationJobStatus.DELEGATED
        }

        val completed = service.resultHandler.markCompleted(detachedJob, "{}", workerReportWithSkippedBusinessStage())

        val run = de.littletrotter.shared.KositReportPayload.decode(completed.kositReport)!!.validationRun!!
        val businessStage = run.stages.single { it.stage == "BUSINESS" }
        assertFalse(
            businessStage.skipped,
            "L25 - BUSINESS must be evaluated from the job reloaded fresh by id, never silently " +
                "left as the worker's placeholder just because the caller's own job/invoice reference was detached",
        )
    }

    @Test
    fun `L25 - the entity actually saved carries the reloaded managed invoice, not the detached parameter's`() {
        // Definition-of-done regression guard: what mergeBusinessStage reads (job.invoice) and what
        // gets saved/fires the event (saved.invoice) must be the exact same object — the asymmetry
        // L25's own diagnosis names as the bug (GenerationJobResultHandler.markCompleted used to pass the
        // parameter's own job.invoice into mergeBusinessStage while reading saved.invoice
        // separately for the fired event).
        val managedInvoice = validInvoice(9002L)
        val repo = DetachReproRepository(jobId = 43L, managedInvoice = managedInvoice)
        val service = GenerationJobCollaborators(repo, mock(), InMemoryInvoiceRepository(managedInvoice))
        val detachedJob = aGenerationJob { id = 43L; invoice = null; status = GenerationJobStatus.DELEGATED }

        service.resultHandler.markCompleted(detachedJob, "{}")

        assertTrue(repo.lastSaved?.invoice === managedInvoice, "the entity actually saved must carry the reloaded, managed invoice")
    }

    @Test
    fun `L2 - markFailed clears PENDING validation status on the freshly reloaded invoice, never a stale detached one`() {
        // Unit-level reproduction of L2's "never write through an entity that crossed a
        // transaction boundary": staleInvoice models what the caller's own (detached) job
        // reference carries, currentInvoice models what a fresh findById re-read inside THIS
        // transaction would actually see. Before the fix, markFailed operated on job.invoice
        // (staleInvoice) throughout and never consulted the repository again — this test fails
        // against that code because staleInvoice, not currentInvoice, ends up cleared.
        val staleInvoice = validInvoice(9101L).edit { validationStatus = ValidationStatus.PENDING }
        val currentInvoice = validInvoice(9101L).edit { validationStatus = ValidationStatus.PENDING }
        val invoiceRepo = InMemoryInvoiceRepository(currentInvoice)
        val repo = DetachReproRepository(jobId = 44L, managedInvoice = currentInvoice)
        val service = GenerationJobCollaborators(repo, mock(), invoiceRepo)
        val detachedJob = aGenerationJob { id = 44L; invoice = staleInvoice; status = GenerationJobStatus.DELEGATED }

        service.recoveryService.markFailed(detachedJob, message = "worker crashed")

        assertEquals(
            ValidationStatus.UNAVAILABLE, currentInvoice.validationStatus,
            "L2 - the freshly reloaded (current) invoice must be the one actually cleared",
        )
        assertEquals(
            ValidationStatus.PENDING, staleInvoice.validationStatus,
            "L2 - the caller's own stale/detached invoice reference must never be mutated or written through",
        )
    }

    // ── LLM status chain ──────────────────────────────────────────────────

    @Test
    fun `V3 success closes PENDING analysis to COMPLETED with model and duration`() {
        val invoice = validInvoice(400L)
        val (service, _, llmRepo) = makeServiceWithLlm(invoice)
        llmRepo.pendingRecord(invoice.id!!)

        service.llmResultHandler.updateLlmAnalysisFromResult(
            invoice.id,
            mapOf("llmStatus" to "COMPLETED", "llmModel" to "Qwen-mini", "llmDurationMs" to "1234"),
        )

        val record = llmRepo.findByInvoiceId(invoice.id!!).single()
        assertEquals(LlmAnalysisStatus.COMPLETED, record.status)
        assertEquals("Qwen-mini", record.modelName)
        assertNotNull(record.summary)
        assertTrue(record.summary!!.contains("1234"))
    }

    @Test
    fun `V3 failure closes PENDING analysis to FAILED with error text`() {
        val invoice = validInvoice(401L)
        val (service, _, llmRepo) = makeServiceWithLlm(invoice)
        llmRepo.pendingRecord(invoice.id!!)

        service.llmResultHandler.updateLlmAnalysisFromResult(
            invoice.id,
            mapOf("llmStatus" to "FAILED", "llmModel" to "oMLX", "llmError" to "oMLX HTTP 500"),
        )

        val record = llmRepo.findByInvoiceId(invoice.id!!).single()
        assertEquals(LlmAnalysisStatus.FAILED, record.status)
        assertEquals("oMLX HTTP 500", record.issues)
    }

    @Test
    fun `V3 not-requested closes a PENDING analysis to DISABLED`() {
        val invoice = validInvoice(402L)
        val (service, _, llmRepo) = makeServiceWithLlm(invoice)
        llmRepo.pendingRecord(invoice.id!!)

        service.llmResultHandler.updateLlmAnalysisFromResult(invoice.id, mapOf("llmStatus" to "NOT_REQUESTED"))

        assertEquals(LlmAnalysisStatus.DISABLED, llmRepo.findByInvoiceId(invoice.id!!).single().status)
    }

    @Test
    fun `V3 not-requested does not regress an already completed analysis`() {
        val invoice = validInvoice(403L)
        val (service, _, llmRepo) = makeServiceWithLlm(invoice)
        llmRepo.pendingRecord(invoice.id!!)
        // first a successful run closes it to COMPLETED
        service.llmResultHandler.updateLlmAnalysisFromResult(invoice.id, mapOf("llmStatus" to "COMPLETED", "llmModel" to "oMLX"))
        // a later job without the LLM must not downgrade it
        service.llmResultHandler.updateLlmAnalysisFromResult(invoice.id, mapOf("llmStatus" to "NOT_REQUESTED"))

        assertEquals(LlmAnalysisStatus.COMPLETED, llmRepo.findByInvoiceId(invoice.id!!).single().status)
    }

    @Test
    fun `V3 a failed job marks pending analyses as FAILED`() {
        val invoice = validInvoice(404L)
        val (service, _, llmRepo) = makeServiceWithLlm(invoice)
        llmRepo.pendingRecord(invoice.id!!)

        service.llmResultHandler.failPendingAnalysesForInvoice(invoice.id)

        assertEquals(LlmAnalysisStatus.FAILED, llmRepo.findByInvoiceId(invoice.id!!).single().status)
    }

    @Test
    fun `V3 unknown status leaves an already terminal analysis untouched`() {
        val invoice = validInvoice(405L)
        val (service, _, llmRepo) = makeServiceWithLlm(invoice)
        llmRepo.pendingRecord(invoice.id!!)
        service.llmResultHandler.updateLlmAnalysisFromResult(invoice.id, mapOf("llmStatus" to "COMPLETED"))

        service.llmResultHandler.updateLlmAnalysisFromResult(invoice.id, emptyMap())

        assertEquals(LlmAnalysisStatus.COMPLETED, llmRepo.findByInvoiceId(invoice.id!!).single().status)
    }

    // ── tryMarkDelegated — atomarer PENDING->DELEGATED-Uebergang ─────────────────

    @Test
    fun `tryMarkDelegated liefert false ohne das Repository zu befragen wenn der Auftrag noch keine id hat`() {
        val repo = mock<GenerationJobRepository>()
        val svc = GenerationJobCollaborators(repo, mock())
        val unsavedJob = GenerationJob()

        val result = svc.publishingService.tryMarkDelegated(unsavedJob)

        assertEquals(false, result)
        verify(repo, never()).tryMarkDelegated(any())
    }

    @Test
    fun `tryMarkDelegated delegiert an das Repository und reicht dessen Ergebnis durch`() {
        val repo = mock<GenerationJobRepository>()
        whenever(repo.tryMarkDelegated(7L)).thenReturn(false)
        val svc = GenerationJobCollaborators(repo, mock())
        val job = aGenerationJob { id = 7L }

        val result = svc.publishingService.tryMarkDelegated(job)

        assertEquals(false, result, "ein bereits von anderer Seite umgeschalteter Auftrag muss als Fehlschlag durchgereicht werden, nicht als Erfolg")
        verify(repo).tryMarkDelegated(7L)
    }

    // ── updateLlmAnalysisFromResult / failPendingAnalysesForInvoice — Randfaelle ──

    @Test
    fun `updateLlmAnalysisFromResult tut nichts wenn keine Rechnungs-id vorliegt`() {
        val (service, _, _) = makeServiceWithLlm()

        assertDoesNotThrow { service.llmResultHandler.updateLlmAnalysisFromResult(null, mapOf("llmStatus" to "COMPLETED")) }
    }

    @Test
    fun `updateLlmAnalysisFromResult tut nichts wenn die Rechnung keine LLM-Analysen hat`() {
        val invoice = validInvoice(410L)
        val (service, _, llmRepo) = makeServiceWithLlm(invoice)
        // no pendingRecord() created

        service.llmResultHandler.updateLlmAnalysisFromResult(invoice.id, mapOf("llmStatus" to "COMPLETED"))

        assertTrue(llmRepo.findByInvoiceId(invoice.id!!).isEmpty())
    }

    @Test
    fun `updateLlmAnalysisFromResult laesst eine PENDING-Analyse unangetastet wenn llmStatus fehlt (Alt-Producer vor V3)`() {
        val invoice = validInvoice(411L)
        val (service, _, llmRepo) = makeServiceWithLlm(invoice)
        llmRepo.pendingRecord(invoice.id!!)

        service.llmResultHandler.updateLlmAnalysisFromResult(invoice.id, mapOf("resultRefs" to "s3://irrelevant"))

        assertEquals(LlmAnalysisStatus.PENDING, llmRepo.findByInvoiceId(invoice.id!!).single().status)
    }

    @Test
    fun `failPendingAnalysesForInvoice tut nichts wenn keine Rechnungs-id vorliegt`() {
        val (service, _, _) = makeServiceWithLlm()
        assertDoesNotThrow { service.llmResultHandler.failPendingAnalysesForInvoice(null) }
    }

    @Test
    fun `failPendingAnalysesForInvoice ueberschreibt einen bereits vorhandenen Ausfalltext nicht`() {
        val invoice = validInvoice(412L)
        val (service, _, llmRepo) = makeServiceWithLlm(invoice)
        val record = llmRepo.pendingRecord(invoice.id!!)
        record.edit { issues = "Bereits dokumentierter Grund" }

        service.llmResultHandler.failPendingAnalysesForInvoice(invoice.id)

        assertEquals("Bereits dokumentierter Grund", llmRepo.findByInvoiceId(invoice.id!!).single().issues)
    }

    @Test
    fun `V3 der Zusammenfassungstext traegt keine Dauer wenn keine uebergeben wurde`() {
        val invoice = validInvoice(413L)
        val (service, _, llmRepo) = makeServiceWithLlm(invoice)
        llmRepo.pendingRecord(invoice.id!!)

        service.llmResultHandler.updateLlmAnalysisFromResult(invoice.id, mapOf("llmStatus" to "COMPLETED", "llmModel" to "oMLX"))

        val summary = llmRepo.findByInvoiceId(invoice.id!!).single().summary
        assertEquals("KI-Prüfung: fertig", summary, "ohne llmDurationMs darf keine Klammer mit einer erfundenen Dauer auftauchen")
    }

    // ── Worker-restart recovery: a job whose FIRST callback reported
    // llmStatus PENDING but whose SECOND (deferred) callback never arrived must not stay stuck
    // forever. ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `resetStaleLlmPending flips an old PENDING llmStatus to FAILED and resolves the analysis row`() {
        val invoice = validInvoice(511L)
        val jobRepo = InMemoryGenerationJobRepository()
        val llmRepo = InMemoryLlmAnalysisRepository(invoice)
        val domainEventBus: Event<DomainEvent> = mock()
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))
        llmRepo.pendingRecord(invoice.id!!, model = "qwen")
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        service.resultHandler.markCompleted(job, resultRefs = """{"objectKey":"invoice/1.pdf","llmStatus":"PENDING"}""")
        job.edit { this.finishedAt = java.time.OffsetDateTime.now().minusMinutes(30) }
        jobRepo.save(job)

        val resolved = service.recoveryService.resetStaleLlmPending(staleCutoffMinutes = 15, lookbackHours = 24)

        assertEquals(1, resolved)
        val refs = com.fasterxml.jackson.databind.ObjectMapper().readValue(jobRepo.findById(job.id!!)!!.resultRefs!!, Map::class.java)
        assertEquals("FAILED", refs["llmStatus"])
        assertEquals("invoice/1.pdf", refs["objectKey"], "the rest of resultRefs must survive the recovery flip")
        assertEquals(LlmAnalysisStatus.FAILED, llmRepo.findByInvoiceIdAndModelName(invoice.id!!, "qwen")?.status)
    }

    @Test
    fun `resetStaleLlmPending never touches a job whose llmStatus already resolved`() {
        val invoice = validInvoice(512L)
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, mock(), InMemoryInvoiceRepository(invoice))
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        service.resultHandler.markCompleted(job, resultRefs = """{"llmStatus":"COMPLETED"}""")
        job.edit { this.finishedAt = java.time.OffsetDateTime.now().minusMinutes(30) }
        jobRepo.save(job)

        val resolved = service.recoveryService.resetStaleLlmPending(staleCutoffMinutes = 15, lookbackHours = 24)

        assertEquals(0, resolved)
        assertEquals("""{"llmStatus":"COMPLETED"}""", jobRepo.findById(job.id!!)!!.resultRefs)
    }

    @Test
    fun `resetStaleLlmPending ignores a job that finished too recently to be stale yet`() {
        val invoice = validInvoice(513L)
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, mock(), InMemoryInvoiceRepository(invoice))
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        service.resultHandler.markCompleted(job, resultRefs = """{"llmStatus":"PENDING"}""") // finishedAt just now

        val resolved = service.recoveryService.resetStaleLlmPending(staleCutoffMinutes = 15, lookbackHours = 24)

        assertEquals(0, resolved)
        assertEquals("""{"llmStatus":"PENDING"}""", jobRepo.findById(job.id!!)!!.resultRefs)
    }

    // Every existing resetStaleLlmPending call above passes both arguments explicitly;
    // the default-value bridge (staleCutoffMinutes=15, lookbackHours=24) had no coverage.
    @Test
    fun `happy path - resetStaleLlmPending uses its documented 15-minute-45-hour defaults when called with no arguments`() {
        val invoice = validInvoice(514L)
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, mock(), InMemoryInvoiceRepository(invoice))
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        service.resultHandler.markCompleted(job, resultRefs = """{"llmStatus":"PENDING"}""")
        job.edit { this.finishedAt = java.time.OffsetDateTime.now().minusMinutes(30) }
        jobRepo.save(job)

        val resolved = service.recoveryService.resetStaleLlmPending()

        assertEquals(1, resolved)
    }

    // resetStaleLlmPendingBatched's real implementation (as opposed to the fake
    // subclass GenerationJobProcessorTest overrides it with) had no direct coverage: it chunks
    // the candidate ids and delegates each chunk to LlmPendingRecoveryBatchTransaction.resolveBatch.
    @Test
    fun `happy path - resetStaleLlmPendingBatched chunks the stale candidates and sums each batch's result`() {
        val invoice = validInvoice(515L)
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, mock(), InMemoryInvoiceRepository(invoice))
        val jobOne = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(jobOne)
        service.resultHandler.markCompleted(jobOne, resultRefs = """{"llmStatus":"PENDING"}""")
        jobOne.edit { this.finishedAt = java.time.OffsetDateTime.now().minusMinutes(30) }
        jobRepo.save(jobOne)
        val jobTwo = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(jobTwo)
        service.resultHandler.markCompleted(jobTwo, resultRefs = """{"llmStatus":"PENDING"}""")
        jobTwo.edit { this.finishedAt = java.time.OffsetDateTime.now().minusMinutes(31) }
        jobRepo.save(jobTwo)
        val batchTransaction = mock<LlmPendingRecoveryBatchTransaction>()
        val idsCaptor = argumentCaptor<List<Long>>()
        whenever(batchTransaction.resolveBatch(any())).thenReturn(1)

        val resolved = service.recoveryService.resetStaleLlmPendingBatched(batchTransaction, staleCutoffMinutes = 15, lookbackHours = 24, batchSize = 1)

        assertEquals(2, resolved, "one chunk of size 1 per stale job, each contributing 1")
        verify(batchTransaction, org.mockito.kotlin.times(2)).resolveBatch(idsCaptor.capture())
        assertTrue(idsCaptor.allValues.all { it.size == 1 }, "batchSize=1 must split the two candidates into separate chunks")
    }

    // Every resetStaleLlmPendingBatched call above passes every argument explicitly; the
    // default-value bridge (staleCutoffMinutes=15, lookbackHours=24, batchSize=200) had no coverage.
    @Test
    fun `happy path - resetStaleLlmPendingBatched uses its documented defaults when called with only batchTransaction`() {
        val invoice = validInvoice(517L)
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, mock(), InMemoryInvoiceRepository(invoice))
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        service.resultHandler.markCompleted(job, resultRefs = """{"llmStatus":"PENDING"}""")
        job.edit { this.finishedAt = java.time.OffsetDateTime.now().minusMinutes(30) }
        jobRepo.save(job)
        val batchTransaction = mock<LlmPendingRecoveryBatchTransaction>()
        whenever(batchTransaction.resolveBatch(any())).thenReturn(1)

        val resolved = service.recoveryService.resetStaleLlmPendingBatched(batchTransaction)

        assertEquals(1, resolved)
    }

    // resolveStaleLlmPendingForIds' own `findById(id) ?: return@forEach` guard: a
    // candidate id that no longer resolves to a job (deleted between the candidate scan and this
    // per-batch write).
    @Test
    fun `boundary - resolveStaleLlmPendingForIds skips an id that no longer resolves to a job`() {
        val (service) = makeService(validInvoice(518L))

        val resolved = service.recoveryService.resolveStaleLlmPendingForIds(listOf(999999L))

        assertEquals(0, resolved)
    }

    // NoOpInvoiceRepository (the secondary test constructor's
    // defaults) implement their full repository interfaces but GenerationJobCollaborators's bundle here only
    // ever calls a handful of their methods; the rest exist solely to satisfy the interface
    // contract and were never invoked by any test.
    @Test
    fun `boundary - NoOpInvoiceRepository answers every InvoiceRepository method as empty-read-only`() {
        assertNull(NoOpInvoiceRepository.findById(1L))
        assertNull(NoOpInvoiceRepository.findByIdWithItems(1L))
        assertNull(NoOpInvoiceRepository.findByInvoiceNumber("ANY-2026-0001"))
        assertTrue(NoOpInvoiceRepository.findAll(0, 20).isEmpty())
        assertEquals(0L, NoOpInvoiceRepository.countForOverview(null, null))
        assertEquals(0L, NoOpInvoiceRepository.count())
        assertEquals(0L, NoOpInvoiceRepository.countByStatus(InvoiceStatus.DRAFT))
        assertTrue(NoOpInvoiceRepository.findByRecipientId(1L, 0, 20).isEmpty())
        assertTrue(NoOpInvoiceRepository.findBySenderProfileId(1L, 0, 20).isEmpty())
        assertFalse(NoOpInvoiceRepository.existsByBillingAddressId(1L))
        assertFalse(NoOpInvoiceRepository.existsByDeliveryAddressId(1L))
        assertNull(NoOpInvoiceRepository.findInvoiceByItemId(1L))
        assertTrue(NoOpInvoiceRepository.findAllForRecalculation().isEmpty())
        assertThrows<UnsupportedOperationException> { NoOpInvoiceRepository.save(validInvoice(1L)) }
    }

    // ── Attempt-budget exhaustion, best-effort last_error recording, and the llm_status
    // column write on the first/second callback and the FAILED path. ──────────────────────────

    @Test
    fun `failExhaustedJob transitions PENDING to FAILED with a named reason and fires GenerationJobExhausted`() {
        // N5 — gates on invoice.status.isIssued now, see the markCompleted test above.
        val invoice = validInvoice(520L).also { it.forceStatusForTest(InvoiceStatus.ISSUED) }
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        job.edit { this.attempts = 5 }

        val result = service.recoveryService.failExhaustedJob(job, maxAttempts = 5)

        assertTrue(result)
        assertEquals(GenerationJobStatus.FAILED, jobRepo.findById(job.id!!)!!.status)
        assertNotNull(jobRepo.findById(job.id!!)!!.lastError)
        assertTrue(jobRepo.findById(job.id!!)!!.lastError!!.contains("5"))

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fireAsync(eventCaptor.capture())
        val emitted = eventCaptor.firstValue
        assertTrue(emitted is de.littletrotter.domain.event.GenerationJobExhausted)
        emitted as de.littletrotter.domain.event.GenerationJobExhausted
        assertEquals(invoice.id, emitted.invoiceId)
        assertEquals(invoice.invoiceNumber.value, emitted.invoiceNumber)
        assertEquals(job.id, emitted.generationJobId)
        assertEquals(5, emitted.attempts)
    }

    @Test
    fun `failExhaustedJob clears a PENDING invoice validationStatus to UNAVAILABLE and fails pending LLM analyses`() {
        val invoice = validInvoice(521L).edit { validationStatus = ValidationStatus.PENDING }
        val jobRepo = InMemoryGenerationJobRepository()
        val llmRepo = InMemoryLlmAnalysisRepository(invoice)
        val service = GenerationJobCollaborators(jobRepo, mock(), InMemoryInvoiceRepository(invoice))
        llmRepo.pendingRecord(invoice.id!!)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        job.edit { this.attempts = 5 }

        service.recoveryService.failExhaustedJob(job, maxAttempts = 5)

        assertEquals(ValidationStatus.UNAVAILABLE, invoice.validationStatus)
        assertEquals(LlmAnalysisStatus.FAILED, llmRepo.findByInvoiceId(invoice.id!!).single().status)
    }

    @Test
    fun `failExhaustedJob is a no-op returning false when the job is no longer PENDING`() {
        val invoice = validInvoice(522L)
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job) // no longer PENDING

        val result = service.recoveryService.failExhaustedJob(job, maxAttempts = 5)

        assertEquals(false, result)
        assertEquals(GenerationJobStatus.DELEGATED, jobRepo.findById(job.id!!)!!.status)
        verify(domainEventBus, never()).fireAsync(any())
    }

    @Test
    fun `failExhaustedJob returns false without an id`() {
        val (service) = makeService()
        val result = service.recoveryService.failExhaustedJob(GenerationJob(), maxAttempts = 5)
        assertEquals(false, result)
    }

    @Test
    fun `recordLastError writes the message onto the job`() {
        val invoice = validInvoice(523L)
        val (service, jobRepo) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        val result = service.recoveryService.recordLastError(job.id!!, "processing failed after 3 attempts: db down")

        assertTrue(result)
        assertEquals("processing failed after 3 attempts: db down", jobRepo.findById(job.id!!)!!.lastError)
    }

    @Test
    fun `recordLastError never throws even when the repository does`() {
        val repo = mock<GenerationJobRepository>()
        whenever(repo.recordLastError(any(), any())).thenThrow(RuntimeException("db down"))
        val service = GenerationJobCollaborators(repo, mock())

        var result = true
        assertDoesNotThrow { result = service.recoveryService.recordLastError(1L, "boom") }

        assertEquals(false, result)
    }

    @Test
    fun `markCompleted persists llmStatus into its own column alongside the resultRefs JSON`() {
        val invoice = validInvoice(524L)
        val (service, jobRepo) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)

        service.resultHandler.markCompleted(job, resultRefs = """{"llmStatus":"PENDING"}""", llmStatus = "PENDING")

        assertEquals("PENDING", jobRepo.findById(job.id!!)!!.llmStatus)
    }

    @Test
    fun `markCompleted leaves llmStatus untouched when none is given (pre-ARC-28 call sites)`() {
        val invoice = validInvoice(525L)
        val (service, jobRepo) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)

        service.resultHandler.markCompleted(job, resultRefs = "{}")

        assertEquals(null, jobRepo.findById(job.id!!)!!.llmStatus)
    }

    @Test
    fun `markFailed persists the worker-forwarded llmStatus into its own column`() {
        val invoice = validInvoice(526L)
        val (service, jobRepo) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        service.recoveryService.markFailed(job, message = "worker crashed", extraResultRefs = mapOf("llmStatus" to "FAILED"))

        assertEquals("FAILED", jobRepo.findById(job.id!!)!!.llmStatus)
    }

    @Test
    fun `mergeLlmResult persists the deferred callback's llmStatus into its own column`() {
        val invoice = validInvoice(527L)
        val (service, jobRepo) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        service.resultHandler.markCompleted(job, resultRefs = """{"llmStatus":"PENDING"}""", llmStatus = "PENDING")

        service.llmResultHandler.mergeLlmResult(job, mapOf("llmStatus" to "COMPLETED", "llmModel" to "qwen"))

        assertEquals("COMPLETED", jobRepo.findById(job.id!!)!!.llmStatus)
    }

    // ── branch-coverage completion: edge cases the scenarios above never happened to exercise ──

    @Test
    fun `createJob accepts an invoice that was never persisted, skipping the reload+validate path entirely`() {
        val invoice = anInvoice()
        val (service) = makeService()

        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        assertTrue(job.invoice === invoice, "an invoice without an id must be used exactly as passed in, never reloaded")
        assertEquals(GenerationJobStatus.PENDING, job.status)
    }

    @Test
    fun `markCompleted fires DocumentGenerated with generationJobId -1 when the saved job carries no id`() {
        // N5 — gates on invoice.status.isIssued now, see the markCompleted test above.
        val invoice = validInvoice(604L).also { it.forceStatusForTest(InvoiceStatus.ISSUED) }
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = object : GenerationJobRepository by InMemoryGenerationJobRepository() {
            override fun save(job: GenerationJob): GenerationJob = job
        }
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))
        val job = aGenerationJob {
            this.invoice = invoice
            jobType = "PDF_EXPORT"
            status = GenerationJobStatus.DELEGATED
        }

        service.resultHandler.markCompleted(job, "{}")

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fireAsync(eventCaptor.capture())
        assertEquals(-1L, (eventCaptor.firstValue as DocumentGenerated).generationJobId)
    }

    @Test
    fun `ARC-6 markCompleted preserves an already-skipped non-BUSINESS stage untouched while replacing BUSINESS`() {
        val invoice = validInvoice(710L)
        val (service) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        val stages = listOf(
            de.littletrotter.shared.ValidationStageResult("SYNTAX", de.littletrotter.shared.KositOutcome.VALID),
            de.littletrotter.shared.ValidationStageResult(
                "KOSIT", de.littletrotter.shared.KositOutcome.VALID,
                skipped = true, skipReason = "kotlin-xpdf worker skipped this stage",
            ),
            de.littletrotter.shared.ValidationStageResult(
                "BUSINESS", de.littletrotter.shared.KositOutcome.VALID,
                skipped = true, skipReason = "TAX-03/PARTY-01 noch nicht integriert",
            ),
        )
        val run = de.littletrotter.shared.ValidationRun(
            stages = stages,
            overall = de.littletrotter.shared.ValidationRun.overallOutcomeOf(stages),
            startedAt = "2026-08-22T00:00:00Z",
        )
        val report = de.littletrotter.shared.KositReportPayload.encode(
            de.littletrotter.shared.KositValidationResult(status = run.overall, validationRun = run)
        )

        service.publishingService.markDelegated(job)
        val completed = service.resultHandler.markCompleted(job, "{}", report)

        val payload = de.littletrotter.shared.KositReportPayload.decode(completed.kositReport)!!
        assertNotNull(payload.summary, "the real evaluation found findings — a flat summary text must be present")
        val mergedRun = payload.validationRun!!
        val businessStage = mergedRun.stages.single { it.stage == "BUSINESS" }
        assertEquals(false, businessStage.skipped)
        assertTrue(businessStage.findings.isNotEmpty())
        // KOSIT stays exactly as the worker sent it (still skipped) — the merge only ever
        // replaces BUSINESS, so the summary's "sum of non-skipped stages" must exclude it.
        assertTrue(mergedRun.stages.single { it.stage == "KOSIT" }.skipped)
    }

    @Test
    fun `mergeLlmResult leaves the job's llmStatus column untouched when the merged map carries no llmStatus key`() {
        val invoice = validInvoice(603L)
        val (service, jobRepo) = makeService(invoice)
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        service.resultHandler.markCompleted(job, resultRefs = """{"llmStatus":"PENDING"}""", llmStatus = "PENDING")

        val merged = service.llmResultHandler.mergeLlmResult(job, mapOf("llmReportKey" to "invoice/1-llm-report.md"))

        assertEquals("PENDING", jobRepo.findById(job.id!!)!!.llmStatus, "no llmStatus key in the merge map must not touch the durable column")
        val refs = com.fasterxml.jackson.databind.ObjectMapper().readValue(merged.resultRefs!!, Map::class.java)
        assertEquals("invoice/1-llm-report.md", refs["llmReportKey"])
    }

    @Test
    fun `markFailed with no message defaults resultRefs to null and the fired reason to a generic text`() {
        // N5 — gates on invoice.status.isIssued now, see the markCompleted test above.
        val invoice = validInvoice(605L).also { it.forceStatusForTest(InvoiceStatus.ISSUED) }
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        val failed = service.recoveryService.markFailed(job)

        assertEquals(null, failed.resultRefs, "no message and no extraResultRefs means nothing to store")
        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fireAsync(eventCaptor.capture())
        assertEquals("Unbekannter Fehler", (eventCaptor.firstValue as de.littletrotter.domain.event.GenerationJobFailed).reason)
    }

    @Test
    fun `markFailed fires GenerationJobFailed with generationJobId -1 when the saved job carries no id`() {
        // N5 — gates on invoice.status.isIssued now, see the markCompleted test above.
        val invoice = validInvoice(606L).also { it.forceStatusForTest(InvoiceStatus.ISSUED) }
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = object : GenerationJobRepository by InMemoryGenerationJobRepository() {
            override fun save(job: GenerationJob): GenerationJob = job
        }
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))
        val job = aGenerationJob {
            this.invoice = invoice
            jobType = "PDF_EXPORT"
            status = GenerationJobStatus.DELEGATED
        }

        service.recoveryService.markFailed(job, message = "boom")

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fireAsync(eventCaptor.capture())
        assertEquals(-1L, (eventCaptor.firstValue as de.littletrotter.domain.event.GenerationJobFailed).generationJobId)
    }

    @Test
    fun `updateLlmAnalysisFromResult treats a blank (non-null) llmStatus the same as a missing one`() {
        val invoice = validInvoice(612L)
        val (service, _, llmRepo) = makeServiceWithLlm(invoice)
        llmRepo.pendingRecord(invoice.id!!)

        service.llmResultHandler.updateLlmAnalysisFromResult(invoice.id, mapOf("llmStatus" to "   "))

        assertEquals(LlmAnalysisStatus.PENDING, llmRepo.findByInvoiceId(invoice.id!!).single().status)
    }

    @Test
    fun `updateLlmAnalysisFromResult falls back to the PENDING analysis when no llmModel key is given`() {
        val (service, _, llmRepo) = makeServiceWithLlm(validInvoice(900L))
        llmRepo.pendingRecord(900L) // model defaults to "oMLX"

        service.llmResultHandler.updateLlmAnalysisFromResult(900L, mapOf("llmStatus" to "COMPLETED"))

        assertEquals(LlmAnalysisStatus.COMPLETED, llmRepo.findByInvoiceIdAndModelName(900L, "oMLX")?.status)
    }

    @Test
    fun `updateLlmAnalysisFromResult FAILED without an llmError falls back to a generic issues text`() {
        val invoice = validInvoice(613L)
        val (service, _, llmRepo) = makeServiceWithLlm(invoice)
        llmRepo.pendingRecord(invoice.id!!, model = "oMLX")

        service.llmResultHandler.updateLlmAnalysisFromResult(invoice.id, mapOf("llmStatus" to "FAILED", "llmModel" to "oMLX"))

        assertEquals("LLM-Prüfung fehlgeschlagen", llmRepo.findByInvoiceIdAndModelName(invoice.id!!, "oMLX")?.issues)
    }

    @Test
    fun `updateLlmAnalysisFromResult summary omits duration when llmDurationMs is zero`() {
        val invoice = validInvoice(608L)
        val (service, _, llmRepo) = makeServiceWithLlm(invoice)
        llmRepo.pendingRecord(invoice.id!!, model = "oMLX")

        service.llmResultHandler.updateLlmAnalysisFromResult(invoice.id, mapOf("llmStatus" to "COMPLETED", "llmModel" to "oMLX", "llmDurationMs" to "0"))

        assertEquals("KI-Prüfung: fertig", llmRepo.findByInvoiceIdAndModelName(invoice.id!!, "oMLX")?.summary)
    }

    @Test
    fun `failPendingAnalysesForInvoice only touches PENDING records, leaving already-terminal ones alone`() {
        val invoice = validInvoice(607L)
        val (service, _, llmRepo) = makeServiceWithLlm(invoice)
        llmRepo.pendingRecord(invoice.id!!, model = "oMLX")
        val completed = llmRepo.pendingRecord(invoice.id!!, model = "qwen")
        completed.edit { this.status = LlmAnalysisStatus.COMPLETED }

        service.llmResultHandler.failPendingAnalysesForInvoice(invoice.id)

        assertEquals(LlmAnalysisStatus.FAILED, llmRepo.findByInvoiceIdAndModelName(invoice.id!!, "oMLX")?.status)
        assertEquals(LlmAnalysisStatus.COMPLETED, llmRepo.findByInvoiceIdAndModelName(invoice.id!!, "qwen")?.status)
    }

    @Test
    fun `failExhaustedJob fires no event when the invoice has no assigned invoice number yet`() {
        val invoice = validInvoice(609L).edit { invoiceNumber = InvoiceNumber.UNASSIGNED }
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice))
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        job.edit { this.attempts = 5 }

        val result = service.recoveryService.failExhaustedJob(job, maxAttempts = 5)

        assertTrue(result)
        verify(domainEventBus, never()).fireAsync(any())
    }

    @Test
    fun `resetStaleLlmPending skips a stale completed job that has no resultRefs at all`() {
        val invoice = validInvoice(610L)
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, mock(), InMemoryInvoiceRepository(invoice))
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        service.resultHandler.markCompleted(job) // no resultRefs given -> stays null
        job.edit { this.finishedAt = java.time.OffsetDateTime.now().minusMinutes(30) }
        jobRepo.save(job)

        val resolved = service.recoveryService.resetStaleLlmPending(staleCutoffMinutes = 15, lookbackHours = 24)

        assertEquals(0, resolved)
    }

    @Test
    fun `resetStaleLlmPending skips a stale completed job whose resultRefs is not parsable JSON`() {
        val invoice = validInvoice(611L)
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, mock(), InMemoryInvoiceRepository(invoice))
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        service.resultHandler.markCompleted(job, resultRefs = "not-json")
        job.edit { this.finishedAt = java.time.OffsetDateTime.now().minusMinutes(30) }
        jobRepo.save(job)

        val resolved = service.recoveryService.resetStaleLlmPending(staleCutoffMinutes = 15, lookbackHours = 24)

        assertEquals(0, resolved)
        assertEquals("not-json", jobRepo.findById(job.id!!)!!.resultRefs, "an unparsable report must be left untouched, not overwritten")
    }

    @Test
    fun `resetStaleLlmPending tolerates a stale job with no invoice at all`() {
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(jobRepo, mock(), InMemoryInvoiceRepository())
        val job = aGenerationJob {
            jobType = "PDF_EXPORT"
            status = GenerationJobStatus.COMPLETED
            resultRefs = """{"llmStatus":"PENDING"}"""
            finishedAt = java.time.OffsetDateTime.now().minusMinutes(30)
        }
        jobRepo.save(job)

        val resolved = service.recoveryService.resetStaleLlmPending(staleCutoffMinutes = 15, lookbackHours = 24)

        assertEquals(1, resolved)
        val refs = com.fasterxml.jackson.databind.ObjectMapper().readValue(jobRepo.findById(job.id!!)!!.resultRefs!!, Map::class.java)
        assertEquals("FAILED", refs["llmStatus"])
    }

    // ── fix-einvoice-status-stale-after-completion regression — publishAfterCommit
    // must defer every one of the five domain events this service fires until the firing method's
    // own transaction has actually committed, and drop them on rollback; see that helper's own
    // KDoc for the full incident. With no registry (every test above) or a registry reporting no
    // active transaction, it must keep firing immediately — proven directly below alongside the
    // deferred-commit and dropped-rollback cases, one per call site. ─────────────────────────────

    @Test
    fun `publishAfterCommit defers markCompleted's DocumentGenerated until commit, fires exactly once`() {
        // N5 — gates on invoice.status.isIssued now, see the markCompleted test above.
        val invoice = validInvoice(950L).also { it.forceStatusForTest(InvoiceStatus.ISSUED) }
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val tsr = FakeTransactionSynchronizationRegistry()
        val service = GenerationJobCollaborators(
            jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice),
            transactionSynchronizationRegistry = tsr,
        )
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)

        service.resultHandler.markCompleted(job, "{\"pdf\":\"obj\"}")
        verify(domainEventBus, never()).fireAsync(any())

        tsr.registered!!.afterCompletion(jakarta.transaction.Status.STATUS_COMMITTED)

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fireAsync(eventCaptor.capture())
        assertTrue(eventCaptor.firstValue is DocumentGenerated)
    }

    @Test
    fun `publishAfterCommit drops the event on rollback`() {
        // N5 — gates on invoice.status.isIssued now, see the markCompleted test above.
        val invoice = validInvoice(951L).also { it.forceStatusForTest(InvoiceStatus.ISSUED) }
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val tsr = FakeTransactionSynchronizationRegistry()
        val service = GenerationJobCollaborators(
            jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice),
            transactionSynchronizationRegistry = tsr,
        )
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)

        service.resultHandler.markCompleted(job, "{\"pdf\":\"obj\"}")
        tsr.registered!!.afterCompletion(jakarta.transaction.Status.STATUS_ROLLEDBACK)

        verify(domainEventBus, never()).fireAsync(any())
    }

    @Test
    fun `publishAfterCommit fires immediately when the registry reports no active transaction`() {
        // N5 — gates on invoice.status.isIssued now, see the markCompleted test above.
        val invoice = validInvoice(952L).also { it.forceStatusForTest(InvoiceStatus.ISSUED) }
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val tsr = FakeTransactionSynchronizationRegistry(jakarta.transaction.Status.STATUS_NO_TRANSACTION)
        val service = GenerationJobCollaborators(
            jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice),
            transactionSynchronizationRegistry = tsr,
        )
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)

        service.resultHandler.markCompleted(job, "{\"pdf\":\"obj\"}")

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fireAsync(eventCaptor.capture())
        assertTrue(eventCaptor.firstValue is DocumentGenerated)
        assertEquals(null, tsr.registered, "no Synchronization should have been registered")
    }

    @Test
    fun `publishAfterCommit defers markFailed's GenerationJobFailed until commit`() {
        // N5 — gates on invoice.status.isIssued now, see the markCompleted test above.
        val invoice = validInvoice(953L).also { it.forceStatusForTest(InvoiceStatus.ISSUED) }
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val tsr = FakeTransactionSynchronizationRegistry()
        val service = GenerationJobCollaborators(
            jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice),
            transactionSynchronizationRegistry = tsr,
        )
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

        service.recoveryService.markFailed(job, message = "worker crashed")
        verify(domainEventBus, never()).fireAsync(any())

        tsr.registered!!.afterCompletion(jakarta.transaction.Status.STATUS_COMMITTED)

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fireAsync(eventCaptor.capture())
        assertTrue(eventCaptor.firstValue is de.littletrotter.domain.event.GenerationJobFailed)
    }

    @Test
    fun `publishAfterCommit defers failExhaustedJob's GenerationJobExhausted until commit`() {
        // N5 — gates on invoice.status.isIssued now, see the markCompleted test above.
        val invoice = validInvoice(954L).also { it.forceStatusForTest(InvoiceStatus.ISSUED) }
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val tsr = FakeTransactionSynchronizationRegistry()
        val service = GenerationJobCollaborators(
            jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice),
            transactionSynchronizationRegistry = tsr,
        )
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        job.edit { this.attempts = 5 }

        service.recoveryService.failExhaustedJob(job, maxAttempts = 5)
        verify(domainEventBus, never()).fireAsync(any())

        tsr.registered!!.afterCompletion(jakarta.transaction.Status.STATUS_COMMITTED)

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fireAsync(eventCaptor.capture())
        assertTrue(eventCaptor.firstValue is de.littletrotter.domain.event.GenerationJobExhausted)
    }

    @Test
    fun `publishAfterCommit defers markResultProcessingFailed's GenerationJobResultProcessingFailed until commit`() {
        // N5 — gates on invoice.status.isIssued now, see the markCompleted test above.
        val invoice = validInvoice(955L).also { it.forceStatusForTest(InvoiceStatus.ISSUED) }
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val tsr = FakeTransactionSynchronizationRegistry()
        val service = GenerationJobCollaborators(
            jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice),
            transactionSynchronizationRegistry = tsr,
        )
        val job = service.creationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)

        service.recoveryService.markResultProcessingFailed(job.id!!, "processing failed after 3 attempts: db timeout")
        verify(domainEventBus, never()).fireAsync(any())

        tsr.registered!!.afterCompletion(jakarta.transaction.Status.STATUS_COMMITTED)

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fireAsync(eventCaptor.capture())
        assertTrue(eventCaptor.firstValue is de.littletrotter.domain.event.GenerationJobResultProcessingFailed)
    }

    @Test
    fun `publishAfterCommit defers updateLlmAnalysisFromResult's InvoiceLlmAnalysisResolved until commit`() {
        val invoice = validInvoice(956L)
        val domainEventBus: Event<DomainEvent> = mock()
        val jobRepo = InMemoryGenerationJobRepository()
        val llmRepo = InMemoryLlmAnalysisRepository(invoice)
        val tsr = FakeTransactionSynchronizationRegistry()
        val service = GenerationJobCollaborators(
            jobRepo, domainEventBus, InMemoryInvoiceRepository(invoice),
            transactionSynchronizationRegistry = tsr,
        )
        llmRepo.pendingRecord(invoice.id!!, model = "Qwen-mini")

        service.llmResultHandler.updateLlmAnalysisFromResult(invoice.id, mapOf("llmStatus" to "COMPLETED", "llmModel" to "Qwen-mini"))
        verify(domainEventBus, never()).fireAsync(any())

        tsr.registered!!.afterCompletion(jakarta.transaction.Status.STATUS_COMMITTED)

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(domainEventBus).fireAsync(eventCaptor.capture())
        assertTrue(eventCaptor.firstValue is de.littletrotter.domain.event.InvoiceLlmAnalysisResolved)
    }

    // ── Single-generation gate for issued documents ───────────────────────────────────
    // Full decision-table matrix (status x completed-job-exists), the cancellation document (384)
    // first-allowed/second-rejected case, "a failed job does not lock", and the VOIDED
    // regression (still the OLD exception, never the new one). ────────────────────────────────

    private fun completeAJob(service: GenerationJobCollaborators, invoice: Invoice): GenerationJob {
        val job = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        service.publishingService.markDelegated(job)
        return service.resultHandler.markCompleted(job)
    }

    @Test
    fun `RS-1 - DRAFT with no completed job allows the first job`() {
        val invoice = validInvoice(2000L)
        val (service, jobRepo) = makeService(invoice)

        val job = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)

        assertNotNull(job.id)
        assertEquals(1, jobRepo.findByInvoiceId(2000L).size)
    }

    @Test
    fun `RS-1 - DRAFT with a completed job still allows another job -- draft artifacts are not archives`() {
        val invoice = validInvoice(2001L)
        val (service, jobRepo) = makeService(invoice)
        completeAJob(service, invoice)

        val second = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)

        assertNotNull(second.id)
        assertEquals(2, jobRepo.findByInvoiceId(2001L).size)
    }

    @Test
    fun `RS-1 - IN_REVIEW with no completed job allows the first job`() {
        val invoice = validInvoice(2002L).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val (service) = makeService(invoice)

        val job = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)

        assertNotNull(job.id)
    }

    @Test
    fun `RS-1 - IN_REVIEW with a completed job still allows another job`() {
        val invoice = validInvoice(2003L).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val (service, jobRepo) = makeService(invoice)
        completeAJob(service, invoice)

        val second = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)

        assertNotNull(second.id)
        assertEquals(2, jobRepo.findByInvoiceId(2003L).size)
    }

    @Test
    fun `RS-1 - ISSUED with no completed job allows the first job -- this is how the final document gets produced`() {
        val invoice = validInvoice(2004L).apply { forceStatusForTest(InvoiceStatus.ISSUED) }
        val (service, jobRepo) = makeService(invoice)

        val job = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)

        assertNotNull(job.id)
        assertEquals(1, jobRepo.findByInvoiceId(2004L).size)
    }

    @Test
    fun `RS-1 - ISSUED rejects a second job once the first has completed`() {
        val invoice = validInvoice(2005L).apply { forceStatusForTest(InvoiceStatus.ISSUED) }
        val (service, jobRepo) = makeService(invoice)
        completeAJob(service, invoice)

        val ex = assertThrows<InvoiceImmutableGenerationBlockedException> {
            service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        }

        assertTrue(ex.message!!.contains(invoice.invoiceNumber.value))
        assertEquals(1, jobRepo.findByInvoiceId(2005L).size, "the rejected attempt must not persist a job")
    }

    @Test
    fun `RS-1 - CANCELLED original allows the first job -- legacy originals without an archive too`() {
        val invoice = validInvoice(2006L).apply { forceStatusForTest(InvoiceStatus.CANCELLED) }
        val (service, jobRepo) = makeService(invoice)

        val job = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)

        assertNotNull(job.id)
        assertEquals(1, jobRepo.findByInvoiceId(2006L).size)
    }

    @Test
    fun `RS-1 - CANCELLED original rejects a second job once the first has completed`() {
        val invoice = validInvoice(2007L).apply { forceStatusForTest(InvoiceStatus.CANCELLED) }
        val (service, jobRepo) = makeService(invoice)
        completeAJob(service, invoice)

        assertThrows<InvoiceImmutableGenerationBlockedException> {
            service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        }
        assertEquals(1, jobRepo.findByInvoiceId(2007L).size)
    }

    private fun cancellationDocument(id: Long, originalInvoiceNumber: String) =
        validInvoice(id).edit {
            forceStatusForTest(InvoiceStatus.CANCELLED)
            typeCode = de.littletrotter.domain.invoice.InvoiceTypeCode.CORRECTED_INVOICE
            this.originalInvoiceNumber = originalInvoiceNumber
        }

    @Test
    fun `RS-1 - cancellation document (384), born CANCELLED, allows its first generation job`() {
        val creditNote = cancellationDocument(2008L, "RE-2025-01-00123")
        val (service, jobRepo) = makeService(creditNote)

        val job = service.creationService.createJob(creditNote, "PRINT_JOB", DocumentJobLane.BULK)

        assertNotNull(job.id)
        assertEquals(1, jobRepo.findByInvoiceId(2008L).size)
    }

    @Test
    fun `RS-1 - cancellation document (384) rejects a second job once its first has completed`() {
        val creditNote = cancellationDocument(2009L, "RE-2025-01-00124")
        val (service, jobRepo) = makeService(creditNote)
        completeAJob(service, creditNote)

        val ex = assertThrows<InvoiceImmutableGenerationBlockedException> {
            service.creationService.createJob(creditNote, "PRINT_JOB", DocumentJobLane.BULK)
        }

        assertTrue(ex.message!!.contains(creditNote.invoiceNumber.value))
        assertEquals(1, jobRepo.findByInvoiceId(2009L).size)
    }

    @Test
    fun `RS-1 - a failed job does not lock -- a later job is allowed and then locks after completion`() {
        val invoice = validInvoice(2010L).apply { forceStatusForTest(InvoiceStatus.ISSUED) }
        val (service, jobRepo) = makeService(invoice)
        val first = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        service.recoveryService.markFailed(first, message = "worker crashed")

        val second = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        assertNotNull(second.id, "a failed first attempt must never lock out a retry")

        service.publishingService.markDelegated(second)
        service.resultHandler.markCompleted(second)

        assertThrows<InvoiceImmutableGenerationBlockedException> {
            service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        }
        assertEquals(2, jobRepo.findByInvoiceId(2010L).size, "only the two real attempts, the rejected third never persists")
    }

    @Test
    fun `RS-1 - VOIDED still throws InvoiceVoidedGenerationBlockedException, not the new immutable one, even after a prior completed job`() {
        // A voided invoice can only be reached from DRAFT/IN_REVIEW (InvoiceStateMachine), so this
        // reconstructs the one realistic path to "VOIDED with an already-completed job": a draft
        // gets (re)generated at least once before someone decides to void it outright.
        val invoice = validInvoice(2011L)
        val (service) = makeService(invoice)
        completeAJob(service, invoice)
        invoice.forceStatusForTest(InvoiceStatus.VOIDED)

        val ex = assertThrows<InvoiceVoidedGenerationBlockedException> {
            service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        }

        assertTrue(ex.message!!.contains(invoice.invoiceNumber.value))
    }

    @Test
    fun `RS-1 - VOIDED without any prior job still throws InvoiceVoidedGenerationBlockedException`() {
        val invoice = validInvoice(2012L).apply { forceStatusForTest(InvoiceStatus.VOIDED) }
        val (service) = makeService(invoice)

        assertThrows<InvoiceVoidedGenerationBlockedException> {
            service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        }
    }

    // ── GenerationJob.issued: a review-time draft job never locks a later ISSUED ──

    @Test
    fun `N13 - createJob sets issued=false for a job created while the invoice is still DRAFT`() {
        val invoice = validInvoice(2013L)
        val (service) = makeService(invoice)

        val job = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)

        assertFalse(job.issued, "a DRAFT invoice is not issued yet")
    }

    @Test
    fun `N13 - createJob sets issued=true for a job created on an already-ISSUED invoice`() {
        val invoice = validInvoice(2014L).apply { forceStatusForTest(InvoiceStatus.ISSUED) }
        val (service) = makeService(invoice)

        val job = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)

        assertTrue(job.issued)
    }

    @Test
    fun `N13 - a completed review-time draft job does not lock the invoice after it is sent -- only the first issued job locks`() {
        val invoice = validInvoice(2015L)
        val (service, jobRepo) = makeService(invoice)

        // Review-time draft job: completed while the invoice is still DRAFT/IN_REVIEW.
        val draftJob = completeAJob(service, invoice)
        assertFalse(draftJob.issued, "the review-time draft job must not be marked issued")

        // The invoice is sent afterward -- the completed draft job must not lock generation.
        invoice.forceStatusForTest(InvoiceStatus.ISSUED)
        val issuedJob = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        assertNotNull(issuedJob.id, "a completed draft job from review time must never lock the invoice once sent")

        service.publishingService.markDelegated(issuedJob)
        val completedIssuedJob = service.resultHandler.markCompleted(issuedJob)
        assertTrue(completedIssuedJob.issued, "a job created on the already-ISSUED invoice must be issued=true")

        // Now the first ISSUED job is completed -- a further attempt must be rejected.
        assertThrows<InvoiceImmutableGenerationBlockedException> {
            service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        }
        assertEquals(2, jobRepo.findByInvoiceId(2015L).size, "the draft job plus the one issued job -- the rejected third never persists")
    }

    // ── PO decision 2026-09-13 — createJob(trigger = SEND) is always the issued document,
    // regardless of the invoice's status at creation (no intermediate InvoiceStatus, so a
    // Send-triggered job is created while the invoice is still IN_REVIEW). ─────────────────────

    @Test
    fun `SEND - createJob sets issued=true even though the invoice is still IN_REVIEW`() {
        val invoice = validInvoice(3001L).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val (service) = makeService(invoice)

        val job = service.creationService.createJob(invoice, "SEND_ISSUE", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)

        assertTrue(job.issued, "a SEND-triggered job must always become the issued document, D1/D3")
        assertEquals(de.littletrotter.domain.invoice.GenerationJobTrigger.SEND, job.trigger)
        assertEquals(InvoiceStatus.IN_REVIEW, invoice.status, "createJob itself never changes the invoice's status")
    }

    @Test
    fun `SEND - a completed SEND job then locks any further generation via RS-1, same as any other issued job`() {
        val invoice = validInvoice(3002L).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val (service) = makeService(invoice)

        val sendJob = service.creationService.createJob(invoice, "SEND_ISSUE", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)
        service.publishingService.markDelegated(sendJob)
        service.resultHandler.markCompleted(sendJob)

        // Even though the invoice never actually reached ISSUED in this unit test (that transition
        // is SendTransitionRunner's own job, exercised at the IT level), the completed,
        // issued=true job already blocks further generation once the invoice IS issued.
        invoice.forceStatusForTest(InvoiceStatus.ISSUED)
        assertThrows<InvoiceImmutableGenerationBlockedException> {
            service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        }
    }

    @Test
    fun `createJob without trigger defaults to MANUAL, unchanged from every pre-existing call site`() {
        val invoice = validInvoice(3003L)
        val (service) = makeService(invoice)

        val job = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)

        assertEquals(de.littletrotter.domain.invoice.GenerationJobTrigger.MANUAL, job.trigger)
    }

    // ── "Guard against concurrent/duplicate sends" (PO decision 2026-09-13) ────────────────────

    @Test
    fun `SEND - a second concurrent Send click is rejected while the first SEND job is still non-terminal`() {
        val invoice = validInvoice(3010L).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val (service, jobRepo) = makeService(invoice)
        service.creationService.createJob(invoice, "SEND_ISSUE", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)

        val ex = assertThrows<de.littletrotter.domain.exception.InvoiceSendAlreadyInProgressException> {
            service.creationService.createJob(invoice, "SEND_ISSUE", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)
        }

        assertTrue(ex.message!!.contains(invoice.invoiceNumber.value))
        assertEquals(1, jobRepo.findByInvoiceId(3010L).size, "the rejected second SEND job never persists")
    }

    @Test
    fun `SEND - a retry after the first SEND job reaches a terminal state is allowed`() {
        val invoice = validInvoice(3011L).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val (service, jobRepo) = makeService(invoice)
        val firstJob = service.creationService.createJob(invoice, "SEND_ISSUE", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)
        service.publishingService.markDelegated(firstJob)
        service.recoveryService.markFailed(firstJob, message = "worker crashed")

        val retryJob = service.creationService.createJob(invoice, "SEND_ISSUE", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)

        assertNotNull(retryJob.id)
        assertEquals(2, jobRepo.findByInvoiceId(3011L).size)
    }

    @Test
    fun `SEND - the concurrent-send guard does not block a plain MANUAL job while a SEND job is in flight`() {
        val invoice = validInvoice(3012L).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val (service) = makeService(invoice)
        service.creationService.createJob(invoice, "SEND_ISSUE", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)

        val manualJob = service.creationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)

        assertNotNull(manualJob.id)
    }

    @Test
    fun `SEND - the concurrent-send guard does not block a SEND job for a different invoice`() {
        val invoiceA = validInvoice(3013L).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val invoiceB = validInvoice(3014L).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val (service) = makeService(invoiceA, invoiceB)
        service.creationService.createJob(invoiceA, "SEND_ISSUE", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)

        val jobB = service.creationService.createJob(invoiceB, "SEND_ISSUE", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)

        assertNotNull(jobB.id)
    }

    // ── PO decision 2026-09-13 — resolveSendOutcome, the trigger-driven completion path ────────

    private fun makeServiceWithSendCollaborators(
        vararg invoices: Invoice,
    ): Triple<GenerationJobCollaborators, InMemoryGenerationJobRepository, de.littletrotter.application.invoice.SendTransitionRunner> {
        val jobRepo = InMemoryGenerationJobRepository()
        val sendTransitionRunner = mock<de.littletrotter.application.invoice.SendTransitionRunner>()
        val messageCenterService = mock<de.littletrotter.application.message.MessageCenterService>()
        val service = GenerationJobCollaborators(
            generationJobRepository = jobRepo,
            domainEventBus = mock(),
            invoiceRepository = InMemoryInvoiceRepository(*invoices),
            sendTransitionRunner = sendTransitionRunner,
            messageCenterService = messageCenterService,
        )
        return Triple(service, jobRepo, sendTransitionRunner)
    }

    private fun kositReportWithStatus(status: de.littletrotter.shared.KositOutcome): String =
        de.littletrotter.shared.KositReportPayload.encode(
            de.littletrotter.shared.KositValidationResult(status = status, details = emptyList()),
        )

    @Test
    fun `resolveSendOutcome calls transition to ISSUED when the completed job's report is VALID`() {
        val invoice = validInvoice(4001L).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val (service, _, sendTransitionRunner) = makeServiceWithSendCollaborators(invoice)
        val job = service.creationService.createJob(invoice, "SEND_ISSUE", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)
        service.publishingService.markDelegated(job)
        val completed = service.resultHandler.markCompleted(job, kositReport = kositReportWithStatus(de.littletrotter.shared.KositOutcome.VALID))

        service.resultHandler.resolveSendOutcome(completed)

        verify(sendTransitionRunner).transitionToSent(4001L)
    }

    @Test
    fun `resolveSendOutcome does not transition and records a message when validation is INVALID`() {
        val invoice = validInvoice(4002L).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val jobRepo = InMemoryGenerationJobRepository()
        val sendTransitionRunner = mock<de.littletrotter.application.invoice.SendTransitionRunner>()
        val messageCenterService = mock<de.littletrotter.application.message.MessageCenterService>()
        val service = GenerationJobCollaborators(
            generationJobRepository = jobRepo,
            domainEventBus = mock(),
            invoiceRepository = InMemoryInvoiceRepository(invoice),
            sendTransitionRunner = sendTransitionRunner,
            messageCenterService = messageCenterService,
        )
        val job = service.creationService.createJob(invoice, "SEND_ISSUE", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)
        service.publishingService.markDelegated(job)
        val completed = service.resultHandler.markCompleted(job, kositReport = kositReportWithStatus(de.littletrotter.shared.KositOutcome.INVALID))

        service.resultHandler.resolveSendOutcome(completed)

        verify(sendTransitionRunner, never()).transitionToSent(any())
        verify(messageCenterService).record(
            SystemMessageContent(
                severity = de.littletrotter.domain.message.SystemMessageSeverity.ERROR,
                source = de.littletrotter.domain.message.SystemMessageSource.GENERATION,
                messageKey = "message.center.send.validationFailed",
                args = listOf(invoice.invoiceNumber.value),
                resolvedText = "Rechnung ${invoice.invoiceNumber.value}: Sendevorgang abgebrochen — E-Rechnungs-Prüfung nicht bestanden.",
                refType = "INVOICE",
                refId = "4002",
            ),
        )
    }

    @Test
    fun `resolveSendOutcome does not transition and records a message when no decodable report exists (UNAVAILABLE)`() {
        val invoice = validInvoice(4003L).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val (service, _, sendTransitionRunner) = makeServiceWithSendCollaborators(invoice)
        val job = service.creationService.createJob(invoice, "SEND_ISSUE", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)
        service.publishingService.markDelegated(job)
        val completed = service.resultHandler.markCompleted(job, kositReport = null)

        service.resultHandler.resolveSendOutcome(completed)

        verify(sendTransitionRunner, never()).transitionToSent(any())
    }

    @Test
    fun `resolveSendOutcome records a generationFailed message and attempts no transition when the job itself FAILED`() {
        val invoice = validInvoice(4004L).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val jobRepo = InMemoryGenerationJobRepository()
        val sendTransitionRunner = mock<de.littletrotter.application.invoice.SendTransitionRunner>()
        val messageCenterService = mock<de.littletrotter.application.message.MessageCenterService>()
        val service = GenerationJobCollaborators(
            generationJobRepository = jobRepo,
            domainEventBus = mock(),
            invoiceRepository = InMemoryInvoiceRepository(invoice),
            sendTransitionRunner = sendTransitionRunner,
            messageCenterService = messageCenterService,
        )
        val job = service.creationService.createJob(invoice, "SEND_ISSUE", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)
        service.publishingService.markDelegated(job)
        val failed = service.recoveryService.markFailed(job, message = "worker crashed")

        service.resultHandler.resolveSendOutcome(failed)

        verify(sendTransitionRunner, never()).transitionToSent(any())
        verify(messageCenterService).record(
            SystemMessageContent(
                severity = de.littletrotter.domain.message.SystemMessageSeverity.ERROR,
                source = de.littletrotter.domain.message.SystemMessageSource.GENERATION,
                messageKey = "message.center.send.generationFailed",
                args = listOf(invoice.invoiceNumber.value, "worker crashed"),
                resolvedText = "Rechnung ${invoice.invoiceNumber.value}: Sendevorgang abgebrochen — E-Rechnungs-Erzeugung fehlgeschlagen: worker crashed.",
                refType = "INVOICE",
                refId = "4004",
            ),
        )
    }

    // extractFailureReason's JsonProcessingException catch had no coverage: markFailed
    // itself always writes valid {"error": ...} JSON, so resultRefs is corrupted after the fact
    // to simulate an unparseable value (e.g. a legacy/foreign row).
    @Test
    fun `boundary - resolveSendOutcome falls back to unbekannter Fehler when resultRefs is not decodable JSON`() {
        val invoice = validInvoice(4008L).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val jobRepo = InMemoryGenerationJobRepository()
        val sendTransitionRunner = mock<de.littletrotter.application.invoice.SendTransitionRunner>()
        val messageCenterService = mock<de.littletrotter.application.message.MessageCenterService>()
        val service = GenerationJobCollaborators(
            generationJobRepository = jobRepo,
            domainEventBus = mock(),
            invoiceRepository = InMemoryInvoiceRepository(invoice),
            sendTransitionRunner = sendTransitionRunner,
            messageCenterService = messageCenterService,
        )
        val job = service.creationService.createJob(invoice, "SEND_ISSUE", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)
        service.publishingService.markDelegated(job)
        val failed = service.recoveryService.markFailed(job, message = "worker crashed")
        failed.edit { this.resultRefs = "not-json-at-all" }

        service.resultHandler.resolveSendOutcome(failed)

        verify(messageCenterService).record(
            SystemMessageContent(
                severity = de.littletrotter.domain.message.SystemMessageSeverity.ERROR,
                source = de.littletrotter.domain.message.SystemMessageSource.GENERATION,
                messageKey = "message.center.send.generationFailed",
                args = listOf(invoice.invoiceNumber.value, "unbekannter Fehler"),
                resolvedText = "Rechnung ${invoice.invoiceNumber.value}: Sendevorgang abgebrochen — E-Rechnungs-Erzeugung fehlgeschlagen: unbekannter Fehler.",
                refType = "INVOICE",
                refId = "4008",
            ),
        )
    }

    // The OTHER elvis fallback on the same line: extractFailureReason's own `resultRefs
    // ?: return@let` null branch (a FAILED job markFailed left with no message at all, so
    // resultRefs stays null) falling through to the job's own lastError column.
    @Test
    fun `boundary - resolveSendOutcome falls back to lastError when resultRefs carries no error message`() {
        val invoice = validInvoice(4009L).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val jobRepo = InMemoryGenerationJobRepository()
        val sendTransitionRunner = mock<de.littletrotter.application.invoice.SendTransitionRunner>()
        val messageCenterService = mock<de.littletrotter.application.message.MessageCenterService>()
        val service = GenerationJobCollaborators(
            generationJobRepository = jobRepo,
            domainEventBus = mock(),
            invoiceRepository = InMemoryInvoiceRepository(invoice),
            sendTransitionRunner = sendTransitionRunner,
            messageCenterService = messageCenterService,
        )
        val job = service.creationService.createJob(invoice, "SEND_ISSUE", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)
        service.publishingService.markDelegated(job)
        val failed = service.recoveryService.markFailed(job)
        failed.edit { this.lastError = "worker process killed (OOM)" }

        service.resultHandler.resolveSendOutcome(failed)

        verify(messageCenterService).record(
            SystemMessageContent(
                severity = de.littletrotter.domain.message.SystemMessageSeverity.ERROR,
                source = de.littletrotter.domain.message.SystemMessageSource.GENERATION,
                messageKey = "message.center.send.generationFailed",
                args = listOf(invoice.invoiceNumber.value, "worker process killed (OOM)"),
                resolvedText = "Rechnung ${invoice.invoiceNumber.value}: Sendevorgang abgebrochen — E-Rechnungs-Erzeugung fehlgeschlagen: worker process killed (OOM).",
                refType = "INVOICE",
                refId = "4009",
            ),
        )
    }

    @Test
    fun `resolveSendOutcome does nothing for a job without an invoice`() {
        val (service) = makeServiceWithSendCollaborators()
        val job = aGenerationJob {
            jobType = "SEND_ISSUE"
            trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND
            status = GenerationJobStatus.COMPLETED
        }

        assertDoesNotThrow { service.resultHandler.resolveSendOutcome(job) }
    }

    // The sibling `invoice.id ?: return` short-circuit right below the `invoice ?:
    // return` one above: an attached [Invoice] that itself has no id yet.
    @Test
    fun `boundary - resolveSendOutcome does nothing when the job's invoice has no id`() {
        val (service) = makeServiceWithSendCollaborators()
        val job = aGenerationJob {
            jobType = "SEND_ISSUE"
            trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND
            status = GenerationJobStatus.COMPLETED
            invoice = anInvoice()
        }

        assertDoesNotThrow { service.resultHandler.resolveSendOutcome(job) }
    }

    // resolveSendOutcome's own outer try/catch and both collaborator
    // requireNotNull guards had no direct coverage: every other resolveSendOutcome test wires
    // both SendTransitionRunner and MessageCenterService, so doResolveSendOutcome never threw.
    @Test
    fun `boundary - resolveSendOutcome logs and swallows the failure when SendTransitionRunner is not wired`() {
        val invoice = validInvoice(4005L).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val jobRepo = InMemoryGenerationJobRepository()
        val service = GenerationJobCollaborators(
            generationJobRepository = jobRepo,
            domainEventBus = mock(),
            invoiceRepository = InMemoryInvoiceRepository(invoice),
            sendTransitionRunner = null,
            messageCenterService = mock(),
        )
        val job = service.creationService.createJob(invoice, "SEND_ISSUE", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)
        service.publishingService.markDelegated(job)
        val completed = service.resultHandler.markCompleted(job, kositReport = kositReportWithStatus(de.littletrotter.shared.KositOutcome.VALID))

        assertDoesNotThrow { service.resultHandler.resolveSendOutcome(completed) }
    }

    @Test
    fun `boundary - resolveSendOutcome logs and swallows the failure when MessageCenterService is not wired`() {
        val invoice = validInvoice(4006L).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val jobRepo = InMemoryGenerationJobRepository()
        val sendTransitionRunner = mock<de.littletrotter.application.invoice.SendTransitionRunner>()
        val service = GenerationJobCollaborators(
            generationJobRepository = jobRepo,
            domainEventBus = mock(),
            invoiceRepository = InMemoryInvoiceRepository(invoice),
            sendTransitionRunner = sendTransitionRunner,
            messageCenterService = null,
        )
        val job = service.creationService.createJob(invoice, "SEND_ISSUE", DocumentJobLane.BULK, trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)
        service.publishingService.markDelegated(job)
        val completed = service.resultHandler.markCompleted(job, kositReport = kositReportWithStatus(de.littletrotter.shared.KositOutcome.INVALID))

        assertDoesNotThrow { service.resultHandler.resolveSendOutcome(completed) }
    }

    // The "gate throws after job COMPLETED" edge case (transition() itself throwing for a
    // VALID-validated invoice) needs a real Narayana transaction manager
    // (QuarkusTransaction.requiringNew, used to record the message-center entry in a fresh
    // transaction once transition()'s own failure has marked resolveSendOutcome's ambient one
    // rollback-only) — covered at the IT level (SendOutcomeGateFailureIT), not here; a plain
    // JUnit unit test has no active Arc/Narayana container for that call to run against.
}
