package de.littletrotter.fixture

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.ElectronicAddress
import de.littletrotter.domain.masterdata.LegalForm
import de.littletrotter.domain.masterdata.PartyIdentifier
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.masterdata.SenderProfileDetails
import de.littletrotter.domain.masterdata.editableDetails
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.LocalizedTextGroup
import de.littletrotter.domain.value.Bic
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import java.time.LocalDate

/**
 * Property-style access to the editable state of a [SenderProfile]; each write applies at once through its
 * intent method.
 *
 * Identity, lock version, derived handles and the stored note groups have no domain write path in this form;
 * they are hydrated the way a loaded row carries them.
 */
class SenderProfileEditor internal constructor(val entity: SenderProfile) {
    var name: String
        get() = entity.name
        set(value) { entity.update(entity.editableDetails().copy(name = value)) }
    var taxNumber: TaxNumber?
        get() = entity.taxNumber
        set(value) { entity.update(entity.editableDetails().copy(taxNumber = value)) }
    var vatId: VatId?
        get() = entity.vatId
        set(value) { entity.update(entity.editableDetails().copy(vatId = value)) }
    var iban: Iban?
        get() = entity.iban
        set(value) { entity.update(entity.editableDetails().copy(iban = value)) }
    var bic: Bic?
        get() = entity.bic
        set(value) { entity.update(entity.editableDetails().copy(bic = value)) }
    var bankName: String?
        get() = entity.bankName
        set(value) { entity.update(entity.editableDetails().copy(bankName = value)) }
    var invoicePrefix: String
        get() = entity.invoicePrefix
        set(value) { entity.update(entity.editableDetails().copy(invoicePrefix = value)) }
    var phone: String?
        get() = entity.phone
        set(value) { entity.update(entity.editableDetails().copy(phone = value)) }
    var email: Email?
        get() = entity.email
        set(value) { entity.update(entity.editableDetails().copy(email = value)) }
    var managingDirector: String?
        get() = entity.managingDirector
        set(value) { entity.update(entity.editableDetails().copy(managingDirector = value)) }
    var courtOfJurisdiction: String?
        get() = entity.courtOfJurisdiction
        set(value) { entity.update(entity.editableDetails().copy(courtOfJurisdiction = value)) }
    var registerCourt: String?
        get() = entity.registerCourt
        set(value) { entity.update(entity.editableDetails().copy(registerCourt = value)) }
    var website: String?
        get() = entity.website
        set(value) { entity.update(entity.editableDetails().copy(website = value)) }
    var registeredSeat: String?
        get() = entity.registeredSeat
        set(value) { entity.update(entity.editableDetails().copy(registeredSeat = value)) }
    var commercialRegisterNumber: String?
        get() = entity.commercialRegisterNumber
        set(value) { entity.update(entity.editableDetails().copy(commercialRegisterNumber = value)) }
    var sellerIdentifier: PartyIdentifier?
        get() = entity.sellerIdentifier
        set(value) { entity.update(entity.editableDetails().copy(sellerIdentifier = value)) }
    var legalRegistrationSchemeId: String?
        get() = entity.legalRegistrationSchemeId
        set(value) { entity.update(entity.editableDetails().copy(legalRegistrationSchemeId = value)) }
    var electronicAddress: ElectronicAddress?
        get() = entity.electronicAddress
        set(value) { entity.update(entity.editableDetails().copy(electronicAddress = value)) }
    var legalForm: LegalForm?
        get() = entity.legalForm
        set(value) { entity.update(entity.editableDetails().copy(legalForm = value)) }
    var craftsChamberRegistered: Boolean
        get() = entity.craftsChamberRegistered
        set(value) { entity.update(entity.editableDetails().copy(craftsChamberRegistered = value)) }
    var isDefault: Boolean
        get() = entity.isDefault
        set(value) { if (value) entity.markAsDefault() else entity.revokeDefault() }
    var addresses: List<Address>
        get() = entity.addressList
        set(value) { PersistenceEmulation.hydrate(entity, "addresses", value.toMutableList()) }
    var consumerNoteEnabled: Boolean
        get() = entity.consumerNoteEnabled
        set(value) { entity.enableConsumerNote(value) }
    var defaultPaymentTerm: PaymentTerm?
        get() = entity.defaultPaymentTerm
        set(value) { entity.changeDefaultPaymentTerm(value) }
    var sellerNumber: String?
        get() = entity.sellerNumber
        set(value) { entity.assignSellerNumber(entity.sellerNumberPrefix, entity.sellerNumberSequence, value) }
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assignId(entity, value) }
    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assignVersion(entity, value) }
    var globalId: String?
        get() = entity.globalId
        set(value) { PersistenceEmulation.hydrate(entity, "globalId", value) }
    var shortHash: String?
        get() = entity.shortHash
        set(value) { PersistenceEmulation.hydrate(entity, "shortHash", value) }
    var invoiceNote: LocalizedTextGroup?
        get() = entity.invoiceNote
        set(value) { PersistenceEmulation.hydrate(entity, "invoiceNote", value) }
    fun update(details: SenderProfileDetails) = entity.update(details)

    fun changeInvoiceNote(language: Language, value: String?) = entity.changeInvoiceNote(language, value)

    fun updateVatRegistrations(commands: List<SellerVatRegistrationCommand>, today: LocalDate) =
        entity.updateVatRegistrations(commands, today)

    fun addAddress(address: Address) = entity.addAddress(address)

    fun designateBillingAddress(address: Address) = entity.designateBillingAddress(address)

    fun addDeliveryAddress(address: Address) = entity.addDeliveryAddress(address)
}

/** Edits [SenderProfile] in place through its domain operations. */
fun SenderProfile.edit(block: SenderProfileEditor.() -> Unit): SenderProfile = also { SenderProfileEditor(it).block() }

/**
 * A seller profile built through the domain operations; every default is the entity default.
 */
fun aSenderProfile(block: SenderProfileEditor.() -> Unit = {}): SenderProfile = SenderProfile().edit(block)
