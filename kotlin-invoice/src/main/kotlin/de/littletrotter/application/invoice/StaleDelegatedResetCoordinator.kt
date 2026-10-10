package de.littletrotter.application.invoice

import de.littletrotter.domain.repository.StaleDelegatedCandidate
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * Replaces the old `createdAt`-keyed, unconditionally-re-publishing stale-DELEGATED reset:
 * "a `DELEGATED` job is re-published only when its message no longer exists in the stream." Called
 * once per [GenerationJobProcessor.pollPendingJobs] tick, same call site the old
 * `GenerationJobService.resetStaleDelegated` used to occupy.
 *
 * Three steps, deliberately three separate method calls rather than one `@Transactional` sweep:
 * 1. [StaleDelegatedResetTransactions.findCandidates] — a short, lock-free Postgres read.
 * 2. [decideResetIds] — zero or more [DelegatedMessageProbe] calls, a real NATS round trip with no
 *    bound on latency at all.
 * 3. [StaleDelegatedResetTransactions.applyResets] — a short, `SKIP LOCKED` Postgres write, only for the
 *    ids step 2 actually decided on.
 * Step 2 never runs inside a transaction from step 1 or 3: holding a Postgres row lock across an
 * arbitrary-latency broker round trip would turn a slow/unreachable NATS into a Postgres lock-wait
 * pile-up on `generation_job`, exactly the kind of cross-system coupling Fix U's own id-ordered
 * `SKIP LOCKED` design already avoids for concurrent writers. Step 1's own query is a plain read
 * for the same reason — nothing here holds a lock while step 2 runs.
 *
 * A probe failure aborts the WHOLE tick — no partial resets, no resets from candidates that didn't
 * even need the probe: "never reset on missing evidence" is a per-tick guarantee, not a
 * per-candidate one. Logged once at WARN, never at ERROR — a broker outage self-heals on the next
 * tick, and every queued-but-undelivered job is already safe by construction (see
 * [StaleDelegatedResetDecider]).
 */
@ApplicationScoped
class StaleDelegatedResetCoordinator @Inject constructor(
    private val staleDelegatedResetTransactions: StaleDelegatedResetTransactions,
    private val delegatedMessageProbe: DelegatedMessageProbe,
) {
    private val log = Logger.getLogger(StaleDelegatedResetCoordinator::class.java)

    fun resetStale(staleCutoffMinutes: Long): Int {
        val candidates = staleDelegatedResetTransactions.findCandidates(staleCutoffMinutes)
        if (candidates.isEmpty()) return 0
        val resetIds = decideResetIdsOrNull(candidates) ?: return 0
        return if (resetIds.isEmpty()) 0 else staleDelegatedResetTransactions.applyResets(resetIds)
    }

    // Sole catch-all of this tick: any broker failure (unreachable NATS, a
    // JetStream API error other than "no message found") must abort this tick's reset
    // entirely rather than act on missing evidence; see this class's own KDoc.
    // Extracted out of [resetStale] (rather than inlined) to keep that method's own
    // ReturnCount down; behaviour is unchanged.
    private fun decideResetIdsOrNull(candidates: List<StaleDelegatedCandidate>): List<Long>? =
        try {
            decideResetIds(candidates)
        } catch (ex: Exception) {
            log.warn(
                "Stale-DELEGATED probe failed for this tick -- skipping the reset entirely " +
                    "(never reset on missing evidence), retrying on the next tick: ${ex.message}",
                ex,
            )
            null
        }

    /** One [DelegatedMessageProbe.snapshot] call, only if at least one candidate actually needs
     *  it ([StaleDelegatedResetReason.MISSING_SEQ] never does — see [StaleDelegatedResetDecider])
     *  — a tick whose every candidate is a legacy/lost-ack row never touches NATS at all. */
    private fun decideResetIds(candidates: List<StaleDelegatedCandidate>): List<Long> {
        val snapshot = if (candidates.any { it.delegatedStreamSeq != null }) delegatedMessageProbe.snapshot() else null
        return candidates.filter { candidate ->
            val seq = candidate.delegatedStreamSeq
            val reason = if (seq == null) {
                StaleDelegatedResetReason.MISSING_SEQ
            } else {
                StaleDelegatedResetDecider.decide(seq, candidate.lane, requireNotNull(snapshot), delegatedMessageProbe)
            }
            reason.resets
        }.map { it.id }
    }
}
