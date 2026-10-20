package de.littletrotter.application.invoice

import de.littletrotter.domain.masterdata.showsCommercialRegister
import de.littletrotter.domain.invoice.RegistrationTaxNumberFreeze
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.ElectronicAddress
import de.littletrotter.domain.masterdata.LegalRegistrationIdentifier
import de.littletrotter.domain.masterdata.PartyIdentifier
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SellerVatRegistration
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.value.ForeignTaxNumberValidator
import de.littletrotter.shared.PartyDto
import de.littletrotter.shared.SchemedIdentifierDto
import de.littletrotter.shared.ShipToDto
import de.littletrotter.shared.VatRegistrationDto

/**
 * [InvoiceDtoMapper]'s party/address/ship-to mapping (BT-30, BG-13), extracted
 * verbatim. Pure value mapping, no collaborators — a plain object rather than a CDI bean.
 */
object PartyDtoMapper {
    private fun addressFields(addr: Address?): Triple<String?, String?, String?> =
        Triple(addr?.street, addr?.postalCode?.value, addr?.city)

    private fun PartyIdentifier?.toDto(): SchemedIdentifierDto? = this?.let { SchemedIdentifierDto(it.value, it.schemeId) }
    private fun ElectronicAddress?.toDto(): SchemedIdentifierDto? = this?.let { SchemedIdentifierDto(it.value, it.schemeId) }
    private fun LegalRegistrationIdentifier?.toDto(): SchemedIdentifierDto? = this?.let { SchemedIdentifierDto(it.value, it.schemeId) }

    /**
     * BG-13. `null` when there is no delivery address at all, or when it is equal *in
     * substance* to [billing] (field comparison, not object identity). Otherwise a slim [ShipToDto]
     * carrying the delivery address plus [recipient]'s name (BT-70 — BG-13 has no separate name).
     */
    fun buildShipTo(recipient: Recipient, billing: Address?, delivery: Address?): ShipToDto? {
        if (delivery == null) return null
        val samePlace = billing != null &&
            billing.street == delivery.street &&
            billing.postalCode == delivery.postalCode &&
            billing.city == delivery.city &&
            billing.country == delivery.country
        if (samePlace) return null
        return ShipToDto(
            name = recipient.name,
            street = delivery.street,
            postalCode = delivery.postalCode.value,
            city = delivery.city,
            country = delivery.country.alpha2.let { if (it == "DE") null else it },
        )
    }

    /**
     * INV-B — [invoiceNote] is already the invoice-owned snapshot. PDF and XML keep consuming the
     * same [PartyDto.footerNote] wire field, but no export-time path reads mutable seller defaults.
     */
    fun senderToDto(
        sender: SenderProfile,
        billing: Address?,
        invoiceNote: String?,
        frozen: RegistrationTaxNumberFreeze? = null,
    ): PartyDto {
        val (street, postalCode, city) = addressFields(billing)
        // A sole proprietor registered with a Handwerkskammer has no Handelsregister
        // entry at all; suppressing registerCourt/commercialRegisterNumber here (the one place the
        // domain rule is applied) covers both the PDF footer and the e-invoice legalRegistration.
        val showsRegister = sender.showsCommercialRegister()
        val effectiveRegisterCourt = sender.registerCourt.takeIf { showsRegister }
        val effectiveCommercialRegisterNumber = sender.commercialRegisterNumber.takeIf { showsRegister }
        return PartyDto(
            name = sender.name,
            street = street,
            postalCode = postalCode,
            city = city,
            country = billing?.country?.alpha2?.let { if (it == "DE") null else it },
            email = sender.email?.value,
            phone = sender.phone,
            vatId = sender.vatId?.value,
            iban = sender.iban?.value,
            bic = sender.bic?.value,
            taxNumber = sender.taxNumber?.value,
            globalId = sender.globalId,
            bankName = sender.bankName,
            managingDirector = sender.managingDirector,
            courtOfJurisdiction = sender.courtOfJurisdiction,
            registerCourt = effectiveRegisterCourt,
            commercialRegisterNumber = effectiveCommercialRegisterNumber,
            footerNote = invoiceNote,
            website = sender.website,
            registeredSeat = sender.registeredSeat,
            identifier = sender.sellerIdentifier.toDto(),
            // BT-30's scheme lives on a separate field (legalRegistrationSchemeId) from its value
            // (commercialRegisterNumber) — SenderProfile predates P1's paired-embeddable shape.
            legalRegistration = sender.legalRegistrationSchemeId?.let { scheme ->
                effectiveCommercialRegisterNumber?.let { value -> SchemedIdentifierDto(value, scheme) }
            },
            electronicAddress = sender.electronicAddress.toDto(),
            additionalVatRegistrations = sender.additionalVatRegistrations.map { registrationToDto(it, frozen) },
        )
    }

    /**
     * The tax number is left out when it equals the registration's VAT id without its prefix
     * ([ForeignTaxNumberValidator.duplicatesVatId]): the row would only repeat a number already printed.
     */
    private fun registrationToDto(registration: SellerVatRegistration, frozen: RegistrationTaxNumberFreeze?): VatRegistrationDto {
        // vatId is only ever null on a half-built row, which SellerVatRegistration.of
        // (the sole construction path) never produces; see its own KDoc.
        val vatId = registration.vatId!!.value
        val taxNumber = when {
            frozen == null -> registration.taxNumber
            frozen.country == registration.country -> frozen.taxNumber
            else -> null
        }?.value
        val printed = taxNumber?.takeUnless { ForeignTaxNumberValidator.duplicatesVatId(registration.country, it, vatId) }
        return VatRegistrationDto(
            country = registration.country.alpha2,
            vatId = vatId,
            taxNumber = printed,
            taxNumberLabelKey = printed?.let { ForeignTaxNumberValidator.labelKey(registration.country, it) },
        )
    }

    fun recipientToDto(recipient: Recipient, billing: Address?): PartyDto {
        val (street, postalCode, city) = addressFields(billing)
        return PartyDto(
            name = recipient.name,
            street = street,
            postalCode = postalCode,
            city = city,
            country = billing?.country?.alpha2?.let { if (it == "DE") null else it },
            email = recipient.email?.value,
            phone = recipient.phone,
            vatId = recipient.vatId?.value,
            globalId = recipient.globalId,
            customerNumber = recipient.customerNumber?.value,
            identifier = recipient.buyerIdentifier.toDto(),
            legalRegistration = recipient.legalRegistrationIdentifier.toDto(),
            electronicAddress = recipient.electronicAddress.toDto(),
            customerType = recipient.customerType?.name,
            vatIdVerificationStatus = recipient.vatIdVerificationStatus?.name,
        )
    }
}
