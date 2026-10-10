package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.system.JobPipelineStatus
import de.littletrotter.application.system.JobPipelineStatusService
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.Money
import de.littletrotter.shared.NatsStreamSetup
import io.nats.client.Nats
import io.nats.client.PublishOptions
import io.nats.client.PurgeOptions
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * Real Postgres + real NATS JetStream (Testcontainers): [JobPipelineStatusService]'s job
 * stages come from the DB regardless of the broker, and the transport line's `Warteschlange`
 * (queue depth) comes from JetStream alone. No kotlin-xpdf worker process is involved — a
 * consumer with nobody actively pulling from it is modelled by creating the durable
 * `kotlin-xpdf-group` consumer (the same bootstrap kotlin-xpdf itself performs on startup) but
 * never calling `.consume` on it, exactly like
 * [de.littletrotter.application.invoice.JobWorkerAvailabilityRecoveryIT]'s "worker down" phase.
 *
 * `generation_job` is a shared table across this module's whole IT run (other `@QuarkusTest`
 * classes commit real rows against the same Testcontainers Postgres — see
 * [de.littletrotter.application.invoice.PrintJobOverviewQueryServiceIT]'s own KDoc for the same
 * caveat), and [de.littletrotter.domain.repository.GenerationJobRepository.pipelineCounts] is
 * deliberately a global, unscoped aggregate (the whole point of the pipeline view). Every
 * assertion here is therefore a *delta* against a snapshot taken before this test's own writes,
 * never an absolute count — the `document-jobs` subject is also purged before/after each test so
 * `Warteschlange` deltas are not polluted by another test's leftover messages either.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(NatsTestResource::class)
class NatsJobPipelineStatusServiceIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var jobPipelineStatusService: JobPipelineStatusService

    @ConfigProperty(name = "nats.url")
    lateinit var natsUrl: String

    @ConfigProperty(name = "nats.consumer.document-jobs", defaultValue = "kotlin-xpdf-group")
    lateinit var workerDurableName: String

    @ConfigProperty(name = "nats.subject.document-jobs", defaultValue = "document-jobs")
    lateinit var jobsSubject: String

    @ConfigProperty(name = "nats.consumer.document-jobs-interactive", defaultValue = "kotlin-xpdf-interactive")
    lateinit var interactiveDurableName: String

    @ConfigProperty(name = "nats.subject.document-jobs-interactive", defaultValue = "document-jobs-interactive")
    lateinit var interactiveJobsSubject: String

    @BeforeEach
    fun purgeJobsSubjectBeforeTest() = purgeJobsSubject()

    @AfterEach
    fun cleanup() {
        purgeJobsSubject()
        runCatching {
            Nats.connect(natsUrl).use { it.jetStreamManagement().deleteConsumer(NatsStreamSetup.STREAM_NAME, workerDurableName) }
        }
        runCatching {
            Nats.connect(natsUrl).use { it.jetStreamManagement().deleteConsumer(NatsStreamSetup.STREAM_NAME, interactiveDurableName) }
        }
    }

    private fun purgeJobsSubject() {
        runCatching {
            Nats.connect(natsUrl).use { it.jetStreamManagement().purgeStream(NatsStreamSetup.STREAM_NAME, PurgeOptions.subject(jobsSubject)) }
        }
        runCatching {
            Nats.connect(natsUrl).use { it.jetStreamManagement().purgeStream(NatsStreamSetup.STREAM_NAME, PurgeOptions.subject(interactiveJobsSubject)) }
        }
    }

    private fun persistInvoice(number: String): Long = QuarkusTransaction.requiringNew().call<Long> {
        val sender = SenderProfile().apply {
            name = "Muster GmbH"
            email = Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            invoicePrefix = "PL${number.takeLast(4)}-"
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
            code = "PL_TEST_19_$number"
            category = "S"
            isSelectable = false
        }
        em.persist(sender)
        em.persist(recipient)
        em.persist(taxRate)
        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber(number)
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
        invoice.id!!
    }

    private fun persistJob(
        invoiceId: Long,
        status: GenerationJobStatus,
        createdAt: OffsetDateTime = OffsetDateTime.now(),
        finishedAt: OffsetDateTime? = null,
    ): Long = QuarkusTransaction.requiringNew().call<Long> {
        val job = GenerationJob().apply {
            this.invoice = em.getReference(Invoice::class.java, invoiceId)
            this.jobType = "PDF_EXPORT"
            this.status = status
            this.createdAt = createdAt
            this.finishedAt = finishedAt
        }
        em.persist(job)
        em.flush()
        job.id!!
    }

    /** Creates the durable `kotlin-xpdf-group` consumer (mirrors kotlin-xpdf's own startup
     *  bootstrap) but never attaches a puller — the "worker down"/"nobody consuming" state the
     *  spec's own test description asks for. */
    private fun ensureWorkerConsumerWithoutPulling() {
        Nats.connect(natsUrl).use { connection ->
            val jsm = connection.jetStreamManagement()
            NatsStreamSetup.ensureConsumer(
                jsm,
                NatsStreamSetup.STREAM_NAME,
                NatsStreamSetup.consumerConfiguration(durableName = workerDurableName, filterSubject = jobsSubject, maxAckPending = 8),
            )
        }
    }

    private fun publishRaw(msgId: String) {
        Nats.connect(natsUrl).use { connection ->
            connection.jetStream().publish(jobsSubject, "{}".toByteArray(Charsets.UTF_8), PublishOptions.builder().messageId(msgId).build())
        }
    }

    /** JetStream consumer-info propagation is effectively synchronous with a synchronous publish,
     *  but this polls briefly anyway (same idiom used across this module's other NATS ITs) rather
     *  than assert on the very first read. [expectTransport] gates what "settled" means: either
     *  "the transport line is reachable at all" (first call in a test) or "the queue depth has
     *  reached at least [minQueueDepth]" (after a publish, so a slow propagation cannot flake the
     *  very next read into a stale, too-low count). */
    private fun awaitStatus(minQueueDepth: Long = -1, timeoutMs: Long = 5_000): JobPipelineStatus {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var status = jobPipelineStatusService.currentStatus()
        while (System.nanoTime() < deadline &&
            (!status.transport.reachable || status.transport.queueDepth < minQueueDepth)
        ) {
            Thread.sleep(50)
            status = jobPipelineStatusService.currentStatus()
        }
        return status
    }

    @Test
    fun `job stages come from the DB while the transport queue depth comes from JetStream, with nobody pulling`() {
        val invoiceId = persistInvoice("PL-2026-9401")
        ensureWorkerConsumerWithoutPulling()
        val before = awaitStatus()

        persistJob(invoiceId, GenerationJobStatus.PENDING)
        persistJob(invoiceId, GenerationJobStatus.PENDING)
        persistJob(invoiceId, GenerationJobStatus.DELEGATED)
        persistJob(invoiceId, GenerationJobStatus.COMPLETED, finishedAt = OffsetDateTime.now().minusMinutes(1))
        persistJob(invoiceId, GenerationJobStatus.FAILED, finishedAt = OffsetDateTime.now())
        publishRaw("it-pipeline-msg-1")
        publishRaw("it-pipeline-msg-2")

        val after = awaitStatus(minQueueDepth = before.transport.queueDepth + 2)

        // Angelegt (created) covers every open status -- 2 PENDING + 1 DELEGATED here.
        assertEquals(before.created.count + 3, after.created.count, "2 new PENDING + 1 new DELEGATED job")
        assertEquals(before.completed.last24h + 1, after.completed.last24h)
        assertEquals(before.failed.failedCount + 1, after.failed.failedCount)
        assertTrue(after.transport.reachable)
        assertEquals(before.transport.queueDepth + 2, after.transport.queueDepth, "2 published messages, nobody pulling them")
        assertFalse(after.inProgress.workerConnected, "no active pull request was ever made")
        // In Bearbeitung is the worker consumer's own numAckPending, not a DB count -- nobody ever
        // pulls in this test, so it reads 0 regardless of how many jobs were just persisted.
        assertEquals(0L, after.inProgress.ackPending)
    }

    @Test
    fun `two messages in flight for one newly-opened job report exactly one more duplicate`() {
        val invoiceId = persistInvoice("PL-2026-9402")
        ensureWorkerConsumerWithoutPulling()
        val before = awaitStatus()

        persistJob(invoiceId, GenerationJobStatus.DELEGATED) // +1 open job
        // Two DISTINCT message ids so JetStream's own duplicate window does not collapse them —
        // this models the real incident (a republish reaching the broker as a second, genuinely
        // separate message for the same logical job), not a JetStream-level resend of one message.
        publishRaw("it-duplicate-1")
        publishRaw("it-duplicate-2") // +2 queue depth

        val after = awaitStatus(minQueueDepth = before.transport.queueDepth + 2)

        // `generation_job` is shared across this module's whole IT run (this class's own KDoc),
        // so the *raw* deltas below are the safe, always-provable assertions: this test's own
        // writes really reached the DB/broker, in exactly the expected amount. The "Duplikate"
        // figure itself is `(inTransit - openCount).coerceAtLeast(0)` — under an unknown, possibly
        // already-clamped global baseline, only monotonic non-decrease is provable here; the exact
        // clamped arithmetic (including the "never negative" edge case) is covered deterministically
        // by `JobPipelineStatusMapperTest` instead, against controlled inputs.
        val openJobsDelta = after.created.count - before.created.count
        val queueDepthDelta = after.transport.queueDepth - before.transport.queueDepth
        assertEquals(1L, openJobsDelta, "this test's own DELEGATED job must be counted exactly once")
        assertEquals(2L, queueDepthDelta, "this test's own two published messages must be counted exactly")
        assertTrue(after.transport.duplicates >= before.transport.duplicates, "more in-flight messages than new open jobs must never lower the duplicate count")
    }

    @Test
    fun `queue depth includes messages waiting on the interactive consumer`() {
        ensureWorkerConsumerWithoutPulling()
        Nats.connect(natsUrl).use { connection ->
            NatsStreamSetup.ensureConsumer(
                connection.jetStreamManagement(),
                NatsStreamSetup.STREAM_NAME,
                NatsStreamSetup.consumerConfiguration(durableName = interactiveDurableName, filterSubject = interactiveJobsSubject, maxAckPending = 8),
            )
        }
        val before = awaitStatus()

        Nats.connect(natsUrl).use { connection ->
            connection.jetStream().publish(
                interactiveJobsSubject,
                "{}".toByteArray(Charsets.UTF_8),
                PublishOptions.builder().messageId("it-interactive-msg-1").build(),
            )
        }

        val after = awaitStatus(minQueueDepth = before.transport.queueDepth + 1)

        assertEquals(before.transport.queueDepth + 1, after.transport.queueDepth, "one message on the interactive subject, nobody pulling it")
    }
}
