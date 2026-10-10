package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceStatusTransitionException

/**
 * ST-1 — the single place in the domain that knows the legal [Invoice] status edges. Before ST-1
 * the lifecycle lived as a `when` block inside [InvoiceStatus.canTransitionTo] itself, with every
 * caller free to write [Invoice.status] directly and no shared notion of which transitions were
 * even meant to be possible — the same shape already replaced for [GenerationJob] with
 * [GenerationJobStateMachine], mirrored here.
 *
 * Legal edges:
 * ```
 * DRAFT     -> IN_REVIEW | VOIDED
 * IN_REVIEW -> DRAFT | SENT | VOIDED
 * SENT      -> CANCELLED
 * CANCELLED -> (terminal)
 * VOIDED    -> (terminal)
 * ```
 * [InvoiceStatus.CANCELLED] and [InvoiceStatus.VOIDED] are terminal — neither has any outgoing
 * edge. [InvoiceStatus.SENT] is **not** terminal from ST-1 onward: it keeps exactly one outgoing
 * edge, the storno of a sent invoice. It is still read-only, though — [isReadOnly] is a distinct,
 * narrower predicate than [isTerminal] and the two must never be collapsed into one.
 */
object InvoiceStateMachine {

    private val legalEdges: Map<InvoiceStatus, Set<InvoiceStatus>> = mapOf(
        InvoiceStatus.DRAFT to setOf(InvoiceStatus.IN_REVIEW, InvoiceStatus.VOIDED),
        InvoiceStatus.IN_REVIEW to setOf(InvoiceStatus.DRAFT, InvoiceStatus.SENT, InvoiceStatus.VOIDED),
        InvoiceStatus.SENT to setOf(InvoiceStatus.CANCELLED),
        InvoiceStatus.CANCELLED to emptySet(),
        InvoiceStatus.VOIDED to emptySet(),
    )

    /** `true` when [from] → [to] is a legal edge in the table above (`from == to` is never legal —
     *  there is no self-edge; a caller that means "already there, nothing to do" checks that
     *  itself). */
    fun isLegal(from: InvoiceStatus, to: InvoiceStatus): Boolean =
        to in legalEdges.getValue(from)

    /** Throws [InvoiceStatusTransitionException] naming both [from] and [to] when the edge is not
     *  legal; a no-op otherwise. An illegal transition is a programming error, not a recoverable
     *  runtime condition — callers are expected to let this propagate, not catch it. */
    fun assertLegal(from: InvoiceStatus, to: InvoiceStatus) {
        if (!isLegal(from, to)) {
            throw InvoiceStatusTransitionException(from.name, to.name)
        }
    }

    /** `true` when [state] has no outgoing edge at all. Replaces the hand-maintained
     *  `setOf(SENT, CANCELLED, VOIDED)` literals that used to be scattered through the codebase —
     *  [InvoiceStatus.SENT] is deliberately NOT in this set any more, it has the storno edge. Use
     *  [isReadOnly] for "no longer editable", which SENT still is. */
    fun isTerminal(state: InvoiceStatus): Boolean = legalEdges.getValue(state).isEmpty()

    /** The states reachable from [state] in one legal step — the UI builds its status-change
     *  action buttons from this instead of hard-coding a list. */
    fun successors(state: InvoiceStatus): Set<InvoiceStatus> = legalEdges.getValue(state)
}

/**
 * `true` for a status that no longer accepts header/item edits: every [InvoiceStateMachine
 * .isTerminal] status, plus [InvoiceStatus.SENT] — which keeps exactly one outgoing edge (the
 * storno transition) but is immutable in every other respect. Deliberately a separate predicate
 * from [InvoiceStateMachine.isTerminal] rather than an alias for it: from ST-1 onward "terminal"
 * (no outgoing edge) and "read-only" (no longer editable) answer different questions for exactly
 * one status. The one shared definition every call site that used to spell out its own copy of
 * the `SENT`/`CANCELLED`/`VOIDED` set now uses instead.
 */
val InvoiceStatus.isReadOnly: Boolean
    get() = this == InvoiceStatus.SENT || InvoiceStateMachine.isTerminal(this)

/**
 * `true` for a status that means the document has actually been issued — it left the house and
 * someone outside the system may hold it. [InvoiceStatus.SENT] and [InvoiceStatus.CANCELLED] (ST-3:
 * the storno document is born in it, and the original it cancels was sent long before it got
 * there).
 *
 * [InvoiceStatus.VOIDED] is deliberately **not** issued, which is the whole point of the
 * distinction: a voided invoice was discarded before it ever left the house, places no e-invoice
 * job, and owes no recipient anything. N5 — it still carries the number drawn at its draft
 * creation (every invoice does, from birth), but stays excluded from `isIssued` regardless: the
 * number/row are kept, never deleted, precisely so no drawn number is ever orphaned — the
 * sequence stays exactly as gapless as N2/N4 require, just by a different mechanism (see
 *  than N2's original one. Do not fold this into
 * [isReadOnly] — a draft that is merely read-only for other reasons has not been issued either.
 */
val InvoiceStatus.isIssued: Boolean
    get() = this == InvoiceStatus.SENT || this == InvoiceStatus.CANCELLED
