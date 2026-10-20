package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.dto.ModelReport
import de.littletrotter.application.invoice.port.outbound.InvoiceLlmAnalysisReadRepository
import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager

@ApplicationScoped
class InvoiceLlmAnalysisReadRepositoryImpl @Inject constructor(
    private val em: EntityManager,
) : InvoiceLlmAnalysisReadRepository {

    override fun findReports(invoiceId: Long): List<ModelReport> =
        em.createQuery("SELECT a FROM InvoiceLlmAnalysis a WHERE a.invoice.id = :id", InvoiceLlmAnalysis::class.java)
            .setParameter("id", invoiceId)
            .resultList
            .map(ModelReport::from)
}
