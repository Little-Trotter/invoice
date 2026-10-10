package de.littletrotter.domain.masterdata

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.shared.AuditableEntity
import de.littletrotter.domain.value.PostalCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.Size

@Entity
@Table(name = "address")
class Address private constructor() : AuditableEntity() {

    enum class AddressType { BILLING, DELIVERY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    var recipient: Recipient? = null

    @ManyToOne(fetch = FetchType.LAZY)
    var senderProfile: SenderProfile? = null

    @jakarta.persistence.Convert(converter = de.littletrotter.domain.masterdata.converters.AddressTypeConverter::class)
    @Column(nullable = false)
    var addressType: AddressType = AddressType.BILLING

    @field:Size(max = 255)
    var street: String = ""

    var postalCode: PostalCode = PostalCode("")

    @field:Size(max = 255)
    var city: String = ""
    @jakarta.persistence.Convert(converter = CountryAttributeConverter::class)
    @Column(name = "country")
    var country: Country = Country.DE
    var isDefault: Boolean = false

    @Version
    var version: Long? = 0

    /**
     * S6619 — checks each own-typed nullable association directly (no `listOf(recipient,
     * senderProfile)` erasing both to a shared `Any?` element type first): [recipient] and
     * [senderProfile] are declared nullable for a real reason (JPA needs a mutable, optional FK on
     * each side of this either/or ownership), so "exactly one is set" is an XOR over their own
     * nullability, not a generic-collection null count Sonar's inference could second-guess.
     */
    fun validateOwner() {
        val hasRecipient = recipient != null
        val hasSenderProfile = senderProfile != null
        check(hasRecipient != hasSenderProfile) { "Address must belong to exactly one owner: recipient or senderProfile" }
    }

    fun validate() {
        validateOwner()
        if (street.isBlank()) throw InvalidParameterException("Straße darf nicht leer sein.")
        if (postalCode.value.isBlank()) throw InvalidParameterException("Postleitzahl darf nicht leer sein.")
        if (city.isBlank()) throw InvalidParameterException("Stadt darf nicht leer sein.")
    }

    /** [type]/[street]/[postalCode]/[city]/[country]/[isDefault] bundled so the private
     *  [Companion.unowned] draft builder takes one parameter for them instead of six
     *  (LongParameterList) — [forRecipient]/[forSender] keep their own public parameter
     *  list unchanged (118+ existing call sites across production and tests). */
    private data class UnownedFields(
        val type: AddressType,
        val street: String,
        val postalCode: String,
        val city: String,
        val country: Country,
        val isDefault: Boolean,
    )

    companion object {
        /** Ownerless draft: [forRecipient]/[forSender] assign exactly one owner and only then run
         *  [validateOwner], so an [Address] never leaves this companion without one. */
        private fun unowned(fields: UnownedFields): Address = Address().apply {
            this.addressType = fields.type
            this.street = fields.street
            this.postalCode = PostalCode.forCountry(fields.country.alpha2, fields.postalCode)
            this.city = fields.city
            this.country = fields.country
            this.isDefault = fields.isDefault
        }

        fun forRecipient(recipient: Recipient, type: AddressType, street: String, postalCode: String, city: String, country: Country = Country.DE, isDefault: Boolean = false): Address {
            return unowned(UnownedFields(type, street, postalCode, city, country, isDefault)).apply {
                this.recipient = recipient
                validateOwner()
            }
        }

        fun forSender(sender: SenderProfile, type: AddressType, street: String, postalCode: String, city: String, country: Country = Country.DE, isDefault: Boolean = false): Address {
            return unowned(UnownedFields(type, street, postalCode, city, country, isDefault)).apply {
                this.senderProfile = sender
                validateOwner()
            }
        }
    }

    fun isOwnedByRecipient(recipient: Recipient) = this.recipient?.id == recipient.id
    fun isOwnedBySender(sender: SenderProfile) = this.senderProfile?.id == sender.id
}
