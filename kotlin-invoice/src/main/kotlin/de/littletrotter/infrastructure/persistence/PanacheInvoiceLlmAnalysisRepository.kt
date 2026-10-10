package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import de.littletrotter.domain.repository.InvoiceLlmAnalysisRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class PanacheInvoiceLlmAnalysisRepository : BasePanacheRepository(), InvoiceLlmAnalysisRepository {

    override fun findByInvoiceId(invoiceId: Long): List<InvoiceLlmAnalysis> =
        em.createQuery(
            "SELECT a FROM InvoiceLlmAnalysis a WHERE a.invoice.id = :id",
            InvoiceLlmAnalysis::class.java
        ).setParameter("id", invoiceId).resultList

    override fun findByInvoiceIdAndModelName(invoiceId: Long, modelName: String): InvoiceLlmAnalysis? =
        em.createQuery(
            "SELECT a FROM InvoiceLlmAnalysis a WHERE a.invoice.id = :id AND a.modelName = :model",
            InvoiceLlmAnalysis::class.java
        ).setParameter("id", invoiceId).setParameter("model", modelName)
            .setMaxResults(1).resultList.firstOrNull()

    override fun save(analysis: InvoiceLlmAnalysis): InvoiceLlmAnalysis = saveEntity(analysis) { analysis.id }
}
