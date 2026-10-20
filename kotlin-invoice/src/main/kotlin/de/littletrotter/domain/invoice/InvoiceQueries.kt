package de.littletrotter.domain.invoice

import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.tax.VatCategory

// A cancellation document is read-only from the moment it exists, regardless
// of its own status: it is the system's automatic 1:1 copy of the original invoice's items and
// documents, not a fresh draft someone is supposed to keep editing.
//
// This predicate keys on CORRECTED_INVOICE (`384`), not CREDIT_NOTE (`381`), along with the
// cancellation flow itself (InvoiceCancellationService.createCreditNote); CREDIT_NOTE is reserved for a
// genuine credit note and carries no read-only invariant of its own today, since nothing in
// this codebase produces one.
//
// Read-only is not the same predicate as terminal: ISSUED keeps an outgoing edge (the
// cancellation transition to CANCELLED) but is still immutable in every other respect, so this
// spells out InvoiceStatus.isReadOnly explicitly rather than InvoiceStateMachine.isTerminal.
fun Invoice.isReadOnly(): Boolean = status.isReadOnly || typeCode == InvoiceTypeCode.CORRECTED_INVOICE

/**
 * Whether this invoice carries a Reverse-Charge (`AE`) item, derived purely from
 * each item's tax snapshot/category, independent of [Invoice.reverseCharge] (the header decision is
 * leading, not a derivation of this one). Still the wire-export truth
 * ([de.littletrotter.application.invoice.InvoiceDtoMapper] keeps reading this, not the raw
 * column) and used by the item-consistency check to find items that disagree with
 * the header decision.
 */
fun Invoice.hasReverseChargeItems(): Boolean = itemList.any { it.taxCategory == VatCategory.REVERSE_CHARGE }

/** The frozen seller identity a finalized invoice must read instead of the live
 *  [Invoice.senderProfile] association. `null` only for an invoice created before this snapshot
 *  existed. */
fun Invoice.sellerSnapshot(): InvoicePartySnapshot? = partySnapshotList.firstOrNull { it.partyRole == PartyRole.SELLER }

/** The frozen buyer identity, BT-10 included. See [sellerSnapshot]. */
fun Invoice.buyerSnapshot(): InvoicePartySnapshot? = partySnapshotList.firstOrNull { it.partyRole == PartyRole.BUYER }

fun Invoice.canTransitionTo(target: InvoiceStatus): Boolean = InvoiceStateMachine.isLegal(status, target)

/** The [Invoice.validationStatus] counterpart to [canTransitionTo]. */
fun Invoice.canTransitionValidationStatusTo(target: ValidationStatus): Boolean = validationStatus.canTransitionTo(target)

/** The current header values as a change object, the starting point for a single-value edit via
 *  [Invoice.updateHeader]. */
fun Invoice.headerChange(): InvoiceHeaderChange = InvoiceHeaderChange(
    invoiceDate = invoiceDate,
    servicePeriodFrom = servicePeriodFrom,
    servicePeriodTo = servicePeriodTo,
    deliveryDate = deliveryDate,
    dueDate = dueDate,
    paymentTermsDays = paymentTermsDays,
    currency = currency,
    invoiceFormat = invoiceFormat,
    sellerVatRegistrationCountry = sellerVatRegistrationCountry,
)

/** The current parties and party-derived choices, the starting point for a single-value edit via
 *  [Invoice.assignParties]. */
fun Invoice.parties(): InvoiceParties = InvoiceParties(
    senderProfile = senderProfile,
    recipient = recipient,
    billingAddress = billingAddress,
    deliveryAddress = deliveryAddress,
    b2bProfile = b2bProfile,
    paymentTerm = paymentTerm,
    documentLanguage = documentLanguage.code,
)

/**
 * The tax number of the foreign registration an invoice is billed under, as frozen at issue.
 * [taxNumber] `null` means the invoice froze "none".
 */
data class RegistrationTaxNumberFreeze(val country: de.littletrotter.shared.country.Country?, val taxNumber: de.littletrotter.domain.value.ForeignTaxNumber?)

/**
 * The frozen registration tax number of a read-only document; `null` for a draft, which still
 * reads the live profile. A read-only document without a seller snapshot never went through the
 * issue path that freezes the number (rows loaded straight into the database), so nothing was
 * frozen and it reads the live profile as well: a missing snapshot is not a frozen "none".
 */
fun Invoice.registrationTaxNumberFreeze(): RegistrationTaxNumberFreeze? =
    if (isReadOnly()) sellerSnapshot()?.let { RegistrationTaxNumberFreeze(sellerVatRegistrationCountry, it.registrationTaxNumber) } else null
