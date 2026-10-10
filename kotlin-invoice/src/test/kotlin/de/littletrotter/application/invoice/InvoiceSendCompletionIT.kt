package de.littletrotter.application.invoice

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.masterdata.SellerDefaultSelections
import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.domain.exception.InvoiceImmutableGenerationBlockedException
import de.littletrotter.application.masterdata.CustomerManagementService
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.message.SystemMessageFilter
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.SystemMessageRepository
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * PO decision 2026-09-13 — the real, end-to-end proof of the "Recommended completion path"
 * (trigger-driven, no poller): [GenerationJobResultHandler.resolveSendOutcome], called exactly the way
 * [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer] calls it (a plain,
 * sequential continuation right after `markCompleted`/`markFailed`), against a real Postgres and a
 * real [InvoiceLifecycleService.transition] — no mocks on the collaborators the unit tests in
 * `GenerationJobServiceTest` cannot safely exercise (a real, committed `SENT` transition; the
 * "gate throws after job COMPLETED" edge case, which needs a real JTA transaction manager for
 * [GenerationJobResultHandler]'s own `QuarkusTransaction.requiringNew` fallback to run against at all).
 *
 * Fixture recipe (sender/recipient/item shape) mirrors [de.littletrotter.application.masterdata
 * .MasterDataSnapshotsViesRegistrationsIT]'s own `createValidSenderProfile`/`createValidRecipient`/
 * `createMinimalItem` — a known-good minimal invoice that already reaches `SENT` for real in that
 * suite.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceSendCompletionIT {

    companion object {
        private val uniqueCounter = AtomicInteger(0)
    }

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var invoiceLifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var invoiceSendService: InvoiceSendService

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobPublishingService: GenerationJobPublishingService

    @Inject
    lateinit var generationJobResultHandler: GenerationJobResultHandler

    @Inject
    lateinit var generationJobRecoveryService: GenerationJobRecoveryService

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var changeInvoiceStatus: ChangeInvoiceStatus

    @Inject
    lateinit var invoiceStornoService: InvoiceStornoService

    @Inject
    lateinit var issuedDocumentJobSweep: IssuedDocumentJobSweep

    @Inject
    lateinit var generationJobRepository: de.littletrotter.domain.repository.GenerationJobRepository

    @Inject
    lateinit var sellerProfileService: SellerProfileService

    @Inject
    lateinit var customerManagementService: CustomerManagementService

    @Inject
    lateinit var messageCenterService: MessageCenterService

    @Inject
    lateinit var systemMessageRepository: SystemMessageRepository

    private fun createValidSenderProfile(name: String): SenderProfile = SenderProfile().apply {
        this.name = name
        invoicePrefix = "SCI${uniqueCounter.incrementAndGet()}-"
        email = Email("sender${System.nanoTime()}@example.invalid")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban("DE89370400440532013000")
        registerCourt = "Amtsgericht Test"
        phone = "+49 30 1234567"
    }.also { sellerProfileService.save(it, defaults = SellerDefaultSelections()) }

    private fun createValidRecipient(name: String): Recipient = Recipient().apply {
        this.name = name
        email = Email("$name@example.invalid".replace(" ", "").lowercase())
        customerType = CustomerType.CONSUMER
        vatId = VatId("DE987654321")
    }.also { customerManagementService.save(it) }

    /** [de.littletrotter.domain.invoice.InvoiceItem.effectiveTaxRate] requires either a
     *  frozen snapshot or a live [de.littletrotter.domain.invoice.InvoiceItem.taxRateRef]; a fresh
     *  [de.littletrotter.domain.reference.TaxRate] fixture (own EM persist, same recipe
     *  [de.littletrotter.application.invoice.GenerationJobServiceIT] uses) covers both this and
     *  [GenerationJobCreationService.createJob]'s own `recalculateTotals()` call. */
    private fun createMinimalItem(tag: String): de.littletrotter.domain.invoice.InvoiceItem {
        val taxRate = TaxRate().apply {
            name = "19%"
            rate = TaxRatePercent(BigDecimal("19.00"))
            code = "SC_TEST_19_$tag"
            category = "S"
            isSelectable = false
        }
        em.persist(taxRate)
        em.flush()
        return de.littletrotter.domain.invoice.InvoiceItem().apply {
            description = "Test Item"
            quantity = BigDecimal("1.0")
            unitPriceNet = Money.of("100.00")
            taxRateRef = taxRate
        }
    }

    /** Builds and persists a sendable (IN_REVIEW) invoice via the real application services, the
     *  same recipe [de.littletrotter.application.masterdata.MasterDataSnapshotsViesRegistrationsIT]
     *  uses. Returns its id. */
    private fun createSendableInvoice(tag: String): Long = QuarkusTransaction.requiringNew().call<Long> {
        val sender = createValidSenderProfile("ACME $tag")
        val recipient = createValidRecipient("Kunde $tag")
        val invoice = invoiceLifecycleService.create(sender.id!!, recipient.id!!)
        invoice.references.buyerReference = "BUYER-REF-$tag"
        invoice.addItem(createMinimalItem(tag))
        invoice.deliveryDate = java.time.LocalDate.now()
        invoiceRepository.saveAndFlush(invoice)
        invoiceLifecycleService.transition(invoice.id!!, InvoiceStatus.IN_REVIEW)
        invoice.id!!
    }

    private fun validKositReport(): String = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))
    private fun invalidKositReport(): String = KositReportPayload.encode(
        KositValidationResult(status = KositOutcome.INVALID, details = emptyList()),
    )

    private fun sendMessages(): List<de.littletrotter.domain.message.SystemMessage> =
        QuarkusTransaction.requiringNew().call<List<de.littletrotter.domain.message.SystemMessage>> {
            systemMessageRepository.find(userId = "system", filter = SystemMessageFilter(source = setOf(SystemMessageSource.GENERATION)), pageSize = 200)
        }

    /**
     * Bug production (this task) — every post-condition read below goes through a brand-new
     * [QuarkusTransaction.requiringNew] + a direct `em.find`, never the plain-injected
     * [invoiceRepository] from the test method's own (non-transactional) scope: Hibernate's
     * first-level cache returns the SAME managed Java object for a given id within one
     * persistence context, so an EARLIER read of the (still `IN_REVIEW`) invoice earlier in a test
     * method — before the real, separately-committed [GenerationJobResultHandler.resolveSendOutcome]
     * transaction ran — would otherwise keep being handed back unrefreshed, masking the real,
     * correctly-committed `SENT` write with stale in-memory state. Same root cause (and same fix)
     * as the L2/L25 staleness incidents this module's own KDoc documents elsewhere.
     */
    private fun freshInvoice(invoiceId: Long): de.littletrotter.domain.invoice.Invoice =
        QuarkusTransaction.requiringNew().call<de.littletrotter.domain.invoice.Invoice> {
            em.find(de.littletrotter.domain.invoice.Invoice::class.java, invoiceId)
        }

    @Test
    fun `requestSend then a VALID completion transitions the invoice all the way to SENT, and the sent state blocks further generation`() {
        val invoiceId = createSendableInvoice("SC1")

        val job = invoiceSendService.requestSend(invoiceId)
        assertEquals(GenerationJobTrigger.SEND, job.trigger)
        assertTrue(job.issued, "a SEND job is always the issued document (D1/D3)")
        assertEquals(InvoiceStatus.IN_REVIEW, freshInvoice(invoiceId).status, "requestSend itself never changes the invoice's status")

        generationJobPublishingService.markDelegated(job)
        val completed = generationJobResultHandler.markCompleted(job, resultRefs = "{}", kositReport = validKositReport())
        generationJobResultHandler.resolveSendOutcome(completed)

        val sent = freshInvoice(invoiceId)
        assertEquals(InvoiceStatus.SENT, sent.status)
        assertTrue(sent.invoiceNumber.isAssigned(), "transition's own numbering defense-in-depth must have run")
        assertNotNull(sent.paymentTermSnapshot, "transition's own payment-term freeze must have run")

        // The completed, issued=true SEND job, now that the invoice itself is SENT, locks
        // any further generation (see GenerationJobRepository.hasCompletedIssuedJob's own KDoc:
        // the gate needs BOTH the invoice's own issued status AND a completed issued job).
        assertThrows(InvoiceImmutableGenerationBlockedException::class.java) {
            generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK)
        }
    }

    @Test
    fun `an INVALID completion leaves the invoice IN_REVIEW and records a message-center entry`() {
        val invoiceId = createSendableInvoice("SC2")
        val job = invoiceSendService.requestSend(invoiceId)
        generationJobPublishingService.markDelegated(job)
        val completed = generationJobResultHandler.markCompleted(job, resultRefs = "{}", kositReport = invalidKositReport())

        generationJobResultHandler.resolveSendOutcome(completed)

        assertEquals(InvoiceStatus.IN_REVIEW, freshInvoice(invoiceId).status)
        val messages = sendMessages()
        assertTrue(
            messages.any { it.messageKey == "message.center.send.validationFailed" },
            "expected a validationFailed message-center entry, got: ${messages.map { it.messageKey }}",
        )

        // A retry always creates a fresh SEND job and can still succeed.
        val retryJob = invoiceSendService.requestSend(invoiceId)
        assertNotNull(retryJob.id)
    }

    /**
     * PO decision 2026-09-14 (addendum) — regression check, not new gate logic: an invoice whose
     * SEND job resolves UNAVAILABLE (an absent/undecodable report, unreachable/misconfigured
     * validation tooling -- not a data defect) must still be reachable via the pre-existing,
     * unchanged [InvoiceLifecycleService.transition] `overrideReason` parameter, exactly as it
     * worked before this feature existed. This is the mechanism
     * [InvoiceDetailViewCockpit.buildOverrideSendActionButtonOrNull]'s UI button drives.
     */
    @Test
    fun `an UNAVAILABLE completion can still reach SENT via the pre-existing transition overrideReason, unchanged`() {
        val invoiceId = createSendableInvoice("SC5")
        val job = invoiceSendService.requestSend(invoiceId)
        generationJobPublishingService.markDelegated(job)
        // An absent/undecodable report -- KositReportPayload.decode returns null -- is exactly
        // what resolveValidationStatusFromKositReport resolves to UNAVAILABLE.
        val completed = generationJobResultHandler.markCompleted(job, resultRefs = "{}", kositReport = null)

        generationJobResultHandler.resolveSendOutcome(completed)
        assertEquals(InvoiceStatus.IN_REVIEW, freshInvoice(invoiceId).status, "UNAVAILABLE alone must not transition")

        QuarkusTransaction.requiringNew().run {
            invoiceLifecycleService.transition(invoiceId, InvoiceStatus.SENT, "Validator war zum Zeitpunkt des Versands nicht erreichbar")
        }

        val sent = freshInvoice(invoiceId)
        assertEquals(InvoiceStatus.SENT, sent.status, "the pre-existing override path must still work, unchanged")
        assertTrue(sent.invoiceNumber.isAssigned())
        // No new generation job is created for the override -- the already-COMPLETED, issued=true
        // SEND job that resolved UNAVAILABLE remains the invoice's issued document.
        assertThrows(InvoiceImmutableGenerationBlockedException::class.java) {
            generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK)
        }
    }

    @Test
    fun `a job that FAILED outright records a generationFailed message and attempts no transition`() {
        val invoiceId = createSendableInvoice("SC3")
        val job = invoiceSendService.requestSend(invoiceId)
        generationJobPublishingService.markDelegated(job)
        val failed = generationJobRecoveryService.markFailed(job, message = "IT-simulated worker crash")

        generationJobResultHandler.resolveSendOutcome(failed)

        assertEquals(InvoiceStatus.IN_REVIEW, freshInvoice(invoiceId).status)
        val messages = sendMessages()
        assertTrue(
            messages.any { it.messageKey == "message.center.send.generationFailed" && it.resolvedText.contains("IT-simulated worker crash") },
            "expected a generationFailed message-center entry naming the worker's own reason, got: ${messages.map { it.resolvedText }}",
        )
    }

    /**
     * "Gate throws after job COMPLETED" edge case — the spec's own open PO decision. Forces
     * [InvoiceLifecycleService.transition] to throw for an otherwise VALID-validated invoice by
     * clearing the buyer reference (BT-10) between job completion and [resolveSendOutcome] — a
     * structural defect [de.littletrotter.domain.event.DocumentGenerated]'s own validation pass
     * never covers. Asserts this is loud (a message-center entry, verified below — the
     * accompanying `error`-level log line is confirmed by direct inspection of a real run, not
     * independently asserted here without a log-capture harness) and that the invoice never
     * reaches `SENT`.
     *
     * PO-visible finding (empirically verified against real Postgres while building this test,
     * not merely assumed): `GenerationJobRepository.hasCompletedIssuedJob`'s single-generation gate in
     * [GenerationJobCreationService.createJob] is `loaded.status.isIssued && hasCompletedIssuedJob(...)` —
     * BOTH conditions, ANDed. In this exact edge case the invoice's own status never becomes
     * `SENT` (`isIssued` stays `false`), so the gate does **not** actually block a further
     * `createJob`/retry `requestSend` call for this invoice, despite the completed job's own
     * `issued = true` flag — the spec's "this now blocks any further generation for this invoice"
     * framing describes the job's own flag, not an enforced invoice-level lock, in this specific
     * branch. A retry can therefore actually go through and, once the underlying data defect
     * (here: BT-10) is fixed, succeed normally — which arguably makes this edge case
     * self-recoverable via a plain retry rather than "permanently locked". This is exactly
     * the open PO decision the spec flags; this test documents the real, verified behavior rather
     * than the narrative assumption.
     */
    @Test
    fun `gate throws after job COMPLETED -- surfaced loudly via a message-center entry, invoice never reaches SENT`() {
        val invoiceId = createSendableInvoice("SC4")
        val job = invoiceSendService.requestSend(invoiceId)
        generationJobPublishingService.markDelegated(job)
        val completed = generationJobResultHandler.markCompleted(job, resultRefs = "{}", kositReport = validKositReport())
        QuarkusTransaction.requiringNew().run {
            em.find(de.littletrotter.domain.invoice.Invoice::class.java, invoiceId).references.buyerReference = null
        }

        generationJobResultHandler.resolveSendOutcome(completed)

        val invoice = freshInvoice(invoiceId)
        assertEquals(InvoiceStatus.IN_REVIEW, invoice.status, "the invoice must never reach SENT when the gate rejects it")
        val messages = sendMessages()
        assertTrue(
            messages.any { it.messageKey == "message.center.send.gateFailedAfterCompletion" },
            "expected a gateFailedAfterCompletion message-center entry, got: ${messages.map { it.messageKey }}",
        )

        // See this test's own KDoc: verified empirically that the gate, as actually coded, does NOT
        // block a further createJob call here, because the invoice's own status never became
        // issued (SENT). A retry (after fixing the data defect) can succeed normally.
        val retryJob = invoiceSendService.requestSend(invoiceId)
        assertNotNull(retryJob.id, "RS-1 does not block a retry here -- the invoice's own status never became issued")
    }

    @Test
    fun `a user cannot void or revert an invoice while its SEND job is in flight`() {
        val invoiceId = createSendableInvoice("SC6")
        invoiceSendService.requestSend(invoiceId)

        assertThrows(de.littletrotter.domain.exception.InvoiceSendAlreadyInProgressException::class.java) {
            QuarkusTransaction.requiringNew().run { invoiceLifecycleService.transition(invoiceId, InvoiceStatus.VOIDED) }
        }
        assertThrows(de.littletrotter.domain.exception.InvoiceSendAlreadyInProgressException::class.java) {
            QuarkusTransaction.requiringNew().run { invoiceLifecycleService.transition(invoiceId, InvoiceStatus.DRAFT) }
        }

        assertEquals(InvoiceStatus.IN_REVIEW, freshInvoice(invoiceId).status)
    }

    @Test
    fun `an invoice voided after its SEND job completed stays voided and the late outcome is a no-op without a message`() {
        val invoiceId = createSendableInvoice("SC7")
        val job = invoiceSendService.requestSend(invoiceId)
        generationJobPublishingService.markDelegated(job)
        val completed = generationJobResultHandler.markCompleted(job, resultRefs = "{}", kositReport = validKositReport())
        val messagesBefore = sendMessages().size
        QuarkusTransaction.requiringNew().run { invoiceLifecycleService.transition(invoiceId, InvoiceStatus.VOIDED) }

        generationJobResultHandler.resolveSendOutcome(completed)

        assertEquals(InvoiceStatus.VOIDED, freshInvoice(invoiceId).status)
        assertEquals(messagesBefore, sendMessages().size, "a lost race is logged, not reported as a failure")
    }

    @Test
    fun `a credit note gets its document job right after the storno commits`() {
        val invoiceId = createSendableInvoice("SC8")
        val job = invoiceSendService.requestSend(invoiceId)
        generationJobPublishingService.markDelegated(job)
        val completed = generationJobResultHandler.markCompleted(job, resultRefs = "{}", kositReport = validKositReport())
        generationJobResultHandler.resolveSendOutcome(completed)
        assertEquals(InvoiceStatus.SENT, freshInvoice(invoiceId).status)

        val creditNoteId = changeInvoiceStatus.createCreditNote(invoiceId, "IT-Storno", null).id

        val jobs = QuarkusTransaction.requiringNew().call<List<de.littletrotter.domain.invoice.GenerationJob>> {
            generationJobRepository.findByInvoiceId(creditNoteId)
        }
        assertEquals(1, jobs.size, "the issued credit note must never exist without a document job")
        assertTrue(jobs.single().issued)
    }

    @Test
    fun `the sweep creates exactly one document job for a committed credit note that never got one`() {
        val invoiceId = createSendableInvoice("SC9")
        val job = invoiceSendService.requestSend(invoiceId)
        generationJobPublishingService.markDelegated(job)
        val completed = generationJobResultHandler.markCompleted(job, resultRefs = "{}", kositReport = validKositReport())
        generationJobResultHandler.resolveSendOutcome(completed)
        // Storno service directly: the follow-up job request is skipped, as after a crash.
        val creditNoteId = QuarkusTransaction.requiringNew().call<Long> {
            invoiceStornoService.createCreditNote(invoiceId, "IT-Storno").id!!
        }
        fun jobCount() = QuarkusTransaction.requiringNew().call<Int> { generationJobRepository.findByInvoiceId(creditNoteId).size }
        assertEquals(0, jobCount())

        assertTrue(issuedDocumentJobSweep.requestMissingCreditNoteJobs() >= 1)
        assertEquals(1, jobCount())

        issuedDocumentJobSweep.requestMissingCreditNoteJobs()
        assertEquals(1, jobCount(), "a second sweep must not enqueue another job")
    }

    @Test
    fun `a version bump between the job result and the SENT transition still ends with the invoice SENT`() {
        val invoiceId = createSendableInvoice("RACE${System.nanoTime()}")
        val job = invoiceSendService.requestSend(invoiceId)
        generationJobPublishingService.markDelegated(job)
        val completed = generationJobResultHandler.markCompleted(job, resultRefs = "{}", kositReport = validKositReport())

        // The outer transaction reads the invoice at version V; another transaction then commits
        // V+1 before the SENT transition runs, as a concurrent edit or audit write does.
        QuarkusTransaction.requiringNew().run {
            em.find(de.littletrotter.domain.invoice.Invoice::class.java, invoiceId)
            QuarkusTransaction.requiringNew().run {
                em.createNativeQuery("UPDATE invoice SET version = version + 1 WHERE id = :id")
                    .setParameter("id", invoiceId).executeUpdate()
            }
            generationJobResultHandler.resolveSendOutcome(completed)
        }

        assertEquals(InvoiceStatus.SENT, freshInvoice(invoiceId).status)
    }
}
