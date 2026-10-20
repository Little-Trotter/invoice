package de.littletrotter.shared.quote

/**
 * Why an offer can or cannot be handed to the mail component right now. The values are listed in
 * priority order: the first condition that does not hold decides, so the user fixes one thing at a time.
 * The dispatch copy is requested explicitly by the user; sending never requests it.
 */
enum class QuoteSendGate {
    /** Every condition holds: the mail may be prepared. */
    READY,

    /** Only an offer in review can be sent. */
    NOT_IN_REVIEW,

    /** A dispatch copy of the current offer content is still being rendered. */
    COPY_IN_FLIGHT,

    /** The dispatch copies predate the last content or status change, or their rendering failed; a new copy is needed. */
    COPY_STALE,

    /** No dispatch copy was ever requested for the offer. */
    COPY_MISSING,

    /** The customer has no e-mail address to send the offer to. */
    NO_RECIPIENT_EMAIL,
}
