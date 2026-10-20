package de.littletrotter.application.invoice

import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import io.nats.client.api.PublishAck
import io.quarkus.arc.ClientProxy
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proves [DocumentJobDispatchGate.dispatch]'s `@Bulkhead` (a) reads its limit from
 * `invoice.generation.bulkhead.max-concurrent-calls` rather than the compiled annotation default
 * (20 — [BulkheadLimitTwoProfile] overrides it to 2, so a rejection at the third concurrent call
 * is proof the override took effect), (b) rejects with a [BulkheadException] instead of queueing,
 * and (c) that a rejection composes with the existing durable-recovery behaviour for generation
 * jobs instead of dropping work.
 *
 * The permits are held deterministically: [HoldingPublishTransaction] replaces the transactional bean (via
 * [QuarkusMock], so the gate's injected proxy resolves to it) and blocks the first two dispatches
 * on a latch until the test releases them — no timing assumptions about NATS. The gate itself,
 * its interceptors and the job rows are real (Postgres via [PostgresTestResource]); once released,
 * the held dispatches go through the real bean and reach the real NATS container.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(NatsTestResource::class)
@TestProfile(BulkheadLimitTwoProfile::class)
class DocumentJobPublisherBulkheadIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var documentJobPublisher: DocumentJobPublisher

    @Inject
    lateinit var fastPublishTransaction: GenerationJobFastPublishTransaction

    @Inject
    lateinit var publishTransactionProxy: DocumentJobPublishTransaction

    /** The contextual instance behind the client proxy — still ArC's intercepted subclass, so
     *  `@Transactional` applies — which the stub can delegate to without resolving to itself
     *  once [QuarkusMock] has replaced what the proxy points at. */
    private fun holding(holdCount: Int): HoldingPublishTransaction =
        HoldingPublishTransaction(ClientProxy.unwrap(publishTransactionProxy), holdCount).also {
            QuarkusMock.installMockForType(it, DocumentJobPublishTransaction::class.java)
        }

    private val pool = Executors.newCachedThreadPool()

    @AfterEach
    fun shutdownPool() {
        pool.shutdownNow()
    }

    /** Blocks the first [holdCount] dispatches on [release]; everything else goes straight to the real bean. */
    class HoldingPublishTransaction(
        private val delegate: DocumentJobPublishTransaction,
        private val holdCount: Int,
    ) : DocumentJobPublishTransaction(null, null, null, null, null, null, null) {
        val entered = CountDownLatch(holdCount)
        val release = CountDownLatch(1)
        val calls = AtomicInteger(0)

        override fun publishTransactional(invoice: Invoice, job: GenerationJob, skipValidation: Boolean): CompletableFuture<PublishAck> {
            if (calls.incrementAndGet() <= holdCount) {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "test never released the held dispatches" }
            }
            return delegate.publishTransactional(invoice, job, skipValidation)
        }
    }

    private fun persistInvoice(number: String): Invoice = QuarkusTransaction.requiringNew().call<Invoice> {
        val sender = aSenderProfile {
            name = "Muster GmbH"
            email = Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            invoicePrefix = "BH${number.takeLast(4)}-"
        }
        val recipient = aRecipient {
            customerNumber = CustomerNumber("KD-$number")
            name = "Kunde AG"
            email = Email("kunde@kunde-ag.example")
            vatId = VatId("DE987654321")
            iban = Iban("DE02120300000000202051")
        }
        val taxRate = TaxRate().edit {
            name = "19%"
            rate = TaxRatePercent(BigDecimal("19.00"))
            code = "ARC91_03_BH_19_$number"
            category = "S"
            isSelectable = false
        }
        em.persist(sender)
        em.persist(recipient)
        em.persist(taxRate)
        val item = anInvoiceItem(
            positionId = "1",
            description = "Beratung",
            quantity = BigDecimal("1.00"),
            unitPriceNet = Money(BigDecimal("100.00")),
            taxRateRef = taxRate,
        )
        val invoice = anInvoice(
            invoiceNumber = InvoiceNumber(number),
            invoiceDate = LocalDate.now(),
            senderProfile = sender,
            recipient = recipient,
            items = listOf(item),
            recalculate = true,
        )
        em.persist(invoice)
        em.flush()
        invoice
    }

    /** A PENDING job row without [GenerationJobService.createJob]'s event, whose automatic fast
     *  path would otherwise race the calls this test makes itself. */
    private fun persistPendingJob(invoice: Invoice): Long = QuarkusTransaction.requiringNew().call<Long> {
        val job = aGenerationJob {
            this.invoice = em.merge(invoice)
            jobType = "PRINT_JOB"
            status = GenerationJobStatus.PENDING
            createdAt = OffsetDateTime.now()
        }
        em.persist(job)
        em.flush()
        requireNotNull(job.id)
    }

    /** Same shape as the original fixture: a job that is never persisted, carrying the
     *  fully populated in-memory invoice, for calls that go straight to [DocumentJobPublisher]. */
    private fun transientJob(invoice: Invoice, id: Long): GenerationJob = aGenerationJob {
        this.id = id
        jobType = "PRINT_JOB"
        this.invoice = invoice
    }

    private fun statusOf(jobId: Long): GenerationJobStatus =
        QuarkusTransaction.requiringNew().call<GenerationJobStatus> {
            em.find(GenerationJob::class.java, jobId).status
        }

    @Test
    fun `a third concurrent dispatch is rejected with BulkheadException while the configured two are held`() {
        val holding = holding(holdCount = 2)
        val invoice = persistInvoice("ARC913BH-2026-0001")
        val held = listOf(transientJob(invoice, 900_001L), transientJob(invoice, 900_002L))
        val third = transientJob(invoice, 900_003L)

        val inFlight = held.map { job -> pool.submit<CompletableFuture<PublishAck>> { documentJobPublisher.publish(job) } }
        assertTrue(holding.entered.await(10, TimeUnit.SECONDS), "both permits must be held before the third call")

        assertThrows(BulkheadException::class.java) { documentJobPublisher.publish(third) }
        assertEquals(2, holding.calls.get(), "the rejected call must never have reached the transactional bean")

        holding.release.countDown()
        inFlight.forEach { it.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS) }
    }

    @Test
    fun `a fast-path dispatch rejected by the bulkhead leaves its job durably PENDING while the held ones reach NATS`() {
        val holding = holding(holdCount = 2)
        // One invoice per job: uq_generation_job_active_print allows a single active PRINT_JOB per invoice+engine.
        val heldIds = (2..3).map { n -> persistPendingJob(persistInvoice("ARC913BB-2026-000$n")) }
        val rejectedId = persistPendingJob(persistInvoice("ARC913BB-2026-0004"))

        val inFlight = heldIds.map { id -> pool.submit { fastPublishTransaction.publishOne(id) } }
        assertTrue(holding.entered.await(10, TimeUnit.SECONDS), "both permits must be held before the third call")

        fastPublishTransaction.publishOne(rejectedId)
        assertEquals(
            GenerationJobStatus.PENDING,
            statusOf(rejectedId),
            "a bulkhead rejection must hand the fast path's claim back so the poller can retry",
        )

        holding.release.countDown()
        inFlight.forEach { it.get(10, TimeUnit.SECONDS) }
        heldIds.forEach { id ->
            assertEquals(GenerationJobStatus.DELEGATED, statusOf(id), "an accepted dispatch must complete normally")
        }
        assertEquals(GenerationJobStatus.PENDING, statusOf(rejectedId))
    }
}

/** Overrides the bulkhead limit down from its 20-for-local-dev default so two held
 *  dispatches are enough to observe a rejection; also the proof that the limit is genuinely read
 *  from configuration (see [DocumentJobPublisherBulkheadIT]'s own KDoc). */
class BulkheadLimitTwoProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "invoice.generation.bulkhead.max-concurrent-calls" to "2",
    )
}
