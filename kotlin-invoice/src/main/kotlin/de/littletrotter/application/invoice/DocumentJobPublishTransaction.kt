package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.DocumentJobLane
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import de.littletrotter.application.shared.SetsAuditGuc
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.application.reference.ValidationSettingsGate
import de.littletrotter.application.settings.OutputIntentSettingGate
import de.littletrotter.application.settings.WordingSettingsGate
import de.littletrotter.domain.reference.ValidationCapabilityId
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.documentjob.DocumentJobOptions
import de.littletrotter.shared.documentjob.DocumentJobRequest
import de.littletrotter.shared.documentjob.DocumentJobSubjects
import de.littletrotter.shared.documentjob.DocumentJobWireCodec
import de.littletrotter.shared.NatsStreamSetup
import de.littletrotter.shared.wording.WordingProfile
import io.micrometer.core.instrument.MeterRegistry
import io.nats.client.JetStream
import io.nats.client.PublishOptions
import io.nats.client.api.PublishAck
import io.nats.client.impl.Headers
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * PO-Grundsatz 29.08.2026 (CDI-Injektionen nie ausnehmen) — `DocumentJobPublisher.publishTransactional`
 * moved here, off [DocumentJobPublisher], when the former `Provider<DocumentJobPublisher>`
 * self-reference (`selfProvider`) was replaced by this dedicated collaborator bean: [publish
 * ][DocumentJobPublisher.publish]'s one retry attempt now calls back through this bean's own CDI
 * proxy — a genuinely distinct bean, injected normally — instead of a same-instance self-call, so
 * [DocumentJobPublishTransaction.publishTransactional]'s `@Transactional` is reached the ordinary way any cross-bean CDI call
 * reaches an interceptor. [DocumentJobPublisher] itself keeps only the retry-orchestration
 * (`publish`), the optimistic-lock detection, and the compatibility overload — every
 * collaborator [DocumentJobPublishTransaction.publishTransactional] itself actually touches (JetStream, the mapper, the engine
 * activation gate, the validation-settings gate, the invoice repository, the sent/failed counters)
 * lives here now.
 *
 * Every field/constructor below is carried over unchanged from [DocumentJobPublisher]'s own
 * pre-refactor shape — see the individual KDocs for why each collaborator is nullable/defaulted
 * the way it is; none of that reasoning changed by moving the class it lives on.
 */
// publishTransactional writes invoice.validationStatus (see the KDoc there); the
// action moved onto that method itself, this class carries no class-level marker anymore.
@ApplicationScoped
class DocumentJobPublishTransaction {
    private var jetStreamProvider: (() -> JetStream)? = null
    private var jobsSubjectOverride: String? = null
    private var interactiveSubjectOverride: String? = null
    /**
     * The stream name asserted via
     * `PublishOptions.expectedStream(...)` (see [DocumentJobPublishTransaction.publishTransactional]). Fixed to the real, single
     * production stream ([NatsStreamSetup.STREAM_NAME]) by the CDI-injected constructor below;
     * `null` by default on the test constructor, since the various Testcontainers-backed suites
     * each own a differently named stream of their own (`TEST_STREAM`) — asserting the production
     * name there would make every such publish fail with JetStream's own "expected stream does
     * not match" error. Tests that specifically want to exercise the assertion (or run against a
     * stream actually named [NatsStreamSetup.STREAM_NAME]) pass it explicitly.
     */
    private var expectedStream: String? = null
    private var meterRegistry: MeterRegistry? = null
    private var validationSettingsGate: ValidationSettingsGate? = null
    /** Z4 — the `Invoice` → `InvoiceDto` mapping, delegated wholesale to [InvoiceDtoMapper].
     *  Defaults to an unwired instance (no repositories/services) so every existing unit-test
     *  constructor call that never cared about mapping collaborators keeps compiling unchanged. */
    private var dtoMapper: InvoiceDtoMapper = InvoiceDtoMapper()
    private var engineActivation: EngineActivation? = null
    /** validation-status-truthful-pending.md R3 — used to flip the invoice to PENDING (a real
     *  process now exists) right before the job goes out. concurrency-fix — persisted via
     *  [InvoiceRepository.updateValidationStatus] (a targeted, version-unchecked bulk `UPDATE`),
     *  **not** an entity-level `save()`/merge: [DocumentJobPublishTransaction.publishTransactional] is reached both from an
     *  already-open transaction (fast path/poller, where `invoice` is a *managed* entity a plain
     *  merge would needlessly re-version-check) and from a fresh UI request where the passed-in
     *  [Invoice] may be *detached* and stale (a merge there compares its own stale `version`
     *  against the DB row and throws) — see [DocumentJobPublishTransaction.publishTransactional]'s own KDoc for the full
     *  incident this replaced. `null` in every plain unit test that doesn't wire one — the
     *  mutation on the passed-in [Invoice] instance still happens (and is asserted directly), only
     *  the persistence call itself is skipped. */
    private var invoiceRepository: InvoiceRepository? = null

    @Inject
    constructor(
        jetStream: JetStream,
        meterRegistry: MeterRegistry,
        validationSettingsGate: ValidationSettingsGate,
        mapper: InvoiceDtoMapper,
        engineActivation: EngineActivation,
        invoiceRepository: InvoiceRepository,
        wordingSettingsGate: WordingSettingsGate,
        outputIntentSettingGate: OutputIntentSettingGate? = null,
    ) {
        this.jetStreamProvider = { jetStream }
        this.expectedStream = NatsStreamSetup.STREAM_NAME
        this.meterRegistry = meterRegistry
        this.validationSettingsGate = validationSettingsGate
        this.dtoMapper = mapper
        this.engineActivation = engineActivation
        this.invoiceRepository = invoiceRepository
        this.wordingSettingsGate = wordingSettingsGate
        this.outputIntentSettingGate = outputIntentSettingGate
    }

    // PO-Grundsatz 29.08.2026 — no Kotlin default values on the first 7 params (unlike
    // DocumentJobPublisher's own test-seam constructor, which does still need them: its callers
    // genuinely omit arguments). DocumentJobPublisher.<init> is this constructor's only caller and
    // always forwards those 7 explicitly (its own already-resolved local values, never omitted) —
    // defaults there would only synthesize a Kotlin `$default` bridge constructor that no call
    // site would ever actually invoke, which is dead code the check-messaging-core 100 % gate
    // then rightly flagged as uncovered. wordingSettingsGate DOES default to null: unlike the
    // first 7, DocumentJobPublisher.<init> never has a value to forward for it (wiring it stays
    // wireWordingSettingsGateForTest's post-construction job, see the field's own KDoc), so this
    // default is the one genuinely reachable path, not a dead bridge..
    constructor(
        jetStreamProvider: (() -> JetStream)?,
        jobsSubjectOverride: String?,
        validationSettingsGate: ValidationSettingsGate?,
        expectedStreamOverride: String?,
        mapper: InvoiceDtoMapper?,
        engineActivation: EngineActivation?,
        invoiceRepository: InvoiceRepository?,
        wordingSettingsGate: WordingSettingsGate? = null,
    ) {
        this.jetStreamProvider = jetStreamProvider
        this.jobsSubjectOverride = jobsSubjectOverride
        this.validationSettingsGate = validationSettingsGate
        this.expectedStream = expectedStreamOverride
        this.dtoMapper = mapper ?: InvoiceDtoMapper()
        this.engineActivation = engineActivation
        this.invoiceRepository = invoiceRepository
        this.wordingSettingsGate = wordingSettingsGate
    }

    /** Constructor-injected on the @Inject constructor above (PO-Grundsatz 29.08.2026,
     *  CDI-Injektionen nie ausnehmen: field injection replaced, not suppressed), but kept as a
     *  plain `var` field rather than a constructor-only `val`: stamped onto the mapped
     *  [de.littletrotter.shared.InvoiceDto] at the moment the print/export job is actually
     *  published — never retroactive, an already-issued document keeps whichever bytes it was
     *  rendered with. `null` in a plain unit test that uses the test constructor's own default: the
     *  DTO then carries [WordingProfile.DEFAULT]'s own code, the same fail-open value the gate
     *  itself falls back to. `internal` (not `private`) purely so
     *  `DocumentJobPublisher.wireWordingSettingsGateForTest` can still replace it after
     *  construction — the one caller that mutates it post-construction rather than through either
     *  constructor. */
    internal var wordingSettingsGate: WordingSettingsGate? = null

    /** Supplies job option `outputIntent`. Defaulted to `null` on the @Inject constructor (CDI always
     *  passes the real bean; the default keeps that constructor within the parameter-count limit)
     *  and `null` in plain unit tests. `internal` for the same post-construction test seam as
     *  [wordingSettingsGate]. */
    internal var outputIntentSettingGate: OutputIntentSettingGate? = null

    private val log = Logger.getLogger(DocumentJobPublishTransaction::class.java)
    private val sentCounter = AtomicLong(0)
    private val failedCounter = AtomicLong(0)

    // Registered exactly once, the first time it is needed, so the gauge is only created once
    // the registry is available; compareAndSet gives the same once-only guarantee `by lazy` did
    // without a bare property-reference statement to trigger it (Unit-returning function call
    // instead — see ensureMetricsRegistered's call site).
    private val metricsRegisteredOnce = AtomicBoolean(false)

    private fun ensureMetricsRegistered() {
        if (metricsRegisteredOnce.compareAndSet(false, true)) {
            meterRegistry?.let { registry ->
                registry.gauge("nats.document_jobs.sent_total", sentCounter)
                registry.gauge("nats.document_jobs.failed_total", failedCounter)
            }
        }
    }

    private val jobsSubject: String by lazy {
        jobsSubjectOverride ?: System.getProperty(
            "document.jobs.subject",
            System.getenv().getOrDefault("DOCUMENT_JOBS_SUBJECT", DocumentJobSubjects.JOBS)
        )
    }

    private val interactiveJobsSubject: String by lazy {
        interactiveSubjectOverride ?: System.getProperty(
            "nats.subject.document-jobs-interactive",
            System.getenv().getOrDefault("NATS_SUBJECT_DOCUMENT_JOBS_INTERACTIVE", DocumentJobSubjects.INTERACTIVE_JOBS)
        )
    }

    /** Subject a job is published on: the lane is fixed at creation, so every republish of the same
     *  job (fast path, poller, recovery) lands on the same subject and thus the same consumer. */
    internal fun subjectFor(lane: DocumentJobLane): String =
        if (lane == DocumentJobLane.INTERACTIVE) interactiveJobsSubject else jobsSubject

    /** Test seam for the interactive subject, same role as the `jobsSubjectOverride` constructor
     *  argument for the bulk subject. Must be called before the first publish. */
    internal fun overrideInteractiveSubjectForTest(subject: String?) {
        interactiveSubjectOverride = subject
    }

    private val jetStream: JetStream by lazy {
        jetStreamProvider?.invoke() ?: error("No JetStream provider configured for DocumentJobPublishTransaction")
    }

    /**
     * `@Transactional` exists here purely so
     * that [InvoiceDtoMapper.mapToDto]'s own repository reads (receipts, B2B free values) have an active persistence context when this is called from
     * [GenerationJobFastPublishTransaction.publishOne], which runs on a plain injected executor worker
     * thread with **no** ambient transaction or CDI request context at all (unlike
     * [GenerationJobProcessor.pollPendingJobs], a `@Scheduled` method Quarkus activates a request
     * context for automatically). A no-op for every existing caller: the method body itself never
     * blocks on the returned [CompletableFuture] — `jetStream.publishAsync` is fire-and-forget
     * from here, so the transaction opens and commits synchronously within this call, well before
     * any NATS ack arrives. That transaction only actually takes effect because
     * [DocumentJobPublisher.publish] reaches this method through this bean's own CDI proxy (a
     * genuine cross-bean call) — the whole reason this method lives on its own bean rather than
     * back on [DocumentJobPublisher] itself, see this class's own KDoc.
     *
     * concurrency-fix — the actual write that used to throw is right below: mutating the managed
     * `invoice.validationStatus` field and then calling `invoiceRepository.save(invoice)` (a plain
     * `em.merge`). Two distinct ways that blew up:
     *
     * - **Attached invoice** (both the fast path and the poller load `invoice` inside their own
     *   already-open transaction): the field mutation alone marks the entity dirty; Hibernate's
     *   automatic dirty-checking flush at commit reissues a version-checked `UPDATE` regardless of
     *   whether `save`/`merge` was ever called explicitly. Any concurrent transaction that had
     *   bumped the row's version in the meantime (the poller and the fast path racing for the very
     *   same invoice, per `GenerationJobFastPublishIT`) made that flush fail.
     * - **Detached invoice** (`InvoiceExportPanel`'s print button passes its own UI-held copy,
     *   *not* the freshly reloaded aggregate `GenerationJobService.createJob` just mutated
     *   internally — see that method's own KDoc): `em.merge()` on a detached entity compares the
     *   entity's own `version` field against the current DB row and throws exactly this exception
     *   whenever they disagree — not a rare race here, but near-guaranteed the moment `createJob`
     *   actually changed something (`recalculateTotals`, a new `documentEngine`).
     *
     * Fix: [InvoiceRepository.updateValidationStatus] does a targeted, deliberately
     * version-unchecked bulk `UPDATE` instead (see its own KDoc for why bypassing optimistic
     * locking is correct for this one monotonic status flag) — used *before* the field is set on
     * `invoice` so it never risks Hibernate auto-flushing anything dirty ahead of that statement —
     * and [InvoiceRepository.detachIfManaged] removes `invoice` from the persistence context
     * *after* [InvoiceDtoMapper.mapToDto] is done reading from it (still-attached, so every lazy
     * association it touches loads normally), so the harmless in-memory field assignment below
     * never reaches Hibernate's own commit-time flush at all. Neither call does anything for an
     * already-detached `invoice` (the UI case) beyond the plain field assignment already covers,
     * since there is no dirty-checking session to protect against there in the first place.
     *
     * HARDEN (lazy-association hardening) — that same "detached invoice" case is exactly what
     * used to make [InvoiceDtoMapper.mapToDto] itself unsafe: a detached entity has no live
     * session for any of its 26 lazy associations to resolve against, so any one of them being
     * dereferenced threw `LazyInitializationException` the moment this method's own caller
     * happened not to hold the invoice attached (`InvoiceDtoMapperDetachedIT` reproduces this
     * against a rich invoice with no transaction open at all). [InvoiceDtoMapper.mapToDto] now
     * closes that gap itself — reloading by id inside its own `@Transactional` boundary before
     * mapping, see its own KDoc — rather than requiring every caller (this method included) to
     * hand it an already-attached entity; this method's own reload-then-map ordering below stays
     * unchanged by that, since [InvoiceDtoMapper.mapToDto] joins this method's already-open
     * transaction rather than opening a second one.
     */
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.DOCUMENT_GENERATED)
    fun publishTransactional(
        invoice: Invoice,
        // engine/jobType/llmOptimize all come off this job now; see
        // [DocumentJobPublisher.publish]'s own KDoc for why a caller-supplied override of any of
        // these can no longer exist.
        job: GenerationJob,
        skipValidation: Boolean,
    ): CompletableFuture<PublishAck> {
        val jobId = job.id ?: -1
        val engine = job.engine
        activateEngineIfNeeded(engine)
        // validation-status-truthful-pending.md R3 — an e-invoice generation job for this invoice
        // is genuinely in flight from this point on, so PENDING is now truthful (was previously
        // never set at all, only ever cleared by the completed job's own status mapping).
        // GenerationJobService.markFailed is the counterpart that clears this back to UNAVAILABLE
        // if the job never completes.
        //
        // concurrency-fix — the bulk update (bypasses @Version) runs BEFORE anything else touches
        // `invoice`, so there is nothing dirty on it yet that this statement's own pre-execution
        // auto-flush could trip over. See this method's own KDoc and
        // InvoiceRepository.updateValidationStatus's for the full rationale.
        markInvoicePending(invoice.id)
        // concurrency-fix — mapToDto runs BEFORE the in-memory field assignment below, not after:
        // mapToDto's own repository reads (receipts, B2B values) each trigger
        // Hibernate's ordinary auto-flush-before-query, same as the bulk update above — if
        // `invoice` were already dirtied by the field assignment at this point (true whenever it
        // is a managed entity — both the fast path and the poller load it inside their own open
        // transaction), *any* of those reads would trip that auto-flush over the exact
        // version-checked UPDATE this method exists to avoid, before ever reaching this method's
        // own transaction commit. Confirmed the hard way: this is the precise call chain
        // (PanacheReceiptRepository.findForInvoice's auto-flush, from inside mapToDto)
        // GenerationJobFastPublishIT's production incident stack trace showed.
        // The profile active right now, not the one active when the invoice/job was
        // created; the mapper stamps it onto InvoiceMetaDto and renders the trip workbooks with it.
        val invoiceDto = dtoMapper.mapToDto(
            invoice,
            wordingSettingsGate?.currentProfile() ?: WordingProfile.DEFAULT,
            issued = job.issued,
        )
        // concurrency-fix — the field is set only now, and detached immediately after (a no-op
        // for an already-detached instance, e.g. the UI's own copy in InvoiceExportPanel) so this
        // one assignment never survives long enough to be picked up by Hibernate's automatic
        // dirty-checking flush at commit. See InvoiceRepository.detachIfManaged's own KDoc.
        invoice.transitionValidationStatusTo(ValidationStatus.PENDING)
        invoiceRepository?.detachIfManaged(invoice)
        val correlationId = java.util.UUID.randomUUID().toString()
        val subject = subjectFor(job.lane)
        val json = DocumentJobWireCodec.encodeRequest(buildJobRequest(job, correlationId, invoiceDto, skipValidation))
        // attach correlationId/jobId as NATS headers for easier tracing
        val headers = Headers().add("correlationId", correlationId).add("jobId", jobId.toString())
        log.debugf("Publishing GenerationJob %d to subject [%s] correlationId=%s", jobId, subject, correlationId)
        ensureMetricsRegistered()
        // A stable Nats-Msg-Id per job so a
        // republish of the *same* job (GenerationJobProcessor.pollPendingJobs after a restart, or
        // resetStaleDelegatedJobs re-queuing a stranded DELEGATED job) within the stream's dedup
        // window is collapsed to a single stored message instead of enqueuing the job twice. The
        // dedup window itself (`duplicateWindow`) is set on NatsStreamSetup's stream configuration
        // (owned there, not by this class) and must stay <= GenerationJobProcessor.
        // resetStaleDelegatedJobs' staleCutoffMinutes (2 min) — see that function's own KDoc for the
        // matching cross-reference. resetStaleDelegatedJobs exists precisely to *force* a resend of
        // a job stuck in DELEGATED, and that forced resend must never be silently swallowed as a
        // duplicate of its own earlier (possibly failed) publish attempt. Keeping the dedup window
        // no longer than the stale cutoff guarantees the window has already closed by the time the
        // forced resend fires, so it always reaches the stream as a fresh publish.
        //
        // expectedStream is asserted only when [expectedStream] is configured (always the real
        // production stream name, via the CDI constructor) — see that field's own KDoc for why it
        // stays unset everywhere else (every Testcontainers-backed test suite owns its own,
        // differently named stream).
        val publishOptionsBuilder = PublishOptions.builder().messageId("job-$jobId")
        expectedStream?.let { publishOptionsBuilder.expectedStream(it) }
        val publishOptions = publishOptionsBuilder.build()
        return jetStream.publishAsync(subject, headers, json.toByteArray(Charsets.UTF_8), publishOptions)
            .whenComplete { ack, exception -> logPublishOutcome(PublishLogContext(jobId, subject, correlationId), ack, exception) }
    }

    fun currentSentCount(): Long = sentCounter.get()

    private fun activateEngineIfNeeded(engine: DocumentEngine) {
        // "Vor dem Einstellen eines FACTURX-Auftrags": the
        // engine gets a chance to be running *before* the job that needs it goes out, covering
        // both the fast-path publish and the poller republish since both call through here. Never
        // for MUSTANG — see EngineActivation's own KDoc for why implementations still receive
        // every engine value regardless.
        if (engine == DocumentEngine.FACTURX) {
            engineActivation?.ensureRunning(engine)
        }
    }

    private fun markInvoicePending(invoiceId: Long?) {
        if (invoiceId != null) {
            invoiceRepository?.updateValidationStatus(invoiceId, ValidationStatus.PENDING)
        }
    }

    /** What identifies one publish in the log lines of its outcome. */
    private data class PublishLogContext(val jobId: Long, val subject: String, val correlationId: String)

    private fun logPublishOutcome(context: PublishLogContext, ack: PublishAck?, exception: Throwable?) {
        val (jobId, subject, correlationId) = context
        if (exception == null) {
            sentCounter.incrementAndGet()
            log.debugf(
                "GenerationJob %d published to %s seq=%d correlationId=%s",
                jobId, subject, ack?.seqno ?: -1, correlationId,
            )
        } else {
            failedCounter.incrementAndGet()
            log.errorf(
                "Failed to publish GenerationJob %d to subject [%s] correlationId=%s: %s",
                jobId, subject, correlationId, exception.message,
            )
        }
    }

    private fun buildJobOptions(skipValidation: Boolean, llmOptimize: Boolean): DocumentJobOptions {
        val llmRequested = llmOptimize && (validationSettingsGate?.isEnabled(ValidationCapabilityId.OMLX_LLM_CHECK) ?: true)
        val veraPdfRequested = validationSettingsGate?.isEnabled(ValidationCapabilityId.VERAPDF_CHECK) ?: true
        return DocumentJobOptions(
            llmOptimize = llmRequested,
            skipValidation = skipValidation,
            veraPdfCheck = veraPdfRequested,
            outputIntent = outputIntentSettingGate?.currentToken(),
        )
    }

    private fun buildJobRequest(
        job: GenerationJob,
        correlationId: String,
        invoiceDto: InvoiceDto,
        skipValidation: Boolean,
    ): DocumentJobRequest {
        val jobId = job.id ?: -1
        val options = buildJobOptions(skipValidation, job.llmOptimize)
        return DocumentJobRequest(
            id = jobId.toString(),
            type = job.jobType,
            correlationId = correlationId,
            // MUSTANG is the original path and stays absent from the wire.
            engine = if (job.engine != DocumentEngine.MUSTANG) job.engine.name else null,
            payload = mapOf("invoice" to invoiceDto),
            // A request that asks for nothing carries no options object at all.
            options = options.takeIf { it != DocumentJobOptions() },
        )
    }
}
