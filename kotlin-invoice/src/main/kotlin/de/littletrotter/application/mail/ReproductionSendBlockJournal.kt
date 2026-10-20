package de.littletrotter.application.mail

import de.littletrotter.application.invoice.InvoiceDocumentAuditTouch
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.application.invoice.port.outbound.InvoiceDocumentReadRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * Writes the audit row of a rejected reproduction send in its own transaction: the compose call
 * that triggers it rolls back with the rejection, the attempt must not.
 */
@ApplicationScoped
class ReproductionSendBlockJournal @Inject constructor(
    private val invoiceDocumentReadRepository: InvoiceDocumentReadRepository,
    private val touch: InvoiceDocumentAuditTouch,
) {
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun record(documentId: Long) {
        val sha = invoiceDocumentReadRepository.findDocumentChecksumById(documentId)
        touch.touch(documentId, InvoiceAuditAction.DOCUMENT_REPRODUCTION_SEND_BLOCKED, mapOf("hash_new" to sha))
    }
}
