package de.littletrotter.application.invoice

import de.littletrotter.application.shared.AuditActionOverride
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.invoice.DocumentGenerationStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.repository.InvoiceDocumentRepository
import de.littletrotter.shared.GenerationJobResultRefs
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.OffsetDateTime

/**
 * Writes the SHA-256 checksums the worker computed right after storing the bytes
 * ([de.littletrotter.shared.ExportResult.sha256]/`zugferdXmlSha256`/archive checksum) into
 * `invoice_document.sha256_checksum`, one row per archived artifact (PDF, XML, archive package).
 *
 * Runs inside the caller's transaction (no own `@Transactional`): the checksum and key assignment
 * commit atomically with the job's COMPLETED status, so no reader can ever see a completed job
 * whose documents still carry no (or a stale) checksum. Because every job owns its own object keys,
 * each row points at the file of exactly the job that completed it.
 */
@ApplicationScoped
class DocumentChecksumPersister @Inject constructor(
    private val invoiceDocumentRepository: InvoiceDocumentRepository,
    private val time: TimeSource,
    private val auditActionOverride: AuditActionOverride,
) {

    /** One generated artifact's own row fields. */
    private data class DocumentArtifact(
        val documentType: String,
        val objectKey: String?,
        val checksum: String?,
        val mimeType: String,
    )

    /**
     * Persists a row for every artifact in [resultRefs] that has both an object key and a
     * checksum. Does nothing when [resultRefs] decodes to no usable refs (`null` or a legacy
     * producer). A write failure propagates so the surrounding completion rolls back and is retried.
     */
    fun persist(
        invoice: Invoice,
        resultRefs: String?,
        trigger: GenerationJobTrigger = GenerationJobTrigger.MANUAL,
        jobId: Long? = null,
    ) {
        val refs = GenerationJobResultRefs.decode(resultRefs) ?: return
        persistPdfRow(invoice, DocumentArtifact(DOCUMENT_TYPE_PDF, refs.objectKey, refs.sha256, MIME_PDF), trigger, jobId)
        persistRow(invoice, DocumentArtifact(DOCUMENT_TYPE_XML, refs.zugferdObjectKey, refs.zugferdXmlSha256, MIME_XML))
        // Absent for a draft export or the one degraded Factur-X outcome with no XML at all;
        // persistRow skips an incomplete pair.
        persistRow(invoice, DocumentArtifact(DOCUMENT_TYPE_ARCHIVE, refs.archiveObjectKey, refs.archiveSha256, MIME_ARCHIVE))
    }

    /**
     * The PDF row of a recovery job replaces the lost original: it is journaled as the completion
     * of the regeneration/reproduction (old and new hash) and, for a reproduction, tagged so that
     * the document is never mailed. Any other PDF row is persisted as before.
     */
    private fun persistPdfRow(invoice: Invoice, artifact: DocumentArtifact, trigger: GenerationJobTrigger, jobId: Long?) {
        if (!trigger.isRecovery) {
            persistRow(invoice, artifact)
            return
        }
        val hashOld = invoice.documents
            .filter { it.documentType == DOCUMENT_TYPE_PDF && it.generationStatus == DocumentGenerationStatus.LOST }
            .maxByOrNull { it.generatedAt ?: OffsetDateTime.MIN }
            ?.sha256Checksum
        val reproduction = trigger == GenerationJobTrigger.REPRODUCTION
        val action = if (reproduction) InvoiceAuditAction.DOCUMENT_REPRODUCED else InvoiceAuditAction.DOCUMENT_REGENERATED
        val context = mapOf("phase" to "COMPLETED", "hash_old" to hashOld, "hash_new" to artifact.checksum, "job_id" to jobId)
        auditActionOverride.withAction(action, context) {
            persistRow(invoice, artifact) { doc ->
                if (reproduction) {
                    doc.reproduced = true
                    doc.reproducedAt = time.nowUtc()
                    doc.reproductionOfSha256 = hashOld
                }
            }
        }
    }

    /** Skips an artifact whose key or checksum is missing — an incomplete pair must never produce
     *  a row that looks verified but isn't. Upserts by object key so a redelivered result updates
     *  the existing row instead of accumulating duplicates. */
    private fun persistRow(invoice: Invoice, artifact: DocumentArtifact, tag: (InvoiceDocument) -> Unit = {}) {
        val objectKey = artifact.objectKey
        val checksum = artifact.checksum
        if (objectKey.isNullOrBlank() || checksum.isNullOrBlank()) return
        val doc = invoiceDocumentRepository.findByObjectKey(objectKey) ?: InvoiceDocument().apply {
            this.invoice = invoice
            this.shortName = objectKey.substringAfterLast('/')
            this.documentType = artifact.documentType
            this.objectKey = objectKey
            this.fileName = objectKey.substringAfterLast('/')
            this.mimeType = artifact.mimeType
        }
        doc.sha256Checksum = checksum
        doc.generatedAt = time.nowUtc()
        doc.generationStatus = DocumentGenerationStatus.COMPLETED
        tag(doc)
        invoiceDocumentRepository.save(doc)
    }

    private companion object {
        const val DOCUMENT_TYPE_PDF = "PDF"
        const val DOCUMENT_TYPE_XML = "XML"
        const val DOCUMENT_TYPE_ARCHIVE = "ARCHIVE"
        const val MIME_PDF = "application/pdf"
        const val MIME_XML = "application/xml"
        const val MIME_ARCHIVE = "application/gzip"
    }
}
