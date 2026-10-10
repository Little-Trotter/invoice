package de.littletrotter.application.mail.port.inbound

/** Whether the invoice's document of record can be attached to a mail yet. */
enum class MailDocumentReadiness {
    /** No issuing generation has been requested (or it failed): sending starts a new one. */
    NOT_REQUESTED,

    /** An issuing generation exists but its result is not yet complete and checksummed. */
    PENDING,

    /** A completed, checksummed document is available. */
    READY,
}

/** Reads the mail readiness of one invoice's document; used to gate the send button. */
fun interface InvoiceMailReadinessQueryPort {

    fun readiness(invoiceId: Long): MailDocumentReadiness
}
