package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.compliance.ValidationFinding
import de.littletrotter.domain.repository.ValidationFindingRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class PanacheValidationFindingRepository : BasePanacheRepository(), ValidationFindingRepository {

    // REQUIRES_NEW, not the default REQUIRED: GenerationJobResultHandler.markCompleted is itself
    // @Transactional, and a failure in a REQUIRED-joined call marks that *shared* transaction
    // rollback-only via the interceptor before the caller's try/catch ever runs — the job
    // completion and validationStatus update would silently roll back too. A dedicated
    // transaction confines a findings-write failure to just these rows.
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    override fun saveAll(findings: List<ValidationFinding>) {
        findings.forEach { em.persist(it) }
    }

    override fun findByInvoiceId(invoiceId: Long): List<ValidationFinding> =
        em.createQuery(
            "SELECT f FROM ValidationFinding f WHERE f.invoiceId = :invoiceId ORDER BY f.createdAt, f.id",
            ValidationFinding::class.java,
        ).setParameter("invoiceId", invoiceId).resultList

    override fun findByGenerationJobId(generationJobId: Long): List<ValidationFinding> =
        em.createQuery(
            "SELECT f FROM ValidationFinding f WHERE f.generationJobId = :jobId ORDER BY f.id",
            ValidationFinding::class.java,
        ).setParameter("jobId", generationJobId).resultList
}
