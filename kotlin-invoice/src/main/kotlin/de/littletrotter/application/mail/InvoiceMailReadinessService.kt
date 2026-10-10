package de.littletrotter.application.mail

import de.littletrotter.application.invoice.IssuedDocumentLocator
import de.littletrotter.application.mail.port.inbound.InvoiceMailReadinessQueryPort
import de.littletrotter.application.mail.port.inbound.MailDocumentReadiness
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * Answers whether the document of record of an invoice can be mailed yet, with the same rule the
 * handover applies ([attachableDocuments]), so the offer and the handover never disagree.
 */
@ApplicationScoped
class InvoiceMailReadinessService @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val jobs: GenerationJobRepository,
    private val issuedDocumentLocator: IssuedDocumentLocator,
) : InvoiceMailReadinessQueryPort {

    @Transactional
    override fun readiness(invoiceId: Long): MailDocumentReadiness {
        val issued = jobs.findByInvoiceId(invoiceId).filter { it.issued }
        val invoice = if (issued.isEmpty()) null else invoiceRepository.findById(invoiceId)
        val ready = invoice != null &&
            attachableDocuments(invoice, issuedDocumentLocator.latestIssuedJobKeys(invoiceId)).isNotEmpty()
        return when {
            invoice == null -> MailDocumentReadiness.NOT_REQUESTED
            ready -> MailDocumentReadiness.READY
            hasPendingIssuedJob(issued) -> MailDocumentReadiness.PENDING
            else -> MailDocumentReadiness.NOT_REQUESTED
        }
    }
}
