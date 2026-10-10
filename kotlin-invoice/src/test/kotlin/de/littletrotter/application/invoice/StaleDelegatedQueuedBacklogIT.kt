package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.asMutableForTest

import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.Money
import de.littletrotter.shared.NatsStreamSetup
import io.nats.client.FetchConsumeOptions
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
import java.time.OffsetDateTime
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * The fix's own acceptance: "a `DELEGATED` job is re-published only when its message no
 * longer exists in the stream." Scenario 1 is the mandatory red-before-green proof (its own
 * red-only predecessor, backdating `createdAt` against unfixed `main`, is recorded verbatim in
 * this package's progress log); scenarios 2/3 exercise the other two branches of the fix's own
 * decision order against a real broker.
 *
 * Own, isolated NATS Testcontainer (`@QuarkusTestResource(NatsTestResource::class)`, not the
 * shared JVM-wide one other ITs in this module reuse) — scenario 3 deletes and recreates the whole
 * `document-jobs` stream, which would break any other test class sharing it.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(NatsTestResource::class)
class StaleDelegatedQueuedBacklogIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobProcessor: GenerationJobProcessor

    @ConfigProperty(name = "nats.url")
    lateinit var natsUrl: String

    @ConfigProperty(name = "nats.subject.document-jobs-interactive", defaultValue = "document-jobs-interactive")
    lateinit var interactiveJobsSubject: String

    companion object {
        private const val JOB_COUNT = 50
        // GenerationJobProcessor.resetStaleDelegatedJobs hardcodes staleCutoffMinutes = 2 -- every
        // fixture job's delegatedAt is backdated well past that so the very first tick already
        // finds it a candidate, without waiting two real minutes.
        private const val BACKDATE_MINUTES = 10L
    }

    private fun persistInvoice(number: String, invoicePrefixValue: String): Invoice = QuarkusTransaction.requiringNew().call<Invoice> {
        val sender = SenderProfile().apply {
            name = "Muster GmbH"
            email = Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            invoicePrefix = invoicePrefixValue
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
            code = "SB_TEST_19_$number"
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
        invoice
    }

    /** Real CDI `createJob` -> `GenerationJobCreated` -> the real async fast path -> a real
     *  `tryMarkDelegated` + `DocumentJobPublishTransaction.publishTransactional` publish, on the real
     *  managed executor — the same path production uses, not a hand-set DELEGATED field, so
     *  `delegatedAt`/`delegatedStreamSeq` are both genuinely populated by the real ack callback.
     *  [prefix] is only for the human-readable invoice number; [SenderProfile.invoicePrefix]'s own
     *  10-char-unique column is derived from a fresh random salt per call — this test class's three
     *  scenarios share one Postgres container (`@QuarkusTestResource` is class-scoped), and
     *  deriving it from a fixed substring of the invoice number (e.g. its last 4 digits) collided
     *  across scenarios whose invoice numbers only differ earlier in the string. */
    private fun createDelegatedJobs(count: Int, prefix: String): List<Long> {
        val salt = java.util.UUID.randomUUID().toString().take(4)
        val jobIds = (1..count).map { idx ->
            val number = "$prefix-2026-${idx.toString().padStart(4, '0')}"
            val invoice = persistInvoice(number, "S$salt${idx.toString().padStart(4, '0')}")
            generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK).id!!
        }
        // Only the fast path's own async CDI-event dispatch (createJob -> onGenerationJobCreated
        // -> managedExecutor) is eventually consistent here -- publishOne itself now records
        // delegatedStreamSeq synchronously, so DELEGATED already implies
        // a real sequence is recorded, not just "eventually will be".
        val allDelegated = waitUntil(30) { countByStatus(jobIds, GenerationJobStatus.DELEGATED) == count.toLong() }
        assertTrue(allDelegated, "every fixture job must reach DELEGATED via the real fast path before this test proceeds")
        return jobIds
    }

    private fun countByStatus(jobIds: List<Long>, status: GenerationJobStatus): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            (em.createQuery(
                "SELECT COUNT(j) FROM GenerationJob j WHERE j.id IN :ids AND j.status = :status",
                Long::class.javaObjectType,
            ).setParameter("ids", jobIds).setParameter("status", status).singleResult) ?: 0L
        }

    private fun countWithAttempts(jobIds: List<Long>, attempts: Int): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            (em.createQuery(
                "SELECT COUNT(j) FROM GenerationJob j WHERE j.id IN :ids AND j.attempts = :attempts",
                Long::class.javaObjectType,
            ).setParameter("ids", jobIds).setParameter("attempts", attempts).singleResult) ?: 0L
        }

    private fun backdateDelegatedAt(jobIds: List<Long>, minutes: Long): Unit = QuarkusTransaction.requiringNew().run {
        em.createQuery("UPDATE GenerationJob j SET j.delegatedAt = :past WHERE j.id IN :ids")
            .setParameter("past", OffsetDateTime.now().minusMinutes(minutes))
            .setParameter("ids", jobIds)
            .executeUpdate()
    }

    private fun delegatedStreamSeqOf(jobId: Long): Long? = QuarkusTransaction.requiringNew().call<Long?> {
        em.find(de.littletrotter.domain.invoice.GenerationJob::class.java, jobId).delegatedStreamSeq
    }

    private fun statusAndAttemptsOf(jobId: Long): Pair<GenerationJobStatus, Int> =
        QuarkusTransaction.requiringNew().call<Pair<GenerationJobStatus, Int>> {
            val job = em.find(de.littletrotter.domain.invoice.GenerationJob::class.java, jobId)
            job.status to job.attempts
        }

    private fun waitUntil(timeoutSeconds: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun streamMessageCount(): Long =
        Nats.connect(natsUrl).use { it.jetStreamManagement().getStreamInfo(NatsStreamSetup.STREAM_NAME).streamState.msgCount }

    /** Creates the real `kotlin-xpdf-group` durable consumer and drains (never acks) every
     *  currently available message, so `ConsumerInfo.delivered.streamSequence` genuinely advances
     *  past every fixture message's own sequence — every pulled-but-unacked message stays in the
     *  stream, awaiting redelivery, exactly the `IN_FLIGHT` case [StaleDelegatedResetDecider]
     *  itself distinguishes from a message that is truly gone. [minCount] only sizes `maxAckPending`
     *  generously (never a strict cap): a durable consumer newly created on a shared stream starts
     *  from the oldest still-retained message, which — depending on which of this class's other
     *  scenarios already ran — may be an earlier scenario's own leftover backlog rather than this
     *  call's own fixture messages, so this drains everything currently available (one `fetch()`,
     *  sized comfortably above any realistic backlog across this class's 3 scenarios) instead of
     *  stopping at a fixed count. */
    private fun advanceDeliveredCursorPastEveryFixtureMessage(minCount: Int) {
        Nats.connect(natsUrl).use { conn ->
            val jsm = conn.jetStreamManagement()
            val batchSize = minCount * 4
            NatsStreamSetup.ensureConsumer(
                jsm,
                NatsStreamSetup.STREAM_NAME,
                NatsStreamSetup.consumerConfiguration(
                    durableName = "kotlin-xpdf-group",
                    filterSubject = "document-jobs",
                    maxAckPending = batchSize,
                ),
            )
            val consumerContext = conn.jetStream().getStreamContext(NatsStreamSetup.STREAM_NAME).getConsumerContext("kotlin-xpdf-group")
            val fetch = consumerContext.fetch(FetchConsumeOptions.builder().maxMessages(batchSize).expiresIn(3_000).build())
            fetch.use { while (it.nextMessage() != null) { /* drain, never ack */ } }
        }
    }

    @Test
    fun `a backlog with no consumer pulling never grows the stream and never burns an attempt`() {
        val jobIds = createDelegatedJobs(JOB_COUNT, "SB1")
        backdateDelegatedAt(jobIds, BACKDATE_MINUTES)
        val messagesBefore = streamMessageCount()
        assertTrue(messagesBefore >= JOB_COUNT, "every fixture job must have a real, distinct message in the stream")

        // Three ticks, no consumer ever attached to `kotlin-xpdf-group` -- every candidate's
        // recorded sequence stays ahead of the (non-existent) consumer's delivered cursor, so
        // StaleDelegatedResetDecider must skip every single one of them (STILL_QUEUED), never
        // reset, never touch the broker's per-message existence check at all. Unlike the red-only
        // predecessor of this test, no real waiting is needed here: a skipped candidate never
        // calls publish, so there is nothing for JetStream's own Nats-Msg-Id dedup to even mask.
        repeat(3) { generationJobProcessor.pollPendingJobs() }

        val messagesAfter = streamMessageCount()
        assertEquals(messagesBefore, messagesAfter, "no consumer pulling must never grow the stream — the ST-A6 regression")
        assertEquals(JOB_COUNT.toLong(), countByStatus(jobIds, GenerationJobStatus.DELEGATED), "every job must still be DELEGATED")
        assertEquals(JOB_COUNT.toLong(), countWithAttempts(jobIds, 0), "no worker ever touched any of these messages — attempts must stay 0")
    }

    @Test
    fun `deleting one job's message from the stream resets exactly that job once`() {
        val jobIds = createDelegatedJobs(JOB_COUNT, "SB2")
        backdateDelegatedAt(jobIds, BACKDATE_MINUTES)
        val targetId = jobIds[JOB_COUNT / 2]
        val targetSeq = requireNotNull(delegatedStreamSeqOf(targetId)) { "the real publish ack must have recorded a sequence" }

        // StaleDelegatedResetDecider only ever calls DelegatedMessageProbe.exists() for a
        // candidate the consumer's own delivered cursor has already passed (see its own KDoc) --
        // "deleted but never delivered" is exactly the STILL_QUEUED case, not MESSAGE_GONE. Pull
        // (without acking) so every fixture message is genuinely handed out at least once first,
        // matching what "the message is gone" actually presupposes: a worker had it.
        advanceDeliveredCursorPastEveryFixtureMessage(JOB_COUNT)
        try {
            Nats.connect(natsUrl).use { it.jetStreamManagement().deleteMessage(NatsStreamSetup.STREAM_NAME, targetSeq) }

            // pollPendingJobs runs synchronously: its own tick both resets (message gone -> PENDING,
            // attempts+1) and re-publishes (PENDING -> DELEGATED with a fresh delegatedStreamSeq) in
            // the same pass, so the effect is already committed once this call returns.
            generationJobProcessor.pollPendingJobs()
        } finally {
            // The module's own e2e/IT convention: never leave a durable consumer behind for a
            // later scenario in this class (a shared NATS container) to inherit a stale delivered
            // cursor from.
            Nats.connect(natsUrl).use { it.jetStreamManagement().deleteConsumer(NatsStreamSetup.STREAM_NAME, "kotlin-xpdf-group") }
        }

        val (targetStatus, targetAttempts) = statusAndAttemptsOf(targetId)
        assertEquals(GenerationJobStatus.DELEGATED, targetStatus, "the reset job must have been re-published, not left PENDING")
        assertEquals(1, targetAttempts, "exactly one reset -> exactly one burned attempt")

        // The poller's own republish records delegatedStreamSeq synchronously --
        // pollPendingJobs() above already returned, so no polling wait is needed here.
        // Not asserted against targetSeq: this republish reuses the same stable Nats-Msg-Id
        // ("job-$targetId", AP-M2) as the original publish, seconds earlier in this same test --
        // still well inside the broker's own duplicate window (NatsStreamSetup
        // .MAIN_DUPLICATE_WINDOW, 2 minutes, the same magnitude as staleCutoffMinutes by design,
        // see that constant's own KDoc), so JetStream legitimately collapses it to a duplicate ack
        // carrying the ORIGINAL seqno rather than minting a new one -- exactly the scenario
        // GenerationJobFastPublishTransactionTest's own "records the seqno of a duplicate ack" test covers
        // directly. A real production stale-reset never races its own dedup window this way (its
        // own cutoff is old enough that the window has already closed by the time it fires).
        assertTrue(delegatedStreamSeqOf(targetId) != null, "the re-publish's ack must have been recorded")

        val untouchedIds = jobIds - targetId
        assertEquals(
            untouchedIds.size.toLong(), countByStatus(untouchedIds, GenerationJobStatus.DELEGATED),
            "every other job's own message is still genuinely queued -- none of them may be touched",
        )
        assertEquals(untouchedIds.size.toLong(), countWithAttempts(untouchedIds, 0))
    }

    @Test
    fun `a purged and recreated stream resets every still-queued job exactly once`() {
        val jobIds = createDelegatedJobs(JOB_COUNT, "SB3")
        backdateDelegatedAt(jobIds, BACKDATE_MINUTES)

        Nats.connect(natsUrl).use { conn ->
            val jsm = conn.jetStreamManagement()
            jsm.deleteStream(NatsStreamSetup.STREAM_NAME)
            // the recreated stream must keep every production subject: the container is shared with later ITs
            NatsStreamSetup.ensureStream(
                jsm,
                listOf("document-jobs", interactiveJobsSubject, "document-job-results", NatsStreamSetup.OFFER_RESULTS_SUBJECT),
            )
        }

        generationJobProcessor.pollPendingJobs()

        assertEquals(
            JOB_COUNT.toLong(), countWithAttempts(jobIds, 1),
            "every job's own recorded sequence is now higher than the recreated stream's last sequence -- all reset exactly once",
        )
        assertEquals(JOB_COUNT.toLong(), countByStatus(jobIds, GenerationJobStatus.DELEGATED), "every job must have been re-published after its reset")
    }
}
