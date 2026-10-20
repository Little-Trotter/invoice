package de.littletrotter.fixture

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoicePartySnapshot
import de.littletrotter.domain.invoice.PartyRole
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.value.VatId
import java.time.OffsetDateTime

/**
 * A snapshot of [sender] taken the way finalization does. [name] and [vatId] overlay the copied
 * identity, the way a quote import corrects a party; [invoice] attaches it to its owner without
 * adding it to the owner's snapshot list.
 */
fun aSellerSnapshot(
    sender: SenderProfile,
    name: String? = null,
    vatId: VatId? = null,
    id: Long? = null,
    invoice: Invoice? = null,
    createdAt: OffsetDateTime? = null,
): InvoicePartySnapshot = InvoicePartySnapshot.forSeller(sender)
    .finish(name, vatId, id, invoice, createdAt)

/** A snapshot of [recipient] taken the way finalization does; [buyerReference] is BT-10. */
fun aBuyerSnapshot(
    recipient: Recipient,
    buyerReference: String? = recipient.routingId,
    name: String? = null,
    vatId: VatId? = null,
    id: Long? = null,
    invoice: Invoice? = null,
    createdAt: OffsetDateTime? = null,
): InvoicePartySnapshot = InvoicePartySnapshot.forBuyer(recipient, buyerReference)
    .finish(name, vatId, id, invoice, createdAt)

/**
 * A snapshot that carries only a role and a name, for tests of snapshot validation and copying
 * that do not need a master-data party behind them. A sender or recipient without data is the
 * cheapest source of the empty identity fields; the [name] and [buyerReference] are set on top
 * through the same two domain operations the factories offer.
 */
fun aPartySnapshot(
    role: PartyRole,
    name: String = "",
    buyerReference: String? = null,
    id: Long? = null,
    invoice: Invoice? = null,
    createdAt: OffsetDateTime? = null,
): InvoicePartySnapshot = when (role) {
    PartyRole.SELLER -> InvoicePartySnapshot.forSeller(SenderProfile())
    PartyRole.BUYER -> InvoicePartySnapshot.forBuyer(Recipient(), buyerReference)
}.finish(name, null, id, invoice, createdAt)

private fun InvoicePartySnapshot.finish(
    name: String?,
    vatId: VatId?,
    id: Long?,
    invoice: Invoice?,
    createdAt: OffsetDateTime?,
): InvoicePartySnapshot {
    if (name != null || vatId != null) correctIdentity(name ?: this.name, vatId)
    if (id != null) PersistenceEmulation.assignId(this, id)
    if (invoice != null) PersistenceEmulation.hydrate(this, "invoice", invoice)
    if (createdAt != null) stampCreationIfUnset(createdAt)
    return this
}
