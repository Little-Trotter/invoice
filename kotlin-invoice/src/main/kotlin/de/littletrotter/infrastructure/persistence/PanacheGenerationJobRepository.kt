package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.repository.GenerationJobDaySummary
import de.littletrotter.domain.repository.GenerationJobPipelineCounts
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.StaleDelegatedCandidate
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.JoinType
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Predicate
import java.time.OffsetDateTime
import java.time.ZoneId

private const val READ_ONLY_HINT = "org.hibernate.readOnly"

@ApplicationScoped
class PanacheGenerationJobRepository @Inject constructor(
    private val time: TimeSource,
) : BasePanacheRepository(), GenerationJobRepository {

    companion object {
        // org.hibernate.LockOptions.SKIP_LOCKED — the jakarta.persistence.lock.timeout sentinel
        // Hibernate maps to Postgres' `FOR UPDATE SKIP LOCKED` (see resetStaleDelegated below).
        private const val SKIP_LOCKED_LOCK_TIMEOUT = -2

        private const val QUERY_TIMEOUT_MILLIS = 5000
        private const val FETCH_SIZE = 50

        private const val PARAM_STATUS = "status"
        private const val PARAM_PENDING = "pending"
        private const val PARAM_DELEGATED = "delegated"
        private const val PARAM_RUNNING = "running"
        private const val PARAM_FAILED = "failed"

        private const val ATTR_CREATED_AT = "createdAt"
        private const val ATTR_INVOICE = "invoice"
        private const val ATTR_RECIPIENT = "recipient"
    }

    override fun findByInvoiceId(invoiceId: Long): List<GenerationJob> =
        em.createQuery(
            "SELECT j FROM GenerationJob j JOIN FETCH j.invoice WHERE j.invoice.id = :id ORDER BY j.createdAt DESC",
            GenerationJob::class.java
        ).setParameter("id", invoiceId)
            .resultList

    override fun findByInvoiceIdWithTaxRates(invoiceId: Long): List<GenerationJob> =
        em.createQuery(
            """SELECT j FROM GenerationJob j
               JOIN FETCH j.invoice i
               JOIN FETCH i.items item
               LEFT JOIN FETCH item.taxRateRef
               WHERE i.id = :id
               ORDER BY j.createdAt DESC""",
            GenerationJob::class.java
        ).setParameter("id", invoiceId)
            .resultList

    // N13 (P4) — the single-generation gate's own query, straight `COUNT` rather than loading
    // rows (GenerationJobRepository.hasCompletedIssuedJob's own KDoc): createJob() calls this on
    // every invocation for an issued invoice, so it must stay a cheap existence check, not a full
    // findByInvoiceId scan. `AND j.issued = true` excludes a completed review-time draft job.
    override fun hasCompletedIssuedJob(invoiceId: Long): Boolean =
        em.createQuery(
            "SELECT COUNT(j) FROM GenerationJob j WHERE j.invoice.id = :id AND j.status = :status AND j.issued = true",
            Long::class.java,
        )
            .setParameter("id", invoiceId)
            .setParameter(PARAM_STATUS, GenerationJobStatus.COMPLETED)
            .singleResult > 0L

    // "Guard against concurrent/duplicate sends" (PO decision 2026-09-13) — same cheap-COUNT
    // shape as hasCompletedIssuedJob above; GenerationJobService.createJob calls this on every
    // trigger == SEND invocation, so it must stay an existence check, not a full scan.
    override fun hasNonTerminalTriggerJob(invoiceId: Long, trigger: GenerationJobTrigger): Boolean =
        em.createQuery(
            "SELECT COUNT(j) FROM GenerationJob j WHERE j.invoice.id = :id AND j.trigger = :trigger AND j.status IN (:pending, :delegated, :running)",
            Long::class.java,
        )
            .setParameter("id", invoiceId)
            .setParameter("trigger", trigger)
            .setParameter(PARAM_PENDING, GenerationJobStatus.PENDING)
            .setParameter(PARAM_DELEGATED, GenerationJobStatus.DELEGATED)
            .setParameter(PARAM_RUNNING, GenerationJobStatus.RUNNING)
            .singleResult > 0L

    override fun hasNonTerminalJob(invoiceId: Long, jobType: String, llmOptimize: Boolean): Boolean =
        em.createQuery(
            "SELECT COUNT(j) FROM GenerationJob j WHERE j.invoice.id = :id AND j.jobType = :jobType " +
                "AND j.llmOptimize = :llm AND j.status IN (:pending, :delegated, :running)",
            Long::class.java,
        )
            .setParameter("id", invoiceId)
            .setParameter("jobType", jobType)
            .setParameter("llm", llmOptimize)
            .setParameter(PARAM_PENDING, GenerationJobStatus.PENDING)
            .setParameter(PARAM_DELEGATED, GenerationJobStatus.DELEGATED)
            .setParameter(PARAM_RUNNING, GenerationJobStatus.RUNNING)
            .singleResult > 0L

    // Lane is stored as 0=BULK, 1=INTERACTIVE: descending puts jobs a user waits for first.
    override fun findPending(): List<GenerationJob> =
        em.createQuery(
            "SELECT j FROM GenerationJob j WHERE j.status = :status ORDER BY j.lane DESC, j.createdAt ASC",
            GenerationJob::class.java
        ).setParameter(PARAM_STATUS, GenerationJobStatus.PENDING)
            .setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MILLIS)
            .setHint(READ_ONLY_HINT, true)
            .setHint("org.hibernate.fetchSize", FETCH_SIZE)
            .resultList

    // DocumentJobPublisher-concurrency-fix — `org.hibernate.readOnly` on this fetch (job +
    // invoice + its associations) means Hibernate never includes any of these entities in an
    // automatic dirty-checking flush for the rest of this persistence context's lifetime,
    // regardless of anything that later touches a field on them (in-memory only, e.g.
    // DocumentJobPublisher.publishTransactional's own `invoice.validationStatus = PENDING`
    // assignment for the plain-unit-test/no-repository case) — every actual write on this path
    // now goes through a targeted, explicit bulk statement instead (GenerationJobRepository
    // .tryMarkDelegated, InvoiceRepository.updateValidationStatus), never entity dirty-checking.
    // Without this hint, `pollPendingJobs`'s and `publishOne`'s own losing racer — the one whose
    // tryMarkDelegated call returns false and never even reaches DocumentJobPublisher.publish —
    // could still see its own untouched `invoice` copy rejected at commit with a version-checked
    // UPDATE Hibernate generates on its own initiative; confirmed empirically (readOnly is what
    // actually closes that gap — detaching in DocumentJobPublisher alone does not, since the
    // losing racer's code path never runs that method at all).
    override fun findPendingWithInvoice(): List<GenerationJob> =
        em.createQuery(
            """SELECT j FROM GenerationJob j
               JOIN FETCH j.invoice i
               LEFT JOIN FETCH i.senderProfile
               LEFT JOIN FETCH i.recipient
               LEFT JOIN FETCH i.items item
               LEFT JOIN FETCH item.taxRateRef
               WHERE j.status = :status
               ORDER BY j.lane DESC, j.createdAt ASC""",
            GenerationJob::class.java
        ).setParameter(PARAM_STATUS, GenerationJobStatus.PENDING)
            .setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MILLIS)
            .setHint(READ_ONLY_HINT, true)
            .setHint("org.hibernate.fetchSize", FETCH_SIZE)
            .resultList

    override fun findById(id: Long): GenerationJob? = findByIdInternal(id, GenerationJob::class.java)

    // Same JOIN FETCH shape as findPendingWithInvoice above, and for the same reason: the
    // fast-path publish (GenerationJobProcessor.publishOne) hands the loaded invoice straight to
    // DocumentJobPublisher.publish() *after* this method's own @Transactional scope has already
    // closed, so every association InvoiceDtoMapper.mapToDto touches (senderProfile,
    // recipient, items, items.taxRateRef) has to be eagerly loaded here — a lazy one would throw
    // once accessed outside this transaction. Inner join on j.invoice: a job whose invoice
    // association is null, same edge case findPendingWithInvoice already excludes, is likewise
    // excluded here rather than returned with a null invoice.
    // DocumentJobPublisher-concurrency-fix — see findPendingWithInvoice's own KDoc: same
    // `org.hibernate.readOnly` hint and the same reason. This is the fast path's own query, and
    // its losing racer (tryMarkDelegated returns false, never reaching DocumentJobPublisher
    // .publish at all) is exactly the case this hint protects.
    override fun findByIdForUpdate(id: Long): GenerationJob? {
        // The lock is taken by the read itself; the refresh then replaces any state an earlier read
        // in this persistence context left behind, so the caller never decides on a stale row.
        val job = em.find(GenerationJob::class.java, id, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE) ?: return null
        em.refresh(job, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
        return job
    }

    override fun findByIdWithInvoice(id: Long): GenerationJob? =
        em.createQuery(
            """SELECT j FROM GenerationJob j
               JOIN FETCH j.invoice i
               LEFT JOIN FETCH i.senderProfile
               LEFT JOIN FETCH i.recipient
               LEFT JOIN FETCH i.items item
               LEFT JOIN FETCH item.taxRateRef
               WHERE j.id = :id""",
            GenerationJob::class.java
        ).setParameter("id", id)
            .setHint(READ_ONLY_HINT, true)
            .resultList
            .firstOrNull()

    // A new job is flushed immediately so the active-print-job unique index (see
    // GenerationJobCreationService) surfaces here, where the caller can map it, instead of at an
    // unrelated commit later.
    override fun save(job: GenerationJob): GenerationJob =
        if (job.id == null) saveEntityFlushing(job) { job.id } else saveEntity(job) { job.id }

    // A bulk JPQL UPDATE, not GenerationJob.transitionTo: there is no loaded entity here,
    // only an id. The `WHERE j.status = :oldStatus` clause IS this call site's guard for the
    // PENDING->DELEGATED edge (GenerationJobStateMachine's own KDoc documents this exception) —
    // it can never fire the UPDATE against a row that isn't currently PENDING, so a racing second
    // caller (another thread, the fast path vs. the poller) always loses cleanly (0 rows updated).
    // delegated_at is stamped in the SAME statement as the PENDING->DELEGATED transition,
    // never a follow-up write: it is the stale-DELEGATED candidate clock
    // (findStaleDelegatedCandidates), so it must exist from the very first tick this row could
    // possibly become a candidate on.
    override fun tryMarkDelegated(id: Long): Boolean {
        val updated = em.createQuery(
            "UPDATE GenerationJob j SET j.status = :newStatus, j.delegatedAt = :now WHERE j.id = :id AND j.status = :oldStatus"
        )
            .setParameter("newStatus", GenerationJobStatus.DELEGATED)
            .setParameter("now", time.nowUtc())
            .setParameter("id", id)
            .setParameter("oldStatus", GenerationJobStatus.PENDING)
            .executeUpdate()
        return updated > 0
    }

    // The reverse transition, used when the fast path's immediate publish attempt right
    // after tryMarkDelegated's own claim fails (GenerationJobProcessor.publishOne).
    // Same bulk-UPDATE shape as tryMarkDelegated above; `WHERE j.status = :oldStatus` IS
    // the guard for this DELEGATED->PENDING edge.
    // Clears delegated_at/delegated_stream_seq along with the reverse transition: this job
    // is no longer DELEGATED, so neither the candidate clock nor its (never-recorded, since this
    // fires only when the fast path's OWN immediate publish attempt failed) sequence must survive
    // onto whatever DELEGATED spell comes next for it.
    override fun revertToPending(id: Long): Boolean {
        val updated = em.createQuery(
            """UPDATE GenerationJob j SET j.status = :newStatus, j.delegatedAt = NULL, j.delegatedStreamSeq = NULL
               WHERE j.id = :id AND j.status = :oldStatus"""
        )
            .setParameter("newStatus", GenerationJobStatus.PENDING)
            .setParameter("id", id)
            .setParameter("oldStatus", GenerationJobStatus.DELEGATED)
            .executeUpdate()
        return updated > 0
    }

    // The read half, deliberately a plain SELECT with no lock at all (unlike the old
    // resetStaleDelegated this replaced): StaleDelegatedResetCoordinator calls this, then spends
    // an unbounded amount of time on real NATS round trips deciding which candidates to actually
    // reset, and only opens the short, lock-holding write transaction (resetStaleDelegatedByIds)
    // once every decision is already known. Ordered by id so the follow-up write can keep Fix U's
    // ascending-id lock order even though the two now run as separate statements/transactions.
    override fun findStaleDelegatedCandidates(staleCutoffMinutes: Long): List<StaleDelegatedCandidate> {
        val cutoff = time.nowUtc().minusMinutes(staleCutoffMinutes)
        return em.createQuery(
            """SELECT j FROM GenerationJob j
               WHERE j.status = :delegated
                 AND j.startedAt IS NULL
                 AND j.delegatedAt < :cutoff
               ORDER BY j.id""",
            GenerationJob::class.java,
        )
            .setParameter(PARAM_DELEGATED, GenerationJobStatus.DELEGATED)
            .setParameter("cutoff", cutoff)
            .resultList
            .map { StaleDelegatedCandidate(it.id!!, it.delegatedStreamSeq, it.lane) }
    }

    // Fix U (generation-job-deadlock-demo-burst) — this used to be a single bulk `UPDATE …
    // WHERE status = DELEGATED AND …` touching every stale row in one statement. Under a burst of
    // concurrent single-row publish transactions (tryMarkDelegated and the like, each
    // holding exactly one generation_job row for the duration of its own NATS ack wait), that bulk
    // statement locked rows in whatever order its own query plan visited them — not necessarily
    // `id` order, and not necessarily the same order every time a concurrently-updated table
    // reshuffles physical tuple positions. Two transactions that lock the same set of rows in
    // different orders is the textbook Postgres deadlock; a real one was observed live
    // (`deadlock detected … while updating tuple … in relation "generation_job"`).
    // Fix: re-select exactly the caller-decided [ids], `ORDER BY id FOR UPDATE SKIP LOCKED`, then
    // update them one at a time in that same ascending order. `SKIP LOCKED` (Hibernate's `-2`
    // lock-timeout sentinel) means a row a concurrent publish transaction currently holds is left
    // untouched this tick instead of waited on — it is simply still DELEGATED and a candidate
    // again on the very next tick, so nothing is lost, only deferred. Ascending-id order matches
    // every single-row writer's own natural order (ids only ever grow), so this can no longer lock
    // against a publish transaction in the opposite direction.
    //
    // The per-row attempts bump is now unconditional, no flag to switch it off: every id
    // reaching this method was already decided, off-transaction, by StaleDelegatedResetCoordinator
    // — a reset here always means "a worker had this message and it is gone" (see
    // StaleDelegatedResetDecider's own KDoc), so the increment is always justified.
    override fun resetStaleDelegatedByIds(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        // Re-check status under lock: `WHERE j.id IN :ids AND j.status = :delegated` guards
        // against a row that moved on (completed, reset again, ...) between the caller's earlier
        // plain read and this write — the very reason the two are separate statements.
        val lockedIds = em.createQuery(
            "SELECT j.id FROM GenerationJob j WHERE j.id IN :ids AND j.status = :delegated ORDER BY j.id",
            Long::class.javaObjectType,
        )
            .setParameter("ids", ids)
            .setParameter(PARAM_DELEGATED, GenerationJobStatus.DELEGATED)
            .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
            .setHint("jakarta.persistence.lock.timeout", SKIP_LOCKED_LOCK_TIMEOUT)
            .resultList

        return lockedIds.sumOf { id ->
            em.createQuery(
                """UPDATE GenerationJob j
                   SET j.status = :pending, j.attempts = j.attempts + 1, j.delegatedAt = NULL, j.delegatedStreamSeq = NULL
                   WHERE j.id = :id AND j.status = :delegated"""
            )
                .setParameter(PARAM_PENDING, GenerationJobStatus.PENDING)
                .setParameter("id", id)
                .setParameter(PARAM_DELEGATED, GenerationJobStatus.DELEGATED)
                .executeUpdate()
        }
    }

    // Called synchronously by the same caller that just claimed the DELEGATED transition
    // and is still blocked on the publish ack; `WHERE j.status = :delegated` guards against the
    // one remaining race (the job moved on again between the claim and this call).
    override fun recordDelegatedStreamSeq(id: Long, streamSeq: Long): Boolean =
        em.createQuery("UPDATE GenerationJob j SET j.delegatedStreamSeq = :seq WHERE j.id = :id AND j.status = :delegated")
            .setParameter("seq", streamSeq)
            .setParameter("id", id)
            .setParameter(PARAM_DELEGATED, GenerationJobStatus.DELEGATED)
            .executeUpdate() > 0

    // `WHERE j.status = :pending` IS the guard for this PENDING->FAILED
    // (Versuchsbudget-erschöpft) edge; same bulk-UPDATE exception as the methods above. Called
    // from GenerationJobProcessor.pollPendingJobs once GenerationJob.attempts has reached
    // `invoice.jobs.max-attempts`, instead of re-delegating the job for another doomed cycle.
    override fun failExhausted(id: Long, lastError: String, finishedAt: OffsetDateTime): Boolean {
        val updated = em.createQuery(
            """UPDATE GenerationJob j SET j.status = :failed, j.lastError = :lastError, j.finishedAt = :now
               WHERE j.id = :id AND j.status = :pending"""
        )
            .setParameter(PARAM_FAILED, GenerationJobStatus.FAILED)
            .setParameter("lastError", lastError)
            .setParameter("now", finishedAt)
            .setParameter("id", id)
            .setParameter(PARAM_PENDING, GenerationJobStatus.PENDING)
            .executeUpdate()
        return updated > 0
    }

    // A plain diagnostic write, not a status transition at all: no WHERE-status guard
    // needed or present. Used by GenerationJobResultConsumer's DLQ path as the fallback when
    // failDlq below was a no-op, best-effort.
    override fun recordLastError(id: Long, message: String): Boolean {
        val updated = em.createQuery("UPDATE GenerationJob j SET j.lastError = :msg WHERE j.id = :id")
            .setParameter("msg", message)
            .setParameter("id", id)
            .executeUpdate()
        return updated > 0
    }

    // L3 (fix-generation-job-failure-visibility.md) — `WHERE j.status IN (:delegated, :running)`
    // IS the guard for these DELEGATED/RUNNING->FAILED edges (already legal in
    // GenerationJobStateMachine — see failDlq's own KDoc); same bulk-UPDATE exception as
    // failExhausted/tryMarkDelegated above. Called from GenerationJobResultConsumer's DLQ path,
    // via GenerationJobService.markResultProcessingFailed, once that consumer's own retry loop
    // gives up on a message it could not durably process.
    override fun failDlq(id: Long, message: String, finishedAt: OffsetDateTime): Boolean {
        val updated = em.createQuery(
            """UPDATE GenerationJob j SET j.status = :failed, j.lastError = :lastError, j.finishedAt = :now
               WHERE j.id = :id AND j.status IN (:delegated, :running)"""
        )
            .setParameter(PARAM_FAILED, GenerationJobStatus.FAILED)
            .setParameter("lastError", message)
            .setParameter("now", finishedAt)
            .setParameter("id", id)
            .setParameter(PARAM_DELEGATED, GenerationJobStatus.DELEGATED)
            .setParameter(PARAM_RUNNING, GenerationJobStatus.RUNNING)
            .executeUpdate()
        return updated > 0
    }

    // See GenerationJobRepository.findCompletedBetween's own KDoc for why
    // this stays a plain timestamp-window query (jsonb has no portable JPQL LIKE/-> operator) —
    // the actual resultRefs.llmStatus == "PENDING" filter happens in
    // GenerationJobService.resetStaleLlmPending, after this query has already bounded the scan.
    override fun findCompletedBetween(from: OffsetDateTime, to: OffsetDateTime): List<GenerationJob> =
        em.createQuery(
            "SELECT j FROM GenerationJob j WHERE j.status = :status AND j.finishedAt >= :from AND j.finishedAt < :to",
            GenerationJob::class.java,
        )
            .setParameter(PARAM_STATUS, GenerationJobStatus.COMPLETED)
            .setParameter("from", from)
            .setParameter("to", to)
            .resultList

    override fun findCreditNoteIdsWithoutJob(limit: Int): List<Long> =
        em.createQuery(
            """SELECT i.id FROM Invoice i
               WHERE i.typeCode = :creditNote AND i.status = :cancelled
                 AND NOT EXISTS (SELECT 1 FROM GenerationJob j WHERE j.invoice = i)
               ORDER BY i.id""",
            Long::class.javaObjectType,
        )
            .setParameter("creditNote", InvoiceTypeCode.CORRECTED_INVOICE)
            .setParameter("cancelled", InvoiceStatus.CANCELLED)
            .setMaxResults(limit)
            .resultList
            .map { it }

    // The read half of the attempt-budget reopen path: FAILED jobs whose
    // lastError carries GenerationJob.ATTEMPT_BUDGET_EXHAUSTED_REASON_PREFIX (failExhaustedJob's
    // own message, never any other FAILED reason) AND whose invoice has no COMPLETED job at all
    // yet (the `NOT EXISTS` — any completed job, issued or not: reopening must never race a
    // document that already exists). GenerationJobProcessor's own scan reads this on every tick.
    override fun findExhaustedJobsWithoutDocument(): List<GenerationJob> =
        em.createQuery(
            """SELECT j FROM GenerationJob j
               WHERE j.status = :failed
                 AND j.lastError LIKE :marker ESCAPE '\'
                 AND NOT EXISTS (
                   SELECT 1 FROM GenerationJob j2
                   WHERE j2.invoice = j.invoice AND j2.status = :completed
                 )""",
            GenerationJob::class.java,
        )
            .setParameter(PARAM_FAILED, GenerationJobStatus.FAILED)
            .setParameter("marker", "${GenerationJob.ATTEMPT_BUDGET_EXHAUSTED_REASON_PREFIX}%")
            .setParameter("completed", GenerationJobStatus.COMPLETED)
            .resultList

    // The write half: `WHERE j.status = :failed` IS the guard for this
    // FAILED->PENDING edge (the one narrow exception GenerationJobStateMachine documents), same
    // bulk-UPDATE exception as tryMarkDelegated/failExhausted above — there is no loaded entity
    // here, only an id. Resets `attempts` to 0 (a full fresh budget, not one already exhausted)
    // and clears `finishedAt` (the job is no longer finished). `lastError` is deliberately left
    // untouched — an operator reading the Druckaufträge grid can still see why the PREVIOUS
    // attempt cycle ended, until this new cycle overwrites it with its own outcome.
    override fun reopenExhausted(id: Long): Boolean {
        val updated = em.createQuery(
            """UPDATE GenerationJob j SET j.status = :pending, j.attempts = 0, j.finishedAt = null
               WHERE j.id = :id AND j.status = :failed"""
        )
            .setParameter(PARAM_PENDING, GenerationJobStatus.PENDING)
            .setParameter("id", id)
            .setParameter(PARAM_FAILED, GenerationJobStatus.FAILED)
            .executeUpdate()
        return updated > 0
    }

    // The global "Druckaufträge" listing. Inner JOIN FETCH j.invoice: same convention as
    // findByIdWithInvoice above — a job whose invoice association is null is excluded rather than
    // returned with a null invoice, and PrintJobOverviewQueryServiceImpl.toRow relies on that.
    // LEFT JOIN FETCH i.recipient: a recipient may legitimately be null (a draft-only edge case
    // that never actually reaches GenerationJob in practice, but the association itself is
    // nullable), and this is the join the recipientId filter and the "Kunde" column both need —
    // fetching it here means the row mapper never triggers a lazy load per row (no N+1).
    override fun findPaged(
        statuses: Set<GenerationJobStatus>,
        recipientId: Long?,
        createdFrom: OffsetDateTime?,
        createdTo: OffsetDateTime?,
        offset: Int,
        limit: Int,
    ): List<GenerationJob> {
        val cb = em.criteriaBuilder
        val cq = cb.createQuery(GenerationJob::class.java)
        val j = cq.from(GenerationJob::class.java)

        // See PanacheInvoiceRepository.findOverviewPage's KDoc: `.get(...)` after
        // `.fetch(..., LEFT)` was tried as a cast-free replacement but does not reuse the fetch's
        // join on this Hibernate version (proven wrong for that sibling query).
        //
        // `i` is only ever used to chain a second `.fetch(...)` call, a `FetchParent`
        // method `Fetch<Z,X>` already has (it extends `FetchParent<Z,X>`) — no cast needed at
        // all. `r` only ever needs `Path.get(...)` (see buildPredicates' own `r: Path<*>`
        // parameter below); a star-projected `Path<*>` cast target carries no generic argument
        // left for the JVM to leave unverified, so it is a checked cast (Hibernate's fetch node
        // implements both `Fetch` and `Join`/`Path` at runtime) — no `@Suppress` needed.
        val i = j.fetch<GenerationJob, Invoice>(ATTR_INVOICE, JoinType.INNER)
        val r = i.fetch<Invoice, Any>(ATTR_RECIPIENT, JoinType.LEFT) as Path<*>

        val predicates = buildPredicates(cb, j, r, JobFilters(statuses, recipientId, createdFrom, createdTo))
        cq.select(j).where(*predicates)
        cq.orderBy(cb.desc(j.get<OffsetDateTime>(ATTR_CREATED_AT)), cb.desc(j.get<Long>("id")))

        val q = em.createQuery(cq)
        q.firstResult = offset
        q.maxResults = limit
        return q.resultList
    }

    // Same predicate shape as findPaged above (kept in exact sync — a mismatch here would
    // desync the data provider's reported total from what the page actually shows), but a plain
    // (non-fetching) JOIN: a count query never touches i/r's own columns beyond the id join
    // condition, so fetching their rows into the persistence context would be pure waste.
    override fun countFiltered(
        statuses: Set<GenerationJobStatus>,
        recipientId: Long?,
        createdFrom: OffsetDateTime?,
        createdTo: OffsetDateTime?,
    ): Long {
        val cb = em.criteriaBuilder
        val cq = cb.createQuery(Long::class.javaObjectType)
        val j = cq.from(GenerationJob::class.java)
        val i = j.join<GenerationJob, Invoice>(ATTR_INVOICE, JoinType.INNER)
        val r = i.join<Invoice, Any>(ATTR_RECIPIENT, JoinType.LEFT)

        val predicates = buildPredicates(cb, j, r, JobFilters(statuses, recipientId, createdFrom, createdTo))
        cq.select(cb.count(j)).where(*predicates)

        val q = em.createQuery(cq)
        return q.singleResult
    }

    // Per-day aggregate for the TreeGrid's root rows. Same predicate shape as
    // findPaged/countFiltered above (kept in sync for the same reason those two are), but
    // deliberately NOT a SQL-level GROUP BY on a zone-shifted expression: generation_job
    // .created_at is declared TIMESTAMP WITHOUT TIME ZONE (V001 baseline) — a raw SQL timezone()
    // call on that column depends on exactly how the JDBC driver's default-timezone conversion
    // wrote it, which a portable JPQL query has no reliable way to mirror (confirmed the hard
    // way: an earlier FUNCTION('timezone', ...) GROUP BY version failed against real Postgres
    // with "column ... must appear in the GROUP BY clause", and even a fixed version would only
    // be *coincidentally* correct for whatever timezone the JVM happened to run in).
    // createdFrom/createdTo comparisons against the very same column already round-trip
    // correctly through Hibernate's own OffsetDateTime type descriptor (see findPaged/
    // countFiltered above) — so this loads the same (small: paged/filtered) entity set those two
    // already scan and buckets by LOCAL calendar day in `zone` here, off those already-correct
    // in-memory OffsetDateTime values, instead of trusting a second, independent SQL-level
    // timezone conversion to agree with the first.
    override fun findDaySummaries(
        statuses: Set<GenerationJobStatus>,
        recipientId: Long?,
        createdFrom: OffsetDateTime?,
        createdTo: OffsetDateTime?,
        zone: ZoneId,
    ): List<GenerationJobDaySummary> {
        val cb = em.criteriaBuilder
        val cq = cb.createQuery(GenerationJob::class.java)
        val j = cq.from(GenerationJob::class.java)
        val i = j.join<GenerationJob, Invoice>(ATTR_INVOICE, JoinType.INNER)
        val r = i.join<Invoice, Any>(ATTR_RECIPIENT, JoinType.LEFT)

        val predicates = buildPredicates(cb, j, r, JobFilters(statuses, recipientId, createdFrom, createdTo))
        cq.select(j).where(*predicates)

        val q = em.createQuery(cq)

        return q.resultList
            .groupBy { it.createdAt.atZoneSameInstant(zone).toLocalDate() }
            .map { (day, jobs) ->
                GenerationJobDaySummary(
                    day = day,
                    jobCount = jobs.size.toLong(),
                    failedCount = jobs.count { it.status == GenerationJobStatus.FAILED }.toLong(),
                )
            }
            .sortedByDescending { it.day }
    }

    // One grouped SQL round trip for the whole pipeline row: a raw JPQL multiselect
    // (matching this class's predominant style, not the Criteria API) because
    // `CriteriaBuilder.min`/`max` are typed `Number`-only per the JPA spec and cannot aggregate
    // an `OffsetDateTime` CASE expression — Hibernate's JPQL parser has no such restriction.
    // `SUM(CASE WHEN ... THEN 1 ELSE 0 END)` per stage rather than four separate COUNT queries.
    // Untyped `Query` (not `Tuple`, not `Array<Any>::class.java` — a Kotlin array class the JPA
    // provider cannot resolve) so `.singleResult` returns the row as `Array<*>`, indexed
    // positionally below.
    override fun pipelineCounts(completedSince24h: OffsetDateTime, completedSince5m: OffsetDateTime): GenerationJobPipelineCounts {
        val row = em.createQuery(
            """
            SELECT
                SUM(CASE WHEN j.status IN :open THEN 1 ELSE 0 END),
                MIN(CASE WHEN j.status IN :open THEN j.createdAt ELSE NULL END),
                SUM(CASE WHEN j.status = :completed AND j.finishedAt >= :since24h THEN 1 ELSE 0 END),
                SUM(CASE WHEN j.status = :completed AND j.finishedAt >= :since5m THEN 1 ELSE 0 END),
                SUM(CASE WHEN j.status = :failed THEN 1 ELSE 0 END)
            FROM GenerationJob j
            """,
        )
            .setParameter("open", setOf(GenerationJobStatus.PENDING, GenerationJobStatus.DELEGATED, GenerationJobStatus.RUNNING))
            .setParameter("completed", GenerationJobStatus.COMPLETED)
            .setParameter("since24h", completedSince24h)
            .setParameter("since5m", completedSince5m)
            .setParameter(PARAM_FAILED, GenerationJobStatus.FAILED)
            .singleResult as Array<*>

        // The four SUM(...) columns (indices 0/2/3/4) aggregate over the unconditional
        // `FROM GenerationJob j` (no WHERE): SQL SUM of a CASE that always yields 0 or 1 is NULL
        // when the table has zero rows total, same as any other empty-input SUM. That must map to
        // "zero matches", not blow up — a fresh/isolated database (a lone IT run, a brand-new
        // environment) legitimately starts with no generation_job rows at all. MIN(...) (index 1)
        // is nullable for a second, distinct reason: it is NULL whenever no row is currently
        // "open", a real and common state independent of the table's total size.
        return GenerationJobPipelineCounts(
            openCount = (row[0] as Number?)?.toLong() ?: 0L,
            oldestOpenCreatedAt = row[1] as? OffsetDateTime,
            completedLast24h = (row[2] as Number?)?.toLong() ?: 0L,
            completedLast5m = (row[3] as Number?)?.toLong() ?: 0L,
            failedCount = (row[4] as Number?)?.toLong() ?: 0L,
        )
    }

    /**
     * PO-Entscheid 29.08.2026 (Core-Härtung) — [findPaged]/[countFiltered]/
     * [findDaySummaries] share this exact predicate shape and must stay in sync (a mismatch would
     * desync the data provider's total, the TreeGrid's day buckets and the actual page contents
     * from each other). Criteria API instead of JPQL string assembly: every value is a bind
     * parameter by construction.
     */
    /** Parameter object for [buildPredicates] — bundles every filter dimension [findPaged]/
     *  [countFiltered]/[findDaySummaries] can carry so that function stays under detekt's
     *  `LongParameterList` threshold. */
    private data class JobFilters(
        val statuses: Set<GenerationJobStatus>,
        val recipientId: Long?,
        val createdFrom: OffsetDateTime?,
        val createdTo: OffsetDateTime?,
    )

    private fun buildPredicates(
        cb: CriteriaBuilder,
        j: Path<GenerationJob>,
        r: Path<*>,
        filters: JobFilters,
    ): Array<Predicate> {
        val predicates = mutableListOf<Predicate>()
        if (filters.statuses.isNotEmpty()) predicates += j.get<GenerationJobStatus>(PARAM_STATUS).`in`(filters.statuses)
        if (filters.recipientId != null) predicates += cb.equal(r.get<Long>("id"), filters.recipientId)
        if (filters.createdFrom != null) predicates += cb.greaterThanOrEqualTo(j.get(ATTR_CREATED_AT), filters.createdFrom)
        if (filters.createdTo != null) predicates += cb.lessThanOrEqualTo(j.get(ATTR_CREATED_AT), filters.createdTo)
        return predicates.toTypedArray()
    }
}
