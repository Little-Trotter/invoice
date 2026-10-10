package de.littletrotter.application.invoice

import de.littletrotter.domain.event.InvoiceFinalized
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.ObservesAsync
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * Finalization no longer auto-creates document generation jobs.
 * Jobs are created explicitly via user actions in the export UI.
 */
@ApplicationScoped
class InvoiceDocumentEventHandler @Inject constructor() {

    private val log = Logger.getLogger(InvoiceDocumentEventHandler::class.java)

    fun onInvoiceFinalized(@ObservesAsync event: InvoiceFinalized) {
        log.debugf("Invoice finalized event received for invoice %d (%s); no automatic document job is created", event.invoiceId, event.invoiceNumber)
    }
}
