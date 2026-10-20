package de.littletrotter.domain.invoice

import de.littletrotter.domain.masterdata.ValueSchemePair

/** One changed field; `field` is a display-only label, never re-parsed. */
data class PartySnapshotFieldDiff(val field: String, val oldValue: String?, val newValue: String?)

/** Preview before party snapshot refresh. Computed read-only; must never write. */
data class PartySnapshotRefreshPreview(
    val sellerChanges: List<PartySnapshotFieldDiff>,
    val buyerChanges: List<PartySnapshotFieldDiff>,
) {
    val hasChanges: Boolean get() = sellerChanges.isNotEmpty() || buyerChanges.isNotEmpty()
}

/** Display rendering shared by every identifier-shaped diff field below — one definition instead
 *  of the identical `"${it.value} (${it.schemeId})"` template repeated per field. */
private fun displayValueScheme(pair: ValueSchemePair?): String? = pair?.let { "${it.value} (${it.schemeId})" }

private fun diffFields(old: InvoicePartySnapshot?, new: InvoicePartySnapshot): List<PartySnapshotFieldDiff> {
    fun field(label: String, oldValue: String?, newValue: String?): PartySnapshotFieldDiff? =
        if (oldValue != newValue) PartySnapshotFieldDiff(label, oldValue, newValue) else null

    return listOfNotNull(
        field("Name", old?.name, new.name),
        field("Kennung (BT-29/46)", displayValueScheme(old?.identifier), displayValueScheme(new.identifier)),
        field("Registerkennung (BT-30/47)", displayValueScheme(old?.legalRegistration), displayValueScheme(new.legalRegistration)),
        field("USt-IdNr.", old?.vatId?.value, new.vatId?.value),
        field("Steuernummer", old?.taxNumber?.value, new.taxNumber?.value),
        field("Elektronische Adresse (BT-34/49)", displayValueScheme(old?.electronicAddress), displayValueScheme(new.electronicAddress)),
        field("Leitweg-ID (BT-10)", old?.buyerReference, new.buyerReference),
    )
}

/** P2 — pure diff computation; `invoice`'s existing snapshot rows (if any) vs. what freshly
 *  snapshotting its current [Invoice.senderProfile]/[Invoice.recipient] would produce. */
fun Invoice.previewPartySnapshotRefresh(): PartySnapshotRefreshPreview {
    val sender = requireNotNull(senderProfile) { "Invoice has no senderProfile to snapshot." }
    val recipient = requireNotNull(recipient) { "Invoice has no recipient to snapshot." }
    val newSeller = InvoicePartySnapshot.forSeller(sender)
    val newBuyer = InvoicePartySnapshot.forBuyer(recipient, references.buyerReference ?: recipient.routingId)
    val oldSeller = partySnapshotList.firstOrNull { it.partyRole == PartyRole.SELLER }
    val oldBuyer = partySnapshotList.firstOrNull { it.partyRole == PartyRole.BUYER }
    return PartySnapshotRefreshPreview(diffFields(oldSeller, newSeller), diffFields(oldBuyer, newBuyer))
}
