package de.littletrotter.application.export

/**
 * Which of the two sources a header row's party columns (seller/buyer) were actually
 * filled in from. A finalized invoice's frozen [de.littletrotter.domain.invoice.InvoicePartySnapshot]
 * always wins over the live master-data association; a reader cannot otherwise tell a frozen value
 * from one that may move tomorrow if the referenced `SenderProfile`/`Recipient` row is edited later.
 */
enum class PartySource {
    SNAPSHOT,
    MASTER_DATA,
}
