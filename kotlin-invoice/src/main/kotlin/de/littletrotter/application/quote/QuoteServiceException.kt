package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteSendGateState

/**
 * The offer component could not serve a call: unreachable, not configured, a refusal or an answer
 * that does not match the contract. [message] is meant to be shown to the user as-is; for a
 * refusal it is the component's own explanation (a rule of the offer life cycle, a validation).
 */
open class QuoteServiceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The component answered 404: the addressed offer or attachment does not exist (any more). */
class QuoteNotFoundException(message: String) : QuoteServiceException(message)

/**
 * The component could not serve a call because an object in its document storage is gone. Repeating
 * the call cannot bring the object back, so this failure is permanent, unlike an unreachable component.
 */
class QuoteObjectMissingException(message: String) : QuoteServiceException(message)

/**
 * The component refused with 409 because no active default quote number range is replicated yet.
 * [message] is the message key the UI translates, not text for the user.
 */
class QuoteNoNumberRangeException : QuoteServiceException(MESSAGE_KEY) {

    val messageKey: String get() = MESSAGE_KEY

    companion object {
        const val MESSAGE_KEY = "quote.error.noNumberRange"
    }
}

/**
 * The component refused a send with 409 because the send gate is not open. [message] is the key
 * `quote.send.gate.<GATE>` the UI translates, not text for the user.
 */
class QuoteNotSendableException(val gate: QuoteSendGateState) : QuoteServiceException("$KEY_PREFIX${gate.name}") {

    companion object {
        const val KEY_PREFIX = "quote.send.gate."
    }
}

/**
 * The administrator switched the offer module off (System -> Settings). [message] is the message
 * key the UI translates, not text for the user.
 */
class QuoteModuleDisabledException : QuoteServiceException(MESSAGE_KEY) {

    val messageKey: String get() = MESSAGE_KEY

    companion object {
        const val MESSAGE_KEY = "settings.flag.quote.disabled"
    }
}

/**
 * The component answered with a value this application does not know, which points to diverging
 * versions of invoice and offer component. [messageKey] is what the UI translates with [field] and
 * [value]; [message] stays technical for the log.
 */
class QuoteUnknownValueException(val field: String, val value: String) :
    QuoteServiceException("The offer component sent an unknown value ($field: '$value')") {

    val messageKey: String get() = MESSAGE_KEY

    companion object {
        const val MESSAGE_KEY = "quote.error.unknownValue"
    }
}
