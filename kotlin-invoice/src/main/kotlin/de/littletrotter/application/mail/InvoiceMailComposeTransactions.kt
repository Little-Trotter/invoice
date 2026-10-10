package de.littletrotter.application.mail

import de.littletrotter.application.invoice.IssuedDocumentLocator
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.time.Duration

/** What the short read transaction of a mail handover found. */
sealed interface InvoiceMailPreparation {
    /** Everything the component needs: the mapped draft with presigned attachments. */
    data class Ready(val draft: InvoiceMailDraft) : InvoiceMailPreparation

    /** The document of record is still being generated: an issued, non-failed job has no result yet. */
    data object DocumentPending : InvoiceMailPreparation
}

/**
 * The read transaction of a mail handover: loads the invoice, picks the documents of its latest
 * issued generation job and presigns them, without touching the mail component.
 *
 * A separate bean so the transaction really closes before [InvoiceMailDispatchService] makes its
 * HTTP call (a self-invocation inside one bean would not pass the transactional interceptor).
 * Every document of the invoice is lazy, which is why the walk needs the transaction at all.
 */
@ApplicationScoped
class InvoiceMailComposeTransactions @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val presignPort: DocumentPresignPort,
    // Selects the latest COMPLETED issued job, whose result refs name exactly the documents to
    // attach (per-job object keys leave older jobs' rows in place; attaching all would duplicate).
    private val issuedDocumentLocator: IssuedDocumentLocator,
    private val reproductionSendBlockJournal: ReproductionSendBlockJournal,
    private val jobs: GenerationJobRepository,
) {

    /**
     * Turns the documents of the invoice's latest COMPLETED issued generation job into
     * [InvoiceMailAttachment]s — one PDF and one XML.
     *
     * Every export writes its own per-job object keys, so the invoice carries one row set per
     * completed job; only the latest issued job's rows are attached, never older ones.
     *
     * Only rows that carry **both** an object key and a SHA-256 qualify: the mail-dispatch contract
     * requires the checksum and states that a missing one must fail rather than silently disable
     * the component's verification. An invoice with no such row yet is [InvoiceMailPreparation.DocumentPending]
     * while an issued, non-failed generation job exists — the result simply has not arrived — and
     * otherwise a [MailDispatchException]; it is never mailed without its invoice.
     */
    @Transactional
    fun prepare(invoiceId: Long): InvoiceMailPreparation {
        val invoice = invoiceRepository.findById(invoiceId)
            ?: throw MailDispatchException("Invoice $invoiceId not found")
        val current = issuedDocumentLocator.currentPdf(invoiceId)
        if (current != null && current.document.reproduced) {
            // Journaled in its own transaction first: this one rolls back when the exception
            // propagates, but the rejected attempt must stay in the audit trail.
            reproductionSendBlockJournal.record(requireNotNull(current.document.id))
            throw ReproductionNotSendableException(invoice.invoiceNumber.value)
        }
        val attachable = attachableDocuments(invoice, issuedDocumentLocator.latestIssuedJobKeys(invoiceId))
        if (attachable.isEmpty()) {
            // Waiting only makes sense while a generation can still deliver; with none running the
            // user must start one, so the caller has to see an error instead of a hint that never ends.
            if (hasPendingIssuedJob(jobs.findByInvoiceId(invoiceId))) return InvoiceMailPreparation.DocumentPending
            throw MailDispatchException(
                "Invoice ${invoice.invoiceNumber.value} has no completed, checksummed document to attach — generate the e-invoice first",
            )
        }
        return InvoiceMailPreparation.Ready(InvoiceMailComposeMapper.toDraft(invoice, attachable.map(::handoverFor)))
    }

    private fun handoverFor(document: InvoiceDocument): InvoiceMailAttachment {
        val presigned = presignPort.presignGet(document.objectKey, ATTACHMENT_URL_TTL)
            ?: throw MailDispatchException(
                "Could not presign '${document.objectKey}' — this deployment's document store cannot hand attachments over",
            )
        return InvoiceMailAttachment(
            filename = document.fileName.ifBlank { document.objectKey.substringAfterLast('/') },
            contentType = document.mimeType.ifBlank { DEFAULT_CONTENT_TYPE },
            sizeBytes = presigned.sizeBytes,
            sha256 = document.sha256Checksum.orEmpty(),
            downloadUrl = presigned.url,
        )
    }

    private companion object {
        /**
         * How long a handed-over URL stays valid. Generous next to the component's own
         * "fetch once, at draft creation" behaviour, and deliberately far below the draft's own
         * TTL: the component copies the bytes into its own bucket immediately.
         */
        val ATTACHMENT_URL_TTL: Duration = Duration.ofMinutes(15)

        const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
    }
}
