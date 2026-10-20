package de.littletrotter.application.mail

import de.littletrotter.application.invoice.IssuedDocumentLocator
import de.littletrotter.application.mail.port.inbound.InvoiceMailReadinessQueryPort
import de.littletrotter.application.mail.port.inbound.MailSendGate
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * Answers whether an invoice can be mailed yet, with the same function the handover applies
 * ([mailSendGateOf]), so the offer and the handover never disagree.
 */
@ApplicationScoped
class InvoiceMailReadinessService @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val jobs: GenerationJobRepository,
    private val issuedDocumentLocator: IssuedDocumentLocator,
) : InvoiceMailReadinessQueryPort {

    @Transactional
    override fun readiness(invoiceId: Long): MailSendGate {
        val invoice = invoiceRepository.findById(invoiceId) ?: return MailSendGate.NOT_ISSUED
        return gateOf(invoice, jobs, issuedDocumentLocator)
    }
}

internal fun gateOf(invoice: Invoice, jobs: GenerationJobRepository, locator: IssuedDocumentLocator): MailSendGate {
    val invoiceId = requireNotNull(invoice.id)
    return mailSendGateOf(
        MailSendFacts(
            status = invoice.status,
            validationStatus = invoice.validationStatus,
            jobs = jobs.findByInvoiceId(invoiceId),
            hasAttachableDocument = attachableDocuments(invoice, locator.latestIssuedJobKeys(invoiceId)).isNotEmpty(),
            currentPdfReproduced = locator.currentPdf(invoiceId)?.document?.reproduced == true,
            recipientEmail = invoice.recipient?.email?.value,
        ),
    )
}
