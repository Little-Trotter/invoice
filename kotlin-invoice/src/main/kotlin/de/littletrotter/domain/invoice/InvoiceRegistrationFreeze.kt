package de.littletrotter.domain.invoice

import de.littletrotter.shared.effectiveSellerRegistrationCountry

/**
 * Copies the tax number of the foreign registration this invoice is billed under onto the seller
 * snapshot (cleared when there is none). Called once when the invoice is issued, because the
 * registration can still change while it is a draft; afterwards a profile edit cannot alter what
 * a re-rendered document prints.
 *
 * A selected registration only counts when the recipient sits in its country; that decision is
 * [effectiveSellerRegistrationCountry], the same one PDF and XML use, so the frozen value never
 * disagrees with what they print.
 */
fun Invoice.freezeRegistrationTaxNumber() {
    val snapshot = partySnapshotList.firstOrNull { it.partyRole == PartyRole.SELLER } ?: return
    val recipientCountry = recipient?.let { it.billingAddress ?: deliveryAddress ?: it.deliveryAddress }?.country
    val effective = effectiveSellerRegistrationCountry(sellerVatRegistrationCountry?.alpha2, recipientCountry?.alpha2)
    val registration = effective?.let { country ->
        effectiveSender()?.additionalVatRegistrations?.firstOrNull { it.country.alpha2 == country }
    }
    snapshot.freezeRegistrationTaxNumber(registration?.taxNumber)
}
