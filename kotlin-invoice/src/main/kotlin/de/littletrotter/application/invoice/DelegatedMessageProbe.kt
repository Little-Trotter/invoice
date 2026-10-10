package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.DocumentJobLane

/**
 * Port over the `document-jobs` JetStream stream/consumer state
 * [StaleDelegatedResetCoordinator] needs to decide whether a stale-`DELEGATED`
 * [de.littletrotter.domain.invoice.GenerationJob] should actually be reset: the rule is "a
 * `DELEGATED` job is re-published only when its message no longer exists in the stream" — while
 * the message is still there, JetStream itself owns delivery/redelivery.
 *
 * The real, NATS-backed implementation
 * ([de.littletrotter.infrastructure.messaging.NatsDelegatedMessageProbe]) lives in the
 * `infrastructure.messaging` adapter tier, same hexagonal shape as every other port this module
 * defines in `application`/`domain` and implements in `infrastructure` — this interface itself
 * carries no NATS dependency at all.
 *
 * Both operations may throw on a genuine broker failure (unreachable NATS, JetStream API error);
 * [snapshot] and [exists] are never called from inside a Postgres transaction (see
 * [StaleDelegatedResetCoordinator]'s own KDoc) — a caller sees a real exception, not a fail-safe
 * default, and is expected to skip the whole tick's reset rather than act on missing evidence.
 */
interface DelegatedMessageProbe {

    /** One call per tick: the delivered-cursor stream sequence of each lane's worker consumer (how
     *  far JetStream has ever handed messages to the bulk `kotlin-xpdf-group` and the interactive
     *  `kotlin-xpdf-interactive` consumer — a consumer not registered at all reads as `0`,
     *  "nothing delivered yet", never a failure) and the
     *  stream's own last sequence (so a stream that was purged and recreated, whose sequence
     *  counter dropped below a job's own recorded [de.littletrotter.domain.invoice.GenerationJob
     *  .delegatedStreamSeq], is distinguishable from a message merely still queued). */
    fun snapshot(): DelegatedStreamSnapshot

    /** One call per candidate whose own recorded sequence is at or before [snapshot]'s own
     *  `deliveredStreamSeq` (see [StaleDelegatedResetDecider] for why every other case never
     *  needs this at all): whether the message at [streamSeq] still exists in the stream. `false`
     *  only for a genuine "no message found" answer from the broker (JetStream error 10037) —
     *  every other failure propagates instead of being folded into `false`, so a genuinely
     *  unreachable broker never gets misread as "the message is gone". */
    fun exists(streamSeq: Long): Boolean
}

/** The one-per-tick snapshot [DelegatedMessageProbe.snapshot] returns:
 *  [deliveredStreamSeq] is the bulk consumer's delivered-cursor stream sequence,
 *  [interactiveDeliveredStreamSeq] the interactive consumer's, [lastSeq] the `document-jobs`
 *  stream's own last sequence. Each lane has its own cursor over the shared stream, so a job must
 *  be compared against the cursor of the lane it was published on ([deliveredFor]). */
data class DelegatedStreamSnapshot(
    val deliveredStreamSeq: Long,
    val interactiveDeliveredStreamSeq: Long,
    val lastSeq: Long,
) {
    fun deliveredFor(lane: DocumentJobLane): Long = when (lane) {
        DocumentJobLane.BULK -> deliveredStreamSeq
        DocumentJobLane.INTERACTIVE -> interactiveDeliveredStreamSeq
    }
}
