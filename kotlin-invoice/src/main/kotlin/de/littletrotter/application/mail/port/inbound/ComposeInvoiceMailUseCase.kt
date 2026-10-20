package de.littletrotter.application.mail.port.inbound

import de.littletrotter.application.dto.MailComposeResult
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.command.CommandEntryPoint

/** Input port of the "send by e-mail" action on an invoice: decides whether the entry point exists and prepares the draft. */
interface ComposeInvoiceMailUseCase : CommandEntryPoint {
    /** Whether the mail entry point is offered at all: deployed and switched on. */
    fun isAvailable(): Boolean

    /**
     * Turns [invoiceId] into a draft in the mail component and returns where to open it. Every
     * unmet precondition, including a document that is not generated yet, throws
     * [MailDispatchException]. Writes nothing in the invoicing application.
     */
    fun compose(invoiceId: Long): MailComposeResult
}
