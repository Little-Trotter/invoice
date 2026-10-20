package de.littletrotter.application.invoice

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.port.inbound.PdfCheckVerdict
import de.littletrotter.application.invoice.port.inbound.RecordPdfCheckResultUseCase
import de.littletrotter.application.masterdata.CustomerManagementService
import de.littletrotter.application.masterdata.SellerDefaultSelections
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.VeraPdfCheckState
import de.littletrotter.domain.invoice.veraPdfCheckStateOf
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.message.SystemMessage
import de.littletrotter.domain.message.SystemMessageFilter
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.SystemMessageRepository
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.Money
import de.littletrotter.shared.ValidationRun
import de.littletrotter.shared.ValidationStageResult
import de.littletrotter.shared.tax.CustomerType
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The send decision and the veraPDF verdict write the same job row from two threads. Whichever
 * runs second must see the other's committed state: a hold is only recorded while no verdict is
 * stored, and a verdict that arrives first releases the send instead of leaving it on hold.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class VeraPdfHoldRaceIT {

    companion object {
        private val uniqueCounter = AtomicInteger(0)
        private const val HOLD_KEY = "message.center.send.veraPdfPending"
        private const val BLOCKED_CHECK_MILLIS = 500L
        private const val WAIT_SECONDS = 30L
    }

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var invoiceLifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var invoiceSendService: InvoiceSendService

    @Inject
    lateinit var generationJobPublishingService: GenerationJobPublishingService

    @Inject
    lateinit var generationJobResultHandler: GenerationJobResultHandler

    @Inject
    lateinit var recordPdfCheckResult: RecordPdfCheckResultUseCase

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var generationJobRepository: GenerationJobRepository

    @Inject
    lateinit var sellerProfileService: SellerProfileService

    @Inject
    lateinit var customerManagementService: CustomerManagementService

    @Inject
    lateinit var systemMessageRepository: SystemMessageRepository

    private fun createValidSenderProfile(name: String): SenderProfile = aSenderProfile {
        this.name = name
        invoicePrefix = "VHR${uniqueCounter.incrementAndGet()}-"
        email = Email("sender${System.nanoTime()}@example.invalid")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban("DE89370400440532013000")
        registerCourt = "Amtsgericht Test"
        phone = "+49 30 1234567"
    }.also { sellerProfileService.save(it, defaults = SellerDefaultSelections()) }

    private fun createValidRecipient(name: String): Recipient = aRecipient {
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
        val taxRate = TaxRate().edit {
            name = "19%"
            rate = TaxRatePercent(BigDecimal("19.00"))
            code = "SC_TEST_19_$tag"
            category = "S"
            isSelectable = false
        }
        em.persist(taxRate)
        em.flush()
        return anInvoiceItem(
            description = "Test Item",
            quantity = BigDecimal("1.0"),
            unitPriceNet = Money.of("100.00"),
            taxRateRef = taxRate,
        )
    }

    /** Builds and persists a sendable (IN_REVIEW) invoice via the real application services, the
     *  same recipe [de.littletrotter.application.masterdata.MasterDataSnapshotsViesRegistrationsIT]
     *  uses. Returns its id. */
    private fun createSendableInvoice(tag: String): Long = QuarkusTransaction.requiringNew().call<Long> {
        val sender = createValidSenderProfile("ACME $tag")
        val recipient = createValidRecipient("Kunde $tag")
        val invoice = invoiceLifecycleService.create(sender.id!!, recipient.id!!)
        invoice.edit { references.buyerReference = "BUYER-REF-$tag" }
        invoice.addItem(createMinimalItem(tag))
        invoice.edit { deliveryDate = java.time.LocalDate.now() }
        invoiceRepository.saveAndFlush(invoice)
        invoiceLifecycleService.transition(invoice.id!!, InvoiceStatus.IN_REVIEW)
        invoice.id!!
    }

    private fun pendingReport(): String = KositReportPayload(
        status = "VALID",
        validationRun = ValidationRun(
            listOf(
                ValidationStageResult("PDFA", KositOutcome.VALID),
                ValidationStageResult(ValidationStageResult.VERAPDF, KositOutcome.VALID, pending = true, advisory = true),
            ),
            KositOutcome.VALID,
            startedAt = "2026-10-12T10:00:00Z",
        ),
    ).toJson()

    private fun verdictFor(job: GenerationJob) =
        PdfCheckVerdict(checkNotNull(job.id), KositOutcome.VALID, "veraPDF 1.30.2", emptyList(), null)

    /** A SEND job completed with a valid report whose veraPDF stage is still outstanding. */
    private fun completedSendJob(tag: String): Pair<Long, GenerationJob> {
        val invoiceId = createSendableInvoice(tag)
        val job = invoiceSendService.requestSend(invoiceId)
        generationJobPublishingService.markDelegated(job)
        val completed = generationJobResultHandler.markCompleted(job, resultRefs = "{}", kositReport = pendingReport())
        // The completion merges its own business findings into the report; the race is about the
        // veraPDF stage, so the stored report is pinned to a valid run with that stage outstanding.
        QuarkusTransaction.requiringNew().run {
            val stored = checkNotNull(generationJobRepository.findById(checkNotNull(completed.id)))
            stored.replaceKositReport(pendingReport())
            generationJobRepository.save(stored)
        }
        return invoiceId to completed
    }

    private fun holdMessages(invoiceId: Long): List<SystemMessage> =
        QuarkusTransaction.requiringNew().call<List<SystemMessage>> {
            systemMessageRepository.find(userId = "system", filter = SystemMessageFilter(source = setOf(SystemMessageSource.GENERATION)), pageSize = 200)
        }.filter { it.messageKey == HOLD_KEY && it.refId == invoiceId.toString() }

    private fun freshInvoice(invoiceId: Long): Invoice =
        QuarkusTransaction.requiringNew().call<Invoice> { em.find(Invoice::class.java, invoiceId) }

    private fun storedReport(jobId: Long): String? =
        QuarkusTransaction.requiringNew().call<String?> { generationJobRepository.findById(jobId)?.kositReport }

    @Test
    fun `a verdict committed before the send decision releases the send without a hold message`() {
        val (invoiceId, job) = completedSendJob("VH1")

        recordPdfCheckResult.record(verdictFor(job))
        generationJobResultHandler.resolveSendOutcome(job)

        assertEquals(InvoiceStatus.ISSUED, freshInvoice(invoiceId).status)
        assertTrue(holdMessages(invoiceId).isEmpty(), "no hold message once the verdict was stored")
    }

    @Test
    fun `a send decision before the verdict holds once and the verdict then issues the invoice`() {
        val (invoiceId, job) = completedSendJob("VH2")

        generationJobResultHandler.resolveSendOutcome(job)
        assertEquals(InvoiceStatus.IN_REVIEW, freshInvoice(invoiceId).status)
        assertEquals(1, holdMessages(invoiceId).size)

        recordPdfCheckResult.record(verdictFor(job))

        assertEquals(InvoiceStatus.ISSUED, freshInvoice(invoiceId).status)
        assertEquals(1, holdMessages(invoiceId).size)
    }

    @Test
    fun `the verdict waits for a transaction holding the job row and is stored after it commits`() {
        val (_, job) = completedSendJob("VH3")
        val jobId = checkNotNull(job.id)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)

        val holder = CompletableFuture.runAsync {
            QuarkusTransaction.requiringNew().run {
                generationJobRepository.findByIdForUpdate(jobId)
                locked.countDown()
                assertTrue(release.await(WAIT_SECONDS, TimeUnit.SECONDS))
            }
        }
        assertTrue(locked.await(WAIT_SECONDS, TimeUnit.SECONDS), "lock holder started")

        val writer = CompletableFuture.runAsync { recordPdfCheckResult.record(verdictFor(job)) }
        try {
            writer.get(BLOCKED_CHECK_MILLIS, TimeUnit.MILLISECONDS)
            throw AssertionError("the verdict must wait for the row lock")
        } catch (_: TimeoutException) {
            assertFalse(writer.isDone)
        } finally {
            release.countDown()
        }

        holder.get(WAIT_SECONDS, TimeUnit.SECONDS)
        writer.get(WAIT_SECONDS, TimeUnit.SECONDS)
        assertEquals(VeraPdfCheckState.PASSED, veraPdfCheckStateOf(storedReport(jobId)))
    }
}
