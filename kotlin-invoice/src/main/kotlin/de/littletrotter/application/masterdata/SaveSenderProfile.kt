package de.littletrotter.application.masterdata

import de.littletrotter.domain.masterdata.editableDetails
import de.littletrotter.application.dto.SaveSenderProfileCommand
import de.littletrotter.application.masterdata.port.inbound.SaveSenderProfileUseCase
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.PartyIdentifier
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.value.Bic
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.spi.party.PartyKind
import de.littletrotter.spi.party.PartyPairState
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Implements [SaveSenderProfileUseCase]: loads (or creates) the profile, applies the submitted
 * form values, then saves it and its billing address through [SellerProfileService]. The
 * profile's address is written after the profile itself because it needs the profile's id.
 */
@ApplicationScoped
class SaveSenderProfile @Inject constructor(
    private val sellerProfileService: SellerProfileService,
    private val partyFormExtensionRegistry: PartyFormExtensionRegistry,
    private val sellerVatRegistrationsExtensionRegistry: SellerVatRegistrationsExtensionRegistry,
) : SaveSenderProfileUseCase {

    override fun saveSenderProfile(command: SaveSenderProfileCommand): Long {
        val target = command.id?.let { id ->
            sellerProfileService.findById(id) ?: throw InvalidParameterException("Das Anbieterprofil wurde inzwischen gelöscht.")
        } ?: SenderProfile()
        apply(target, command)
        val saved = sellerProfileService.save(
            target,
            command.proposedSellerNumber,
            command.invoiceNotesByLanguage,
            SellerDefaultSelections(paymentTermId = command.defaultPaymentTermId),
        )
        val id = checkNotNull(saved.id) { "Sender must have ID after save" }
        sellerProfileService.saveAddress(
            id,
            Address.AddressType.BILLING,
            command.street,
            command.postalCode,
            command.city,
            command.countryAlpha2,
        )
        return id
    }

    private fun apply(profile: SenderProfile, command: SaveSenderProfileCommand) {
        applyCompanyAndLegalData(profile, command)
        partyFormExtensionRegistry.enabledAspects(PartyKind.SELLER).forEach { (spec, extension) ->
            extension.write(profile, PartyKind.SELLER, spec, command.partyPairs[spec.id] ?: PartyPairState())
        }
        sellerVatRegistrationsExtensionRegistry.enabledExtension()?.write(profile, command.vatRegistrations)
    }

    // Address fields are not written here: the billing address is saved through
    // SellerProfileService.saveAddress once the profile has an id. The default payment term and
    // the invoice notes are resolved by SellerProfileService.save.
    private fun applyCompanyAndLegalData(profile: SenderProfile, command: SaveSenderProfileCommand) {
        profile.update(
            profile.editableDetails().copy(
                name = command.name.trim(),
                taxNumber = TaxNumber.ofNullable(command.taxNumber),
                vatId = VatId.ofNullable(command.vatId),
                iban = Iban.ofNullable(command.iban.trim().uppercase().ifBlank { null }),
                bic = Bic.ofNullable(command.bic),
                bankName = command.bankName.trim().ifBlank { null },
                invoicePrefix = command.invoicePrefix.trim().ifBlank { "RE-" },
                phone = command.phone.trim().ifBlank { null },
                email = Email.ofNullable(command.email.trim().ifBlank { null }),
                // Stored as entered; the renderer normalises for display (strips the scheme prefix
                // and a trailing slash), never this write path.
                website = command.website.trim().ifBlank { null },
                registeredSeat = command.registeredSeat.trim().ifBlank { null },
                managingDirector = command.managingDirector.trim().ifBlank { null },
                courtOfJurisdiction = command.courtOfJurisdiction.trim().ifBlank { null },
                registerCourt = command.registerCourt.trim().ifBlank { null },
                commercialRegisterNumber = command.commercialRegisterNumber.trim().ifBlank { null },
                // Value and scheme always travel together: both blank means "not set", exactly one
                // filled is a validation error raised by PartyIdentifier.of, never silently dropped.
                sellerIdentifier = toPartyIdentifier(command.sellerIdentifierValue, command.sellerIdentifierSchemeId),
                legalRegistrationSchemeId = command.legalRegistrationSchemeId.trim().ifBlank { null },
                legalForm = command.legalForm,
                craftsChamberRegistered = command.craftsChamberRegistered,
            ),
        )
        if (command.isDefault) profile.markAsDefault() else profile.revokeDefault()
    }

    private fun toPartyIdentifier(value: String, schemeId: String): PartyIdentifier? =
        if (value.isBlank() && schemeId.isBlank()) null else PartyIdentifier.of(value.trim(), schemeId.trim())
}
