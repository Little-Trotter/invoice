package de.littletrotter.application.dto

import de.littletrotter.application.command.CommandResult

sealed interface InvoiceFromQuoteResult : CommandResult {

    /** The draft exists; [alreadyExisted] is `true` when an earlier call created it. */
    data class Created(val invoiceId: Long, val invoiceNumber: String, val alreadyExisted: Boolean) : InvoiceFromQuoteResult

    /** No draft was created; [reason] is a German sentence meant for the clerk. */
    data class Rejected(val reason: String) : InvoiceFromQuoteResult
}
