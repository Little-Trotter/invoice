package de.littletrotter.domain.repository

import de.littletrotter.domain.compliance.ValidationFinding

interface ValidationFindingRepository {
    /** Persists one row per finding for a single job's completion. No-op for an empty list —
     *  callers never need to special-case a `VALID` result with zero findings. */
    fun saveAll(findings: List<ValidationFinding>)

    /** All findings for an invoice, across every job that ever completed for it — a job's
     *  findings never get overwritten or removed by a later job. */
    fun findByInvoiceId(invoiceId: Long): List<ValidationFinding>

    fun findByGenerationJobId(generationJobId: Long): List<ValidationFinding>
}
