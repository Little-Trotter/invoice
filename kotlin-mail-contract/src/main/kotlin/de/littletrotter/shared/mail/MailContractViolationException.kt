package de.littletrotter.shared.mail

/**
 * Thrown when a payload of this published language cannot be read as the contract requires: not a
 * JSON object, a required field absent/blank/of the wrong JSON type, or an enum/typed value the
 * contract does not define.
 *
 * Direction: neither — raised on the reading side, whichever that is.
 * Owner: this contract.
 *
 * **Why this type exists at all.** The sibling payload types in `kotlin-shared`
 * ([de.littletrotter.shared.KositReportPayload], [de.littletrotter.shared.GenerationJobResultRefs])
 * decode to `null` on anything unexpected, which is right for *them*: a KoSIT report that cannot be
 * read degrades to "not yet evaluable", never to a processing failure. A mail contract is the
 * opposite case. A `MailComposeRequest` whose `recipientAddress` is missing must not decode into a
 * request with an empty recipient — that failure mode is real and was found in the mail component
 * itself (`MailOrderPayloadCodec.decode` reads through Jackson's `path(...).asText()`, which
 * returns `""` for an absent node, so a truncated payload decoded into a valid-looking order and
 * the consumer sent a mail with an empty body, or an invoice mail without its PDF, to a real
 * customer and recorded it as `SENT`). Nothing that can leave the building
 * may default to an empty string, so every required field here throws instead.
 */
class MailContractViolationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
