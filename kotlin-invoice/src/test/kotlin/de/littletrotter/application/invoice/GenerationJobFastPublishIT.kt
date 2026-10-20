package de.littletrotter.application.invoice

import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.DocumentJobLane
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
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import io.nats.client.Connection
import io.nats.client.Nats
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * AP-M5 — end-to-end proof of the fast path through the
 * *real* CDI wiring: [GenerationJobCreationService.createJob]'s transactional `GenerationJobCreated`
 * event, [GenerationJobProcessor.onGenerationJobCreated]'s `TransactionPhase.AFTER_SUCCESS`
 * observer, the hop onto the injected managed executor, and [GenerationJobProcessor.publishOne]
 * itself — all real beans, real Postgres, real NATS JetStream (Testcontainers, not mocked).
 *
 * Deliberately not using the shared [de.littletrotter.test.NatsTestContainersExtension] /
 * [de.littletrotter.test.CombinedTestContainersExtension] singleton containers other ITs in this
 * module reuse across the whole JVM: the NATS-down scenario below pauses the container the app
 * itself is connected to, and doing that to a container shared with unrelated test classes would
 * make them fail too. [NatsTestResource] starts a fresh, `withReuse(false)` container scoped to
 * just this `@QuarkusTest` class instead.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(NatsTestResource::class)
class GenerationJobFastPublishIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobProcessor: GenerationJobProcessor

    @ConfigProperty(name = "nats.url")
    lateinit var natsUrl: String

    private fun natsConnection(): Connection = Nats.connect(natsUrl)

    /** Same shape as [GenerationJobServiceIT]'s own helper — see that class's KDoc for why each
     *  setup/service call here runs in its own top-level transaction rather than one enclosing
     *  `@Transactional` test method. */
    private fun persistInvoice(number: String): Invoice = QuarkusTransaction.requiringNew().call<Invoice> {
        val sender = aSenderProfile {
            name = "Muster GmbH"
            email = Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            invoicePrefix = "FP${number.takeLast(4)}-"
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
            code = "M5_TEST_19_$number"
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

    private fun statusOf(jobId: Long): GenerationJobStatus =
        QuarkusTransaction.requiringNew().call<GenerationJobStatus> {
            em.find(GenerationJob::class.java, jobId).status
        }

    /**
     * The NATS ack a test's latch fires on and the [publishOne][GenerationJobProcessor.publishOne]
     * transaction's own commit are two independent systems with no ordering guarantee between
     * them — `.get()` on the publish future can return (and unblock a subscriber) a few
     * milliseconds before the enclosing `@Transactional` actually finishes committing. Polling
     * briefly here (rather than asserting on a single immediate read) is what the status check
     * itself needs to be race-free, independent of the production code's own correctness.
     */
    private fun awaitStatus(jobId: Long, expected: GenerationJobStatus, timeoutMs: Long = 2000): GenerationJobStatus {
        val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000
        var current = statusOf(jobId)
        while (current != expected && System.nanoTime() < deadlineNanos) {
            Thread.sleep(25)
            current = statusOf(jobId)
        }
        return current
    }

    @Test
    fun `fast path delivers the job to NATS well under the 10s poller interval`() {
        val invoice = persistInvoice("ITM5-2026-0001")
        val conn = natsConnection()
        try {
            val latch = CountDownLatch(1)
            conn.createDispatcher { _ -> latch.countDown() }.subscribe("document-jobs")

            val startNanos = System.nanoTime()
            val job = generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
            // Generous CI-safe wait, not a tight production-latency bound — the actual proof that
            // this went through the fast path (not the 10s poller) is elapsedMs asserted below.
            val delivered = latch.await(5, TimeUnit.SECONDS)
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

            assertTrue(delivered, "message was not visible in the stream within 5s (elapsed ${elapsedMs}ms)")
            assertTrue(
                elapsedMs < 5000,
                "delivery took ${elapsedMs}ms — the fast path must beat the 10s poller interval by a wide margin",
            )
            assertEquals(
                GenerationJobStatus.DELEGATED,
                awaitStatus(job.id!!, GenerationJobStatus.DELEGATED),
                "a successfully published job is DELEGATED, not left PENDING",
            )
        } finally {
            conn.close()
        }
    }

    @Test
    fun `concurrent poller and fast path publish exactly once`() {
        val invoice = persistInvoice("ITM5-2026-0002")
        val conn = natsConnection()
        try {
            val received = AtomicInteger(0)
            val firstMessage = CountDownLatch(1)
            conn.createDispatcher { _ ->
                received.incrementAndGet()
                firstMessage.countDown()
            }.subscribe("document-jobs")

            // createJob()'s transaction commit already scheduled the fast path
            // (onGenerationJobCreated -> managedExecutor -> publishOne) onto a worker thread by
            // the time this call returns. Calling pollPendingJobs() synchronously right here, on
            // this thread, races it against that already-in-flight fast path for the same
            // PENDING->DELEGATED claim — exactly the scenario tryMarkDelegated exists to guard.
            val job = generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
            generationJobProcessor.pollPendingJobs()

            assertTrue(firstMessage.await(10, TimeUnit.SECONDS), "neither the fast path nor the poller delivered a message in time")
            // Settle window: give the loser of the race (if it got far enough to attempt
            // anything) a chance to also publish, so a real double-publish isn't missed by
            // stopping the count too early.
            Thread.sleep(1000)

            assertEquals(1, received.get(), "exactly one stream message must exist despite the race")
            assertEquals(GenerationJobStatus.DELEGATED, awaitStatus(job.id!!, GenerationJobStatus.DELEGATED))
        } finally {
            conn.close()
        }
    }

    @Test
    fun `fast path failure leaves the job PENDING, poller delivers once NATS returns`() {
        val invoice = persistInvoice("ITM5-2026-0003")

        NatsTestResource.pause()
        val job = try {
            val job = generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)

            // GenerationJobFastPublishTransaction.publishOne() blocks on the publish ack for up to its own
            // FAST_PATH_PUBLISH_TIMEOUT_SECONDS (3s) before reverting the claim back to PENDING;
            // give it comfortable headroom over that bound.
            Thread.sleep(4000)

            assertEquals(
                GenerationJobStatus.PENDING,
                statusOf(job.id!!),
                "a fast-path publish attempt against an unreachable NATS must hand the job back to PENDING, not leave it DELEGATED",
            )
            job
        } finally {
            NatsTestResource.unpause()
        }

        val conn = natsConnection()
        try {
            val latch = CountDownLatch(1)
            conn.createDispatcher { _ -> latch.countDown() }.subscribe("document-jobs")

            generationJobProcessor.pollPendingJobs()

            assertTrue(latch.await(5, TimeUnit.SECONDS), "the poller must deliver the job once NATS is reachable again")
            assertEquals(GenerationJobStatus.DELEGATED, statusOf(job.id!!))
        } finally {
            conn.close()
        }
    }
}
