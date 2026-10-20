package de.littletrotter.application.mail

/**
 * A compose handover that did not produce a draft. Carries a message the UI can show as-is;
 * never a silent degrade — see [de.littletrotter.shared.mail.MailContractViolationException]'s
 * own KDoc for why nothing on this path may default to an empty value.
 */
open class MailDispatchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The component answered 404: the addressed draft or resource does not exist (any more). */
class MailNotFoundException(message: String) : MailDispatchException(message)
