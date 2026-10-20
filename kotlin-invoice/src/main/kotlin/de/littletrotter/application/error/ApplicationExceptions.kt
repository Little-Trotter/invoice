package de.littletrotter.application.error

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceDomainNotFoundException
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.exception.InvoiceStatusTransitionException
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.exception.ReceiptLockedByIssuedInvoiceException

sealed class ApplicationUserException(
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause)

/** Open so the receipt-size-normalisation exceptions (`application.invoice`, same package
 *  family, sealed-hierarchy-compatible) can carry typed numeric properties (uploaded/limit sizes,
 *  page counts) alongside a plain validation failure instead of duplicating message-only
 *  subclasses of [ApplicationUserException] itself. */
open class ApplicationValidationException(
    message: String,
    cause: Throwable? = null,
) : ApplicationUserException(message, cause)

class ApplicationNotFoundException(
    message: String,
    cause: Throwable? = null,
) : ApplicationUserException(message, cause)

/** A request that is well-formed but conflicts with the resource's current state
 *  (e.g. an unauthorized status transition, or an edit to a finalized invoice). */
open class ApplicationConflictException(
    message: String,
    cause: Throwable? = null,
) : ApplicationUserException(message, cause)

object ApplicationExceptionTranslator {

    fun validation(message: String, cause: Throwable? = null): ApplicationValidationException =
        ApplicationValidationException(message, cause)

    fun from(exception: Throwable, fallbackMessage: String? = null): ApplicationUserException = when (exception) {
        is ApplicationUserException -> exception
        // These four domain-exception arms always carry a non-null String `message` (each
        // constructs its own message from a template, see `domain/exception/Exceptions.kt`) —
        // `fallbackMessage` can never actually win here. `exception.message!!` says that
        // plainly instead of an `?: fallbackMessage ?: "..."` chain whose two fallback branches
        // are unreachable dead code for every member of this sealed hierarchy.
        is InvoiceDomainNotFoundException -> ApplicationNotFoundException(exception.message!!, exception)
        is InvoiceStatusTransitionException, is InvoiceImmutableException, is ReceiptLockedByIssuedInvoiceException ->
            ApplicationConflictException(exception.message!!, exception)
        is InvoiceValidationException -> ApplicationValidationException(exception.message!!, exception)
        is InvalidParameterException -> ApplicationValidationException(exception.message!!, exception)
        is IllegalArgumentException -> ApplicationValidationException(exception.message ?: fallbackMessage ?: "Invalid input", exception)
        else -> ApplicationValidationException(fallbackMessage ?: exception.message ?: "Unknown error", exception)
    }
}
