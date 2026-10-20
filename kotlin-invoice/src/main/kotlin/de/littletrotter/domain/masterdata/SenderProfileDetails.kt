package de.littletrotter.domain.masterdata

import de.littletrotter.domain.value.Bic
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * The fields of a [SenderProfile] that the Anbieter form edits as one unit; see
 * [SenderProfile.update]. Defaults equal the values of a freshly created profile.
 */
data class SenderProfileDetails(
    val name: String,
    val taxNumber: TaxNumber? = null,
    val vatId: VatId? = null,
    val iban: Iban? = null,
    val bic: Bic? = null,
    val bankName: String? = null,
    val invoicePrefix: String = "IP-",
    val phone: String? = null,
    val email: Email? = null,
    val managingDirector: String? = null,
    val courtOfJurisdiction: String? = null,
    val registerCourt: String? = null,
    val website: String? = null,
    val registeredSeat: String? = null,
    val commercialRegisterNumber: String? = null,
    val sellerIdentifier: PartyIdentifier? = null,
    val legalRegistrationSchemeId: String? = null,
    val electronicAddress: ElectronicAddress? = null,
    val legalForm: LegalForm? = null,
    val craftsChamberRegistered: Boolean = false,
)

/** The current values of the fields [SenderProfile.update] writes. */
fun SenderProfile.editableDetails(): SenderProfileDetails = SenderProfileDetails(
    name = name,
    taxNumber = taxNumber,
    vatId = vatId,
    iban = iban,
    bic = bic,
    bankName = bankName,
    invoicePrefix = invoicePrefix,
    phone = phone,
    email = email,
    managingDirector = managingDirector,
    courtOfJurisdiction = courtOfJurisdiction,
    registerCourt = registerCourt,
    website = website,
    registeredSeat = registeredSeat,
    commercialRegisterNumber = commercialRegisterNumber,
    sellerIdentifier = sellerIdentifier,
    legalRegistrationSchemeId = legalRegistrationSchemeId,
    electronicAddress = electronicAddress,
    legalForm = legalForm,
    craftsChamberRegistered = craftsChamberRegistered,
)

/**
 * Moves a detached edit onto this managed entity (Hibernate dirty-tracking instead of `em.merge`
 * with a possibly stale `@Version`): every field of [SenderProfile.update] plus the default flag.
 * `SenderProfileFormDataTest` pins that the form carries the same field set, so an accepted edit
 * is never silently dropped on save.
 */
fun SenderProfile.copyEditableFieldsFrom(other: SenderProfile) {
    update(other.editableDetails())
    if (other.isDefault) markAsDefault() else revokeDefault()
}

/** Replaces the invoice number prefix, leaving all other edited fields as they are. */
fun SenderProfile.changeInvoicePrefix(prefix: String) {
    update(editableDetails().copy(invoicePrefix = prefix))
}

/** BT-34: replaces (or with `null` clears) the seller's electronic address. */
fun SenderProfile.changeElectronicAddress(address: ElectronicAddress?) {
    update(editableDetails().copy(electronicAddress = address))
}
