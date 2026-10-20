package de.littletrotter.fixture

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.ElectronicAddress
import de.littletrotter.domain.masterdata.LegalRegistrationIdentifier
import de.littletrotter.domain.masterdata.PartyIdentifier
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.RecipientDetails
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.VatId
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.tax.VatIdVerificationStatus
import java.time.OffsetDateTime

/**
 * Property-style access to the editable state of a [Recipient]; each write applies at once through its intent
 * method.
 *
 * Identity, lock version and derived handles have no domain write path; they are hydrated the way a loaded row
 * carries them.
 */
class RecipientEditor internal constructor(val entity: Recipient) {
    var name: String
        get() = entity.name
        set(value) { entity.update(details().copy(name = value)) }
    var customerNumber: CustomerNumber?
        get() = entity.customerNumber
        set(value) { entity.update(details().copy(customerNumber = value)) }
    var vatId: VatId?
        get() = entity.vatId
        set(value) { entity.update(details().copy(vatId = value)) }
    var vatIdVerificationNote: String?
        get() = entity.vatIdVerificationNote
        set(value) { entity.update(details().copy(vatIdVerificationNote = value)) }
    var iban: Iban?
        get() = entity.iban
        set(value) { entity.update(details().copy(iban = value)) }
    var routingId: String?
        get() = entity.routingId
        set(value) { entity.update(details().copy(routingId = value)) }
    var email: Email?
        get() = entity.email
        set(value) { entity.update(details().copy(email = value)) }
    var phone: String?
        get() = entity.phone
        set(value) { entity.update(details().copy(phone = value)) }
    var contactPerson: String?
        get() = entity.contactPerson
        set(value) { entity.update(details().copy(contactPerson = value)) }
    var customerType: CustomerType?
        get() = entity.customerType
        set(value) { entity.update(details().copy(customerType = value)) }
    var invoiceFormat: InvoiceFormat
        get() = entity.invoiceFormat
        set(value) { entity.update(details().copy(invoiceFormat = value)) }
    var documentLanguage: String
        get() = entity.documentLanguage
        set(value) { entity.update(details().copy(documentLanguage = value)) }
    var reverseChargePreference: ReverseChargePreference
        get() = entity.reverseChargePreference
        set(value) { entity.update(details().copy(reverseChargePreference = value)) }
    var buyerIdentifier: PartyIdentifier?
        get() = entity.buyerIdentifier
        set(value) { entity.update(details().copy(buyerIdentifier = value)) }
    var legalRegistrationIdentifier: LegalRegistrationIdentifier?
        get() = entity.legalRegistrationIdentifier
        set(value) { entity.update(details().copy(legalRegistrationIdentifier = value)) }
    var b2bProfile: B2bProfile?
        get() = entity.b2bProfile
        set(value) { entity.update(details().copy(b2bProfile = value)) }
    var addresses: List<Address>
        get() = entity.addressList
        set(value) { PersistenceEmulation.hydrate(entity, "addresses", value.toMutableList()) }
    var vatIdVerificationStatus: VatIdVerificationStatus?
        get() = entity.vatIdVerificationStatus
        set(value) { if (value != null) entity.recordVatIdVerification(value, entity.vatIdVerifiedAt) else PersistenceEmulation.hydrate(entity, "vatIdVerificationStatus", null) }
    var vatIdVerifiedAt: OffsetDateTime?
        get() = entity.vatIdVerifiedAt
        set(value) {
            val status = entity.vatIdVerificationStatus
            if (status != null) entity.recordVatIdVerification(status, value) else PersistenceEmulation.hydrate(entity, "vatIdVerifiedAt", value)
        }
    var customerNumberPrefix: String?
        get() = entity.customerNumberPrefix
        set(value) { entity.assignCustomerNumber(value, entity.customerNumberSequence, entity.customerNumber) }
    var customerNumberSequence: Long?
        get() = entity.customerNumberSequence
        set(value) { entity.assignCustomerNumber(entity.customerNumberPrefix, value, entity.customerNumber) }
    var electronicAddress: ElectronicAddress?
        get() = entity.electronicAddress
        set(value) { entity.changeElectronicAddress(value) }
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

    private fun details() = RecipientDetails(
        name = entity.name,
        customerNumber = entity.customerNumber,
        vatId = entity.vatId,
        vatIdVerificationNote = entity.vatIdVerificationNote,
        iban = entity.iban,
        routingId = entity.routingId,
        email = entity.email,
        phone = entity.phone,
        contactPerson = entity.contactPerson,
        customerType = entity.customerType,
        invoiceFormat = entity.invoiceFormat,
        documentLanguage = entity.documentLanguage,
        reverseChargePreference = entity.reverseChargePreference,
        buyerIdentifier = entity.buyerIdentifier,
        legalRegistrationIdentifier = entity.legalRegistrationIdentifier,
        b2bProfile = entity.b2bProfile,
    )

    fun update(details: RecipientDetails) = entity.update(details)

    fun addAddress(address: Address) = entity.addAddress(address)

    fun designateBillingAddress(address: Address) = entity.designateBillingAddress(address)

    fun addDeliveryAddress(address: Address) = entity.addDeliveryAddress(address)
}

/** Edits [Recipient] in place through its domain operations. */
fun Recipient.edit(block: RecipientEditor.() -> Unit): Recipient = also { RecipientEditor(it).block() }

/**
 * A recipient (customer) built through the domain operations; every default is the entity default.
 */
fun aRecipient(block: RecipientEditor.() -> Unit = {}): Recipient = Recipient().edit(block)
