package de.littletrotter.application.mail.port.inbound

/**
 * Why an invoice may or may not be mailed right now. Declared in priority order: when several
 * conditions are violated, the first one listed is the reason reported.
 */
enum class MailSendGate {
    /** Every condition is met: the invoice can be mailed. */
    READY,

    /** A send generation is still running, so the document of record is not final. */
    JOB_IN_FLIGHT,

    /** The invoice has not been sent (issued) yet. */
    NOT_ISSUED,

    /** The e-invoice validation did not pass. */
    NOT_VALID,

    /** There is no completed, checksummed document to attach. */
    DOCUMENT_MISSING,

    /** The current document is a reproduction, which must never leave the system by mail. */
    REPRODUCED,

    /** The recipient has no e-mail address. */
    NO_RECIPIENT_EMAIL,
}

/** Reads the send gate of one invoice; used to gate the send button and the mail handover. */
fun interface InvoiceMailReadinessQueryPort {

    fun readiness(invoiceId: Long): MailSendGate
}
