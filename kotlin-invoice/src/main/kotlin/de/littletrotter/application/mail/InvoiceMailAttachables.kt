package de.littletrotter.application.mail

import de.littletrotter.domain.invoice.DocumentGenerationStatus
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceDocument

/** The GoBD archive bundle stays in the store; the component only accepts PDF/XML attachments. */
private const val ARCHIVE_CONTENT_TYPE = "application/gzip"

/**
 * The single definition of "mailable": completed rows with object key and checksum whose key is one
 * of [jobKeys] (the latest issued job). Shared by the handover and the readiness query so the
 * Send button is enabled exactly when a handover would find something to attach.
 */
internal fun attachableDocuments(invoice: Invoice, jobKeys: Set<String>): List<InvoiceDocument> =
    invoice.documents
        .filter { isAttachable(it) && it.objectKey in jobKeys }
        .distinctBy { it.objectKey }

/**
 * True while an issued generation job exists that has not failed: its result may still arrive, so
 * a missing attachable document means "wait". Without such a job nothing is being generated and
 * waiting would never end. Shared by the handover and the readiness query.
 */
internal fun hasPendingIssuedJob(jobs: List<GenerationJob>): Boolean =
    jobs.any { it.issued && it.status != GenerationJobStatus.FAILED }

private fun isAttachable(document: InvoiceDocument): Boolean =
    document.generationStatus == DocumentGenerationStatus.COMPLETED &&
        document.objectKey.isNotBlank() &&
        document.mimeType != ARCHIVE_CONTENT_TYPE &&
        !document.sha256Checksum.isNullOrBlank()
