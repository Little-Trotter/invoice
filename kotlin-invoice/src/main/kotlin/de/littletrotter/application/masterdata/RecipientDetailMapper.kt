package de.littletrotter.application.masterdata

import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.RecipientDetailDto
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.spi.party.PartyKind
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Assembles [RecipientDetailDto] from a loaded [Recipient]: the entity fields, the buyer's SPI
 * extension pairs and both addresses — the recipient dialog's whole read model in one call.
 * Shared by [RecipientQueries.findRecipientDetail] (a plain read) and [ManageRecipient] (the
 * value it hands back after a write), so the mapping lives in exactly one place.
 */
@ApplicationScoped
class RecipientDetailMapper @Inject constructor(
    private val addressManagementService: AddressManagementService,
    private val partyFormExtensionRegistry: PartyFormExtensionRegistry,
) {
    fun toDetailDto(recipient: Recipient): RecipientDetailDto {
        val id = recipient.id ?: error("Recipient must be persisted before it can be mapped to RecipientDetailDto.")
        val billingAddress = addressManagementService.getBillingAddressForCustomer(recipient).orElse(null) ?: recipient.billingAddress
        val deliveryAddress = addressManagementService.getDeliveryAddressForCustomer(recipient).orElse(null) ?: recipient.deliveryAddress
        val partyPairs = partyFormExtensionRegistry.enabledAspects(PartyKind.BUYER)
            .associate { (spec, extension) -> spec.id to extension.read(recipient, PartyKind.BUYER, spec) }
        return RecipientDetailDto(
            id = id,
            version = recipient.version,
            customerNumber = recipient.customerNumber?.value,
            shortHash = recipient.shortHash,
            name = recipient.name,
            vatId = recipient.vatId?.value,
            routingId = recipient.routingId,
            email = recipient.email?.value,
            phone = recipient.phone,
            contactPerson = recipient.contactPerson,
            iban = recipient.iban?.value,
            customerType = recipient.customerType,
            invoiceFormat = recipient.invoiceFormat,
            documentLanguage = recipient.documentLanguage,
            reverseChargePreference = recipient.reverseChargePreference,
            buyerIdentifierValue = recipient.buyerIdentifier?.value,
            buyerIdentifierSchemeId = recipient.buyerIdentifier?.schemeId,
            legalRegistrationValue = recipient.legalRegistrationIdentifier?.value,
            legalRegistrationSchemeId = recipient.legalRegistrationIdentifier?.schemeId,
            b2bProfileId = recipient.b2bProfile?.id,
            partyPairs = partyPairs,
            billingAddress = billingAddress?.toAddressDto(),
            deliveryAddress = deliveryAddress?.toAddressDto(),
            vatIdVerificationStatus = recipient.vatIdVerificationStatus,
            vatIdVerifiedAt = recipient.vatIdVerifiedAt,
            vatIdVerificationNote = recipient.vatIdVerificationNote,
        )
    }

    private fun Address.toAddressDto(): AddressDto = AddressDto(
        id = id,
        street = street,
        postalCode = postalCode.value,
        city = city,
        countryAlpha2 = country.alpha2,
    )
}
