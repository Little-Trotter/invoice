package de.littletrotter.application.mail.port.outbound

import de.littletrotter.application.mail.InvoiceMailDispatchService
import de.littletrotter.application.mail.MailDispatchException
import java.time.Duration

/**
 * Mints a short-lived, presigned GET URL for one object in the invoice document
 * bucket.
 *
 * **Why the host presigns at all.** The two contexts own separate buckets with separate access
 * keys (`invoice-documents` here, `mail-journal` there). Handing the component a bare object key
 * would force it to hold credentials for the host's bucket — exactly the coupling the standalone
 * component exists to remove. Instead the host mints one URL per object; the component fetches the
 * bytes once and copies them into its own bucket while assembling the draft, so nothing in the
 * audit-relevant send path depends on a URL that will expire (see
 * [de.littletrotter.shared.mail.MailAttachmentHandover]'s own KDoc).
 *
 * The port lives in the application layer and its only implementation in
 * `infrastructure.storage`, per this module's onion rule (`ArchitectureRulesTest`).
 *
 * **`null` means "this deployment cannot presign", never "no URL needed".** The local-filesystem
 * document store has no signing mechanism, and there is no honest fallback: sending an invoice
 * mail without its invoice PDF is precisely the failure the contract's checksum fields exist to
 * prevent. [InvoiceMailDispatchService] therefore turns a `null` into a
 * [MailDispatchException] the user sees, rather than a mail with fewer attachments than the user
 * approved.
 */
fun interface DocumentPresignPort {

    /**
     * A presigned GET URL for [objectKey] plus the object's current byte length, valid for [ttl];
     * `null` when this deployment's document store cannot presign at all, or when the object is
     * not there any more.
     */
    fun presignGet(objectKey: String, ttl: Duration): PresignedDocument?
}

/**
 * One presigned object: where to fetch it and how large it is *right now*.
 *
 * [sizeBytes] is read at handover time rather than taken from `invoice_document.size_bytes` — that
 * column is nullable and is not written by the checksum-persisting reaction, and the contract
 * wants "the host's statement about the object at handover time" so the component can run its
 * total-mail-size budget check against something real
 * ([de.littletrotter.shared.mail.MailAttachmentHandover]).
 */
data class PresignedDocument(val url: String, val sizeBytes: Long)
