package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.outbound.DelegatedMessageProbe
import de.littletrotter.application.invoice.port.outbound.DelegatedStreamSnapshot
import de.littletrotter.domain.invoice.DocumentJobLane

/**
 * Why [StaleDelegatedResetDecider] did or did not reset one stale-`DELEGATED` candidate.
 * [resets] is the only thing [StaleDelegatedResetCoordinator] itself branches on; the distinct
 * names exist purely so `pollPendingJobs`' own log line (via [StaleDelegatedResetCoordinator])
 * can say which of the four reasons actually applied, without re-deriving it.
 */
enum class StaleDelegatedResetReason(val resets: Boolean) {
    /** No [de.littletrotter.domain.invoice.GenerationJob.delegatedStreamSeq] was ever recorded
     *  (the publish ack never arrived, or this is a row from before this reset existed) — no evidence the
     *  message ever reached the stream at all; reset outright. */
    MISSING_SEQ(resets = true),

    /** The candidate's own sequence is higher than the stream's current last sequence — the
     *  stream was purged and recreated after this job was published, so the message cannot
     *  possibly still exist there. */
    STREAM_RECREATED(resets = true),

    /** The candidate's own sequence is still ahead of the consumer's delivered cursor — JetStream
     *  has never even handed this message out yet (worker down, or simply behind a backlog); no
     *  broker call needed to know that. Never reset. */
    STILL_QUEUED(resets = false),

    /** The message has been delivered at least once and a broker existence check confirms it is
     *  still in the stream — in flight or awaiting redelivery; JetStream still owns it. Never
     *  reset. */
    IN_FLIGHT(resets = false),

    /** The message has been delivered at least once and a broker existence check confirms it is
     *  gone (acked, purged, broker data lost) with no result ever recorded for this job — the one
     *  case this whole recovery path exists for. Reset. */
    MESSAGE_GONE(resets = true),
}

/**
 * The pure decision at the heart of the fix: "a `DELEGATED` job is re-published only when
 * its message no longer exists in the stream." Deliberately a plain function with no NATS/JPA
 * dependency of its own (every side effect — the broker call, the actual reset — belongs to its
 * caller, [StaleDelegatedResetCoordinator]) so every branch is a one-line unit test against a fake
 * [DelegatedMessageProbe], satisfying `check-messaging-core`'s coverage bar without a container.
 */
object StaleDelegatedResetDecider {

    /**
     * [lane] selects whose delivered cursor applies: a job queued behind a long bulk backlog is
     * not stale just because the other lane's consumer is further ahead.
     * [candidateSeq] is the candidate's own [de.littletrotter.domain.invoice.GenerationJob
     * .delegatedStreamSeq]; [snapshot] is this tick's one broker read
     * ([DelegatedMessageProbe.snapshot]). [probe] is consulted — via [DelegatedMessageProbe.exists]
     * — only for the one case ([StaleDelegatedResetReason.IN_FLIGHT]/[StaleDelegatedResetReason
     * .MESSAGE_GONE]) that cannot be decided from [snapshot] alone.
     */
    fun decide(candidateSeq: Long?, lane: DocumentJobLane, snapshot: DelegatedStreamSnapshot, probe: DelegatedMessageProbe): StaleDelegatedResetReason {
        if (candidateSeq == null) return StaleDelegatedResetReason.MISSING_SEQ
        if (candidateSeq > snapshot.lastSeq) return StaleDelegatedResetReason.STREAM_RECREATED
        if (candidateSeq > snapshot.deliveredFor(lane)) return StaleDelegatedResetReason.STILL_QUEUED
        return if (probe.exists(candidateSeq)) StaleDelegatedResetReason.IN_FLIGHT else StaleDelegatedResetReason.MESSAGE_GONE
    }
}
