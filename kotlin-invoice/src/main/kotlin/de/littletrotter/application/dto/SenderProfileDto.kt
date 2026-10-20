package de.littletrotter.application.dto

import de.littletrotter.domain.masterdata.LegalForm
import de.littletrotter.spi.party.PartyPairState
import de.littletrotter.spi.party.SellerVatRegistrationRow

/**
 * One row of the sender-profile grid: the lean columns the list shows plus [version], which the
 * editor compares against its own copy to detect a concurrent change.
 */
data class SenderProfileRowDto(
    val id: Long,
    val version: Long?,
    val name: String,
    val isDefault: Boolean,
    val invoicePrefix: String,
    val sellerNumber: String?,
    val shortHash: String?,
    val billingCity: String?,
    val billingCountryAlpha2: String?,
)

/**
 * The completely loaded sender profile the edit dialog is built from. Values are plain strings
 * (empty-able wrappers unwrapped); [partyPairs] is keyed by the extension pair id and
 * [vatRegistrations] carries the additional VAT registrations, both as the extensions read them.
 */
data class SenderProfileDetailDto(
    val id: Long,
    val version: Long?,
    val name: String,
    val billingAddress: AddressDto?,
    val taxNumber: String?,
    val vatId: String?,
    val iban: String?,
    val bic: String?,
    val bankName: String?,
    val defaultPaymentTerm: PaymentTermDto?,
    val invoicePrefix: String,
    val invoiceNotesByLanguageCode: Map<String, String>,
    val phone: String?,
    val email: String?,
    val website: String?,
    val registeredSeat: String?,
    val managingDirector: String?,
    val courtOfJurisdiction: String?,
    val registerCourt: String?,
    val commercialRegisterNumber: String?,
    val sellerIdentifierValue: String?,
    val sellerIdentifierSchemeId: String?,
    val legalRegistrationSchemeId: String?,
    val isDefault: Boolean,
    val legalForm: LegalForm?,
    val craftsChamberRegistered: Boolean,
    val sellerNumber: String?,
    val shortHash: String?,
    val partyPairs: Map<String, PartyPairState>,
    val vatRegistrations: List<SellerVatRegistrationRow>,
)
