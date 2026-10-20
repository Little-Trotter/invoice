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
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proves [DocumentJobDispatchGate.dispatch]'s bounded ack wait, and that a failed
 * attempt is handed to the durable loop rather than retried in-process, through the real CDI
 * interceptor chain and against a real broker (Testcontainers, paused/unpaused via
 * [NatsTestResource], the same mechanism [GenerationJobFastPublishIT] uses) — these are
 * interceptor annotations, so they only take effect on the real, injected bean, never on the
 * test-seam constructor [DocumentJobPublisherTest] uses.
 *
 * Uses its own, `withReuse(false)` NATS container ([NatsTestResource]'s own KDoc) rather than the
 * shared Testcontainers singleton other ITs reuse, since pausing that container would break every
 * unrelated test that also happens to run against it.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(NatsTestResource::class)
class DocumentJobPublisherFaultToleranceIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var fastPublishTransaction: GenerationJobFastPublishTransaction

    /** Fails every attempt the way the NATS client does mid-reconnect: a synchronous exception
     *  out of the transactional bean. */
    class FailingPublishTransaction : DocumentJobPublishTransaction(null, null, null, null, null, null, null) {
        val calls = AtomicInteger(0)

        override fun publishTransactional(invoice: Invoice, job: GenerationJob, skipValidation: Boolean): CompletableFuture<PublishAck> {
            calls.incrementAndGet()
            error("simulated NATS client failure")
        }
    }

    /** A broker that never answers: the returned future stays pending forever, so the only thing
     *  that can end the caller's wait is the gate's own ack bound. */
    class HangingPublishTransaction : DocumentJobPublishTransaction(null, null, null, null, null, null, null) {
        override fun publishTransactional(invoice: Invoice, job: GenerationJob, skipValidation: Boolean): CompletableFuture<PublishAck> =
            CompletableFuture()
    }

    private fun persistInvoice(number: String): Invoice = QuarkusTransaction.requiringNew().call<Invoice> {
        val sender = aSenderProfile {
            name = "Muster GmbH"
            email = Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            invoicePrefix = "FT${number.takeLast(4)}-"
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
            code = "ARC91_03_FT_19_$number"
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

    private fun statusOf(jobId: Long): GenerationJobStatus =
        QuarkusTransaction.requiringNew().call<GenerationJobStatus> {
            em.find(GenerationJob::class.java, jobId).status
        }

    @Test
    fun `A synchronously failing dispatch is attempted exactly once and handed back to the durable loop`() {
        val failing = FailingPublishTransaction()
        QuarkusMock.installMockForType(failing, DocumentJobPublishTransaction::class.java)
        val invoice = persistInvoice("ARC913FT-2026-0001")
        val jobId = persistPendingJob(invoice)

        fastPublishTransaction.publishOne(jobId)

        assertEquals(1, failing.calls.get(), "no in-process retry: the attempt joined publishOne's transaction, which the failure marked rollback-only")
        assertEquals(GenerationJobStatus.PENDING, statusOf(jobId), "the fast path hands its claim back for the poller")
    }

    @Test
    fun `A dispatch whose ack never arrives fails at the configured bound and hands the fast path's claim back`() {
        QuarkusMock.installMockForType(HangingPublishTransaction(), DocumentJobPublishTransaction::class.java)
        val invoice = persistInvoice("ARC913FT-2026-0002")
        val jobId = persistPendingJob(invoice)

        val started = System.nanoTime()
        // publishOne never lets an exception escape (its own KDoc): it claims PENDING->DELEGATED,
        // waits on the ack, and hands the claim back once the dispatch fails — the durable-recovery
        // composition requires, with no separate rejection path added for it.
        fastPublishTransaction.publishOne(jobId)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals(GenerationJobStatus.PENDING, statusOf(jobId), "a dispatch without an ack must leave the job PENDING (durable, recoverable)")
        // invoice.publish.timeout-ms (2000 by default) ended the wait, not the fast path's own outer
        // 3s bound: elapsed sits between the two.
        assertTrue(elapsedMs in 1800..2900, "expected the 2s ack bound to fire, elapsed ${elapsedMs}ms")
    }

    @Test
    fun `A sustained outage of the real broker leaves the job durably PENDING for the poller`() {
        val invoice = persistInvoice("ARC913FT-2026-0003")
        val jobId = persistPendingJob(invoice)

        NatsTestResource.pause()
        val elapsedMs = try {
            val started = System.nanoTime()
            fastPublishTransaction.publishOne(jobId)
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        } finally {
            NatsTestResource.unpause()
        }

        assertEquals(
            GenerationJobStatus.PENDING,
            statusOf(jobId),
            "a dispatch the broker never acknowledged must leave the job PENDING, not DELEGATED with no message ever sent",
        )
        // Against a paused container the jnats client stalls for ~2s *inside* the synchronous
        // hand-off (before the gate's ack bound even starts counting), so here it is the fast
        // path's own 3s .get() that ends the wait — pre-existing client behaviour, not the gate's.
        // What this test pins down is the durable outcome plus that nothing waits beyond that bound.
        assertTrue(elapsedMs < 4000, "the fast path's own bound must end the wait, elapsed ${elapsedMs}ms")
    }
}
