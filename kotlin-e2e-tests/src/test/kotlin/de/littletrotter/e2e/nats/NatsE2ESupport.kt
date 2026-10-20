package de.littletrotter.e2e.nats

import de.littletrotter.application.shared.DomainEventDispatcher
import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.infrastructure.messaging.GenerationJobNatsAdapter
import de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer
import de.littletrotter.shared.NatsStreamSetup
import de.littletrotter.xpdf.infrastructure.messaging.JobConsumer
import de.littletrotter.xpdf.infrastructure.messaging.JobDispatcher
import de.littletrotter.xpdf.infrastructure.messaging.JobResultReporter
import de.littletrotter.xpdf.infrastructure.messaging.PoisonMessageHandler
import de.littletrotter.xpdf.infrastructure.messaging.QuoteJobProcessor
import de.littletrotter.xpdf.infrastructure.messaging.ResultPublisher
import io.nats.client.Connection
import io.nats.client.ConsumeOptions
import io.nats.client.JetStream
import io.nats.client.MessageConsumer
import io.nats.client.PurgeOptions
import io.nats.client.api.ConsumerConfiguration
import jakarta.enterprise.event.Event
import org.mockito.kotlin.mock
import java.util.concurrent.ConcurrentHashMap
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.edit

/**
 * Shared plumbing for the NATS E2E suites in this package. Every helper here wires up a
 * REAL production class (`JobConsumer`, `ResultPublisher`, `DocumentJobPublisher`,
 * `GenerationJobNatsAdapter`, `GenerationJobResultConsumer`, `GenerationJobService`,
 * `NatsStreamSetup`) — the only substitutions are:
 *
 *  - [PdfExportPort][de.littletrotter.shared.PdfExportPort]'s inner render step (per the suite's own
 *    constraint: "substitute only performExport's inner render step, never the messaging layer");
 *  - [RecordingGenerationJobRepository], a plain in-memory `GenerationJobRepository` behind the
 *    real `GenerationJobService`/`GenerationJobResultConsumer` — the exact same substitution
 *    `GenerationJobResultConsumerIT` (kotlin-invoice) already uses. This is a domain-repository
 *    port, not a messaging component, and is the documented way around the finding that
 *    booting the full Quarkus `kotlin-invoice` app inside this module fails with a `LinkageError`
 *    on kotlin-shared enums.
 *
 * fix-t (2026-09-18) — job-id ranges in use across this package's suites, kept disjoint on
 * purpose: JetStream's publish-side `Nats-Msg-Id` dedup (`"job-$jobId"`, `DocumentJobPublisher`)
 * is stream-level and survives a `purgeSubject` for the shared stream's full 2-minute duplicate
 * window (`NatsStreamSetup.MAIN_DUPLICATE_WINDOW`) — two suites racing the same literal id within
 * that window silently drops whichever publishes second as a duplicate ack (see
 * `NatsBurstStaleDispatchE2EIT`'s own KDoc for the incident this caused). Pick a new, disjoint
 * range before adding another suite:
 *  - `NatsRoundTripE2EIT`: `900_001L`
 *  - `NatsDedupE2EIT`: `900_002L`
 *  - `NatsRedeliveryE2EIT`: `900_003L`
 *  - `NatsPoisonDlqE2EIT`: `900_004L`
 *  - `NatsWorkerRecoveryE2EIT`: `900_005L`, `900_006L`
 *  - `NatsLlmCheckE2EIT`: `900_101L`, `900_102L`
 *  - `NatsBurstStaleDispatchE2EIT`: `900_200L`..`900_203L`
 *  - `NatsChecksumPersistenceE2EIT`: `900_301L`
 */
object NatsE2ESupport {

    /** Minimal but real [Invoice] — same shape `DocumentJobPublisherIT` (kotlin-invoice) already
     *  builds for its own publisher tests. No line items: none of the six scenarios assert on
     *  item-level wire content. */
    fun newInvoice(id: Long, invoiceNumber: String): Invoice =
        anInvoice().edit {
            this.id = id
            this.invoiceNumber = InvoiceNumber(invoiceNumber)
            senderProfile = aSenderProfile {
                name = "E2E Sender GmbH"
                vatId = VatId("DE111111111")
            }
            recipient = aRecipient {
                name = "E2E Recipient AG"
                vatId = VatId("DE222222222")
            }
        }

    /** The real publisher (kotlin-invoice), wired against this module's own JetStream and the
     *  real production stream name/subject — never a re-implemented double of the wire format. */
    fun newPublisher(jobsSubject: String = NatsE2EExtension.JOBS_SUBJECT): DocumentJobPublisher =
        DocumentJobPublisher(
            jetStreamProvider = { NatsE2EExtension.jetStream() },
            jobsSubjectOverride = jobsSubject,
            expectedStreamOverride = NatsStreamSetup.STREAM_NAME,
        )

    /**
     * The real worker consumer (kotlin-xpdf), with a real [ResultPublisher] and the real shared
     * [Connection]/[JetStream]. [dlqSubject] is a plain constructor parameter on
     * `PoisonMessageHandler` (CDI resolves the same value via `@ConfigProperty` in production;
     * this plain Kotlin construction just passes it directly): no reflection seam needed any more.
     */
    fun newWorkerConsumer(
        pdfExportPort: de.littletrotter.shared.PdfExportPort,
        dlqSubject: String = NatsE2EExtension.JOBS_DLQ_SUBJECT,
        payloadExchange: de.littletrotter.shared.PayloadExchange = mock(),
        resultPublisher: ResultPublisher = ResultPublisher(NatsE2EExtension.jetStream()),
    ): JobConsumer {
        val jetStream = NatsE2EExtension.jetStream()
        val poisonMessageHandler = PoisonMessageHandler(resultPublisher, jetStream, dlqSubject)
        val jobDispatcher = JobDispatcher(pdfExportPort, payloadExchange, JobResultReporter(resultPublisher))
        // Offer jobs are not exercised on this seam; the processor only has to exist.
        val quoteJobProcessor = QuoteJobProcessor(mock(), resultPublisher)
        return JobConsumer(NatsE2EExtension.connection, jetStream, poisonMessageHandler, jobDispatcher, quoteJobProcessor)
    }

    /**
     * Creates/updates a durable pull consumer for [jobsSubject] on the real stream via the real
     * [NatsStreamSetup.ensureConsumer] (never a hand-rolled `ConsumerConfiguration`), and wires it
     * to [consumer]'s own `receive()` — the same `StreamContext -> ConsumerContext -> consume()`
     * shape `JobConsumer.subscribe()` itself uses; only the durable name/ackWait/maxDeliver differ
     * per scenario, since production hardcodes those to `kotlin-xpdf-group`/120s.
     */
    fun subscribeWorker(
        consumer: JobConsumer,
        durableName: String,
        jobsSubject: String = NatsE2EExtension.JOBS_SUBJECT,
        ackWaitSeconds: Long = 30,
        maxDeliver: Long = 5,
        maxAckPending: Int = 4,
        batchSize: Int = 4,
    ): MessageConsumer {
        val jsm = NatsE2EExtension.jetStreamManagement()
        NatsStreamSetup.ensureConsumer(
            jsm,
            NatsStreamSetup.STREAM_NAME,
            NatsStreamSetup.consumerConfiguration(
                durableName = durableName,
                filterSubject = jobsSubject,
                maxAckPending = maxAckPending,
                ackWaitSeconds = ackWaitSeconds,
            ).let {
                // maxDeliver has no NatsStreamSetup.consumerConfiguration parameter (production
                // never overrides it); scenario 3/4 need a small, explicit cap so a message that
                // can never succeed does not redeliver indefinitely inside the test's own timeout.
                ConsumerConfiguration.builder(it).maxDeliver(maxDeliver).build()
            },
        )
        val consumerContext = NatsE2EExtension.jetStream()
            .getStreamContext(NatsStreamSetup.STREAM_NAME)
            .getConsumerContext(durableName)
        val consumeOptions = ConsumeOptions.builder().batchSize(batchSize).expiresIn(2_000).build()
        return consumerContext.consume(consumeOptions) { msg -> consumer.receive(msg) }
    }

    /** Removes a durable consumer created by [subscribeWorker]/[subscribeAppAdapter] — required
     *  cleanup: the real `LITTLE_TROTTER_JOBS`/`LITTLE_TROTTER_DLQ` streams use WorkQueue-style
     *  filter semantics that reject a second durable with an overlapping filter subject while an
     *  earlier one still exists, so each scenario must tidy up its own durable before the next
     *  scenario in this module claims the same subject. */
    fun deleteConsumer(streamName: String, durableName: String) {
        runCatching { NatsE2EExtension.jetStreamManagement().deleteConsumer(streamName, durableName) }
    }

    /**
     * Purges every message on [subject] from the shared main stream — required test-isolation
     * cleanup for any scenario whose message can be left permanently unacked on the real
     * WorkQueue-retention `LITTLE_TROTTER_JOBS` stream (e.g. the redelivery scenario's
     * deliberately-never-succeeding job, or a poison message whose ack races the test's own
     * assertions). WorkQueue retention only removes a message once it is acked; an exhausted
     * `maxDeliver` does NOT remove it — it simply stops that one consumer from redelivering it,
     * so it stays there ready to be handed to the NEXT durable consumer any later scenario in this
     * module creates on the same `document-jobs` subject. Never fails the calling test: called
     * from `@AfterEach`, where an exception would mask the test's own real outcome.
     */
    fun purgeSubject(subject: String, streamName: String = NatsStreamSetup.STREAM_NAME) {
        runCatching {
            NatsE2EExtension.jetStreamManagement().purgeStream(streamName, PurgeOptions.builder().subject(subject).build())
        }
    }

    /**
     * The real app-side adapter (kotlin-invoice), reflectively wired the same way
     * `GenerationJobNatsAdapterTest` already does for its own unit tests — `@Inject`/
     * `@ConfigProperty` fields never resolve outside a CDI container, so a plain
     * `GenerationJobNatsAdapter()` construction has to set them by hand. `requestContextFactory` is
     * overridden to a no-op `AutoCloseable`: the real default activates an ArC request context via
     * `Arc.container()`, which does not exist in this plain-JUnit E2E module (see that field's own
     * KDoc in `GenerationJobNatsAdapter`).
     *
     * fix-t (2026-09-18) — builds the durable pull consumer itself (the same `ensureConsumer` +
     * `consumerContext.consume` shape [subscribeWorker] already uses for the worker side) instead
     * of delegating to [GenerationJobNatsAdapter.subscribe], which performs the identical setup
     * internally but returns `Unit` — the resulting [MessageConsumer] was never reachable, so no
     * caller could ever close it. See [closeConsumer]'s own KDoc for why every suite in this
     * package must close the handle this now returns.
     */
    fun subscribeAppAdapter(
        resultConsumer: GenerationJobResultConsumer,
        durableName: String,
        resultsSubject: String = NatsE2EExtension.RESULTS_SUBJECT,
        dlqSubject: String = NatsE2EExtension.RESULTS_DLQ_SUBJECT,
        maxAckPending: Int = 16,
    ): MessageConsumer {
        // Config travels through the constructor since the CDI-Architektur-Paket (PO 29.08.):
        // the four @param:ConfigProperty values are constructor parameters now, only the
        // field-injected collaborators still need reflection here.
        val adapter = GenerationJobNatsAdapter(
            resultsSubject = resultsSubject,
            dlqSubject = dlqSubject,
            durableConsumerName = durableName,
            resultConsumerEnabled = true,
        )
        injectField(adapter, "connection", NatsE2EExtension.connection)
        injectField(adapter, "jetStream", NatsE2EExtension.jetStream())
        injectField(adapter, "resultConsumer", resultConsumer)
        val noopContextFactory: () -> AutoCloseable = { AutoCloseable { } }
        injectField(adapter, "requestContextFactory", noopContextFactory)

        val jsm = NatsE2EExtension.jetStreamManagement()
        NatsStreamSetup.ensureConsumer(
            jsm,
            NatsStreamSetup.STREAM_NAME,
            NatsStreamSetup.consumerConfiguration(
                durableName = durableName,
                filterSubject = resultsSubject,
                maxAckPending = maxAckPending,
            ),
        )
        val consumerContext = NatsE2EExtension.jetStream()
            .getStreamContext(NatsStreamSetup.STREAM_NAME)
            .getConsumerContext(durableName)
        return consumerContext.consume { msg -> adapter.receive(msg) }
    }

    /**
     * fix-t (2026-09-18) — synchronously tears down a [MessageConsumer]'s pull subscription and
     * cancels its idle-heartbeat timer. Required cleanup for every consumer [subscribeWorker]/
     * [subscribeAppAdapter] return: jnats's own `MessageConsumer.stop()` only *requests* a stop —
     * the actual unsubscribe/heartbeat-timer teardown (`NatsMessageConsumerBase.shutdownSub`) is
     * applied lazily, from inside whatever pull callback the subscription happens to receive next.
     * Once a scenario's `@AfterEach` deletes its durable (every `@AfterEach` in this package does),
     * that next callback may never arrive — a bare `.stop()` then leaves the consumer's own
     * idle-heartbeat timer running forever on jnats's single shared per-connection scheduler
     * (`heartbeatAlarm`/`pullStatusError` log spam for a durable that no longer exists), delaying
     * that scheduler's delivery of legitimate callbacks to every later scenario's own, still-live
     * consumers sharing the same [NatsE2EExtension.connection] — the root cause of the 2026-09-18
     * `NatsLlmCheckE2EIT` suite-order flake (`nats-llm-check-e2e-suite-order.md`). `.close()`
     * (`NatsMessageConsumerBase.close`) performs that teardown immediately instead: unsubscribe,
     * then `pmm.shutdownHeartbeatTimer()`. Never fails the calling test — same idiom as
     * [deleteConsumer]/[purgeSubject], and must run *before* [deleteConsumer] so the client side is
     * fully torn down before the durable it was reading from disappears server-side.
     */
    fun closeConsumer(consumer: MessageConsumer) {
        runCatching { consumer.close() }
    }

    /**
     * `GenerationJobService` (a pure delegation facade) no longer exists in kotlin-invoice;
     * this builds the same real collaborators it used to build internally
     * ([de.littletrotter.application.invoice.GenerationJobQueryService]/`GenerationJobResultHandler`/`GenerationJobLlmResultHandler`/
     * `GenerationJobRecoveryService`, all public — unlike `NoOpInvoiceRepository`,
     * `internal` to kotlin-invoice and not reachable from here, hence the plain mocks below)
     * behind a fresh [RecordingGenerationJobRepository], matching `GenerationJobResultConsumerIT`'s
     * own established pattern (kotlin-invoice) for exercising the result-consumption path without
     * a real database. Every seeded [GenerationJob] has `invoice == null`, so
     * `GenerationJobResultHandler.markCompleted`/`GenerationJobRecoveryService.markFailed` never reach a
     * code path that touches the mocked [de.littletrotter.domain.repository.InvoiceRepository].
     * `ValidationFindingRepository` is no longer a dependency of any of these at all:
     * finding persistence moved to `DocumentGeneratedReactionHandler`, which this module's
     * scenarios never construct/observe.
     */
    fun newAppResultConsumer(repo: RecordingGenerationJobRepository): GenerationJobResultConsumer {
        val invoiceRepo = mock<de.littletrotter.domain.repository.InvoiceRepository>()
        val eventDispatcher = de.littletrotter.application.shared.DomainEventDispatcher(mock<Event<DomainEvent>>(), null)
        val queryService = de.littletrotter.application.invoice.GenerationJobQueryService(repo)
        val time = de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z"))
        val resultHandler = de.littletrotter.application.invoice.GenerationJobResultHandler(
            repo, eventDispatcher, time = time,
            checksumPersister = de.littletrotter.application.invoice.DocumentChecksumPersister(time, mock()),
        )
        val llmResultHandler = de.littletrotter.application.invoice.GenerationJobLlmResultHandler(
            repo, invoiceRepo, eventDispatcher, time,
        )
        val recoveryService = de.littletrotter.application.invoice.GenerationJobRecoveryService(
            repo, invoiceRepo, eventDispatcher, llmResultHandler, time,
        )
        return GenerationJobResultConsumer(queryService, resultHandler, llmResultHandler, recoveryService)
    }

    /** A DELEGATED SEND-triggered job bound to a real [newInvoice]: SEND makes it an issued
     *  document, so `GenerationJobResultHandler.markCompleted` runs the checksum persister for it
     *  (a job without an invoice never does). Synthetic data only. */
    fun newSendJobWithInvoice(jobId: Long, invoiceNumber: String): GenerationJob =
        aGenerationJob {
            id = jobId
            status = GenerationJobStatus.DELEGATED
            trigger = de.littletrotter.domain.invoice.GenerationJobTrigger.SEND
            invoice = newInvoice(jobId, invoiceNumber)
        }

    fun injectField(target: Any, name: String, value: Any) {
        val field = target::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    /** Busy-waits (short sleeps) until [condition] holds or [timeoutSeconds] elapses; returns
     *  whether it held. Preferred over `CountDownLatch` here because most conditions are "poll an
     *  in-memory map/broker state", not "a single callback fired". */
    fun waitUntil(timeoutSeconds: Long, pollMs: Long = 100, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(pollMs)
        }
        return condition()
    }
}

/**
 * In-memory [GenerationJobRepository] recording every [save] call — the same substitution
 * `GenerationJobResultConsumerIT` (kotlin-invoice) already uses to exercise the real
 * `GenerationJobService`/`GenerationJobResultConsumer` without a database. Thread-safe
 * ([ConcurrentHashMap]): the worker side runs its own message-handler threads concurrently with
 * whatever polls this repository's state from the test's main thread.
 */
class RecordingGenerationJobRepository(vararg jobs: GenerationJob) : GenerationJobRepository {
    private val byId = ConcurrentHashMap<Long, GenerationJob>().apply {
        jobs.forEach { put(requireNotNull(it.id) { "seed job needs an id" }, it) }
    }
    val saveCount = java.util.concurrent.atomic.AtomicInteger(0)

    fun put(job: GenerationJob) {
        byId[requireNotNull(job.id) { "job needs an id" }] = job
    }

    override fun findByInvoiceId(invoiceId: Long): List<GenerationJob> =
        byId.values.filter { it.invoice?.id == invoiceId }

    override fun findByInvoiceIdWithTaxRates(invoiceId: Long): List<GenerationJob> = findByInvoiceId(invoiceId)

    override fun findPending(): List<GenerationJob> =
        byId.values.filter { it.status == GenerationJobStatus.PENDING }

    override fun findPendingWithInvoice(): List<GenerationJob> = findPending()

    override fun findById(id: Long): GenerationJob? = byId[id]

    override fun save(job: GenerationJob): GenerationJob {
        saveCount.incrementAndGet()
        byId[requireNotNull(job.id) { "job needs an id" }] = job
        return job
    }

    override fun tryMarkDelegated(id: Long): Boolean {
        val job = byId[id] ?: return false
        if (job.status != GenerationJobStatus.PENDING) return false
        job.transitionTo(GenerationJobStatus.DELEGATED)
        return true
    }
}
