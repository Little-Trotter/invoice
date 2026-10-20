package de.littletrotter.shared.quote

/**
 * Thrown when a payload of this published language cannot be read as the contract requires: blank,
 * not valid JSON, or not shaped like the requested type.
 *
 * Nothing that changes an offer may be built from a half-read body, so a payload that does not fit
 * fails loudly instead of decoding to an object with silently defaulted fields. It is an
 * [IllegalArgumentException] so the REST layer answers it as a client error.
 */
class QuoteContractViolationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
