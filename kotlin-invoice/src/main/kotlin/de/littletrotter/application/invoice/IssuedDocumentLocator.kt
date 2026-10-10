package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceDocumentRepository
import de.littletrotter.shared.GenerationJobResultRefs
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** The PDF row of an invoice's document of record together with the job that produced it. */
class IssuedPdf(val document: InvoiceDocument, val jobId: Long?)

/**
 * Single definition of "the document of an invoice": the artifacts named by the result refs of its
 * latest COMPLETED issued generation job. Every job writes its own per-job object keys, so an
 * invoice carries several rows; only this job's rows are the document that is mailed, downloaded
 * and, when lost, recovered.
 */
@ApplicationScoped
class IssuedDocumentLocator @Inject constructor(
    private val generationJobRepository: GenerationJobRepository,
    private val invoiceDocumentRepository: InvoiceDocumentRepository,
) {

    /** The PDF and XML object keys of the latest COMPLETED issued job (empty if there is none). */
    fun latestIssuedJobKeys(invoiceId: Long): Set<String> {
        val refs = latestIssuedJob(invoiceId)?.let { GenerationJobResultRefs.decode(it.resultRefs) } ?: return emptySet()
        return setOfNotNull(refs.objectKey, refs.zugferdObjectKey).filterTo(mutableSetOf()) { it.isNotBlank() }
    }

    /**
     * The PDF row of the latest COMPLETED issued job in any generation status: a row whose original
     * was lost is still the document of record while its replacement renders. `null` when the
     * invoice has no issued job or the job's PDF was never tracked.
     */
    fun currentPdf(invoiceId: Long): IssuedPdf? {
        val job = latestIssuedJob(invoiceId) ?: return null
        val key = GenerationJobResultRefs.decode(job.resultRefs)?.objectKey?.takeIf { it.isNotBlank() } ?: return null
        val document = invoiceDocumentRepository.findByObjectKey(key) ?: return null
        return IssuedPdf(document, job.id)
    }

    private fun latestIssuedJob(invoiceId: Long): GenerationJob? =
        generationJobRepository.findByInvoiceId(invoiceId)
            .filter { it.status == GenerationJobStatus.COMPLETED && it.issued }
            .maxWithOrNull(compareBy({ it.finishedAt }, { it.id }))
}
