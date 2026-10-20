package de.littletrotter.application.dto

import de.littletrotter.domain.masterdata.LegalForm
import de.littletrotter.spi.party.PartyPairState
import de.littletrotter.spi.party.SellerVatRegistrationRow

/**
 * Everything the sender-profile dialog submits, as the user typed it: the use case trims,
 * blank-normalises and wraps the strings into the profile's value types. [id] `null` creates a
 * profile.
 *
 * Identifier value and scheme travel together: both blank means "not set", exactly one filled is
 * rejected when the identifier is built. [invoiceNotesByLanguage] is keyed by language code; a
 * code absent from the map is left untouched, a blank value removes that language's text.
 */
data class SaveSenderProfileCommand(
    val id: Long?,
    val name: String,
    val street: String,
    val postalCode: String,
    val city: String,
    val countryAlpha2: String,
    val taxNumber: String,
    val vatId: String,
    val iban: String,
    val bic: String,
    val bankName: String,
    val defaultPaymentTermId: Long?,
    val invoicePrefix: String,
    val invoiceNotesByLanguage: Map<String, String?>,
    val phone: String,
    val email: String,
    val website: String,
    val registeredSeat: String,
    val managingDirector: String,
    val courtOfJurisdiction: String,
    val registerCourt: String,
    val commercialRegisterNumber: String,
    val sellerIdentifierValue: String,
    val sellerIdentifierSchemeId: String,
    val legalRegistrationSchemeId: String,
    val isDefault: Boolean,
    val legalForm: LegalForm?,
    val craftsChamberRegistered: Boolean,
    val partyPairs: Map<String, PartyPairState>,
    val vatRegistrations: List<SellerVatRegistrationRow>,
    val proposedSellerNumber: String?,
)
