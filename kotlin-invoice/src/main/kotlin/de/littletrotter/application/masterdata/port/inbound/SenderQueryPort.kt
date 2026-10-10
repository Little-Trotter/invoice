package de.littletrotter.application.masterdata.port.inbound

import de.littletrotter.application.dto.SenderSummaryDto

/**
 * Input port of the "look up a sender profile" use case: the sender reads the invoice list
 * (Anbieter filter combo) and the create-invoice dialog need, and nothing else. Sender profiles
 * are maintained through the reference-data screens, not through this port.
 */
interface SenderQueryPort {
    /** The complete set of sender profiles, no page-size ceiling. Senders are a small,
     *  admin-maintained set the seller combo loads eagerly, so "all" must mean all: a page-capped
     *  result would make every sender beyond the first page silently unselectable in
     *  [de.littletrotter.ui.view.invoice.InvoiceCreateDialog]'s seller combo. */
    fun findAllSenders(): List<SenderSummaryDto>

    fun findSenderById(id: Long): SenderSummaryDto?
}
