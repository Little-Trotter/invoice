package de.littletrotter.ui.view.masterdata

import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.dto.SenderProfileDetailDto
import de.littletrotter.application.dto.SenderProfileRowDto
import de.littletrotter.domain.masterdata.LegalForm
import de.littletrotter.spi.party.PartyPairState
import de.littletrotter.spi.party.SellerVatRegistrationRow

/** A grid row with plain defaults; override only what a test is about. */
internal fun senderRow(
    id: Long = 1L,
    name: String = "Anbieter",
    version: Long? = 0L,
    isDefault: Boolean = false,
    invoicePrefix: String = "RE-",
    sellerNumber: String? = null,
    shortHash: String? = null,
    billingCity: String? = null,
    billingCountryAlpha2: String? = null,
): SenderProfileRowDto = SenderProfileRowDto(
    id, version, name, isDefault, invoicePrefix, sellerNumber, shortHash, billingCity, billingCountryAlpha2,
)

/** A completely loaded profile with every nullable field absent; override only what a test is about. */
internal fun senderDetail(
    id: Long = 1L,
    name: String = "Anbieter",
    version: Long? = 0L,
    billingAddress: AddressDto? = null,
    taxNumber: String? = null,
    vatId: String? = null,
    iban: String? = null,
    bic: String? = null,
    bankName: String? = null,
    defaultPaymentTerm: PaymentTermDto? = null,
    invoicePrefix: String = "RE-",
    invoiceNotesByLanguageCode: Map<String, String> = emptyMap(),
    phone: String? = null,
    email: String? = null,
    website: String? = null,
    registeredSeat: String? = null,
    managingDirector: String? = null,
    courtOfJurisdiction: String? = null,
    registerCourt: String? = null,
    commercialRegisterNumber: String? = null,
    sellerIdentifierValue: String? = null,
    sellerIdentifierSchemeId: String? = null,
    legalRegistrationSchemeId: String? = null,
    isDefault: Boolean = false,
    legalForm: LegalForm? = null,
    craftsChamberRegistered: Boolean = false,
    sellerNumber: String? = null,
    shortHash: String? = null,
    partyPairs: Map<String, PartyPairState> = emptyMap(),
    vatRegistrations: List<SellerVatRegistrationRow> = emptyList(),
): SenderProfileDetailDto = SenderProfileDetailDto(
    id, version, name, billingAddress, taxNumber, vatId, iban, bic, bankName, defaultPaymentTerm, invoicePrefix,
    invoiceNotesByLanguageCode, phone, email, website, registeredSeat, managingDirector, courtOfJurisdiction,
    registerCourt, commercialRegisterNumber, sellerIdentifierValue, sellerIdentifierSchemeId,
    legalRegistrationSchemeId, isDefault, legalForm, craftsChamberRegistered, sellerNumber, shortHash,
    partyPairs, vatRegistrations,
)
