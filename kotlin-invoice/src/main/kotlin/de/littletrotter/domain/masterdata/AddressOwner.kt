package de.littletrotter.domain.masterdata

import de.littletrotter.shared.country.Country

/** Street, postal code, city and country a default address is saved with. */
data class AddressInput(
    val type: Address.AddressType,
    val street: String,
    val postalCode: String,
    val city: String,
    val country: Country,
)

/**
 * Shared address ownership behavior for entities that manage billing and delivery addresses.
 * The rows are only readable from outside; every change goes through the owner's [addressBook]
 * so its rules (one billing address, a default that cannot be dropped, at least one address) hold.
 */
interface AddressOwner {
    val addressList: List<Address>

    /** The owner's addresses together with the rules for changing them. */
    val addressBook: AddressBook

    fun ownsAddress(address: Address): Boolean
    fun createDefaultBillingAddress(): Address
    fun ownerLabel(): String

    val billingAddress: Address?
        get() = addressList
            .asSequence()
            .filter { it.addressType == Address.AddressType.BILLING }
            .sortedWith(compareByDescending<Address> { it.isDefault }.thenBy { it.id ?: Long.MAX_VALUE })
            .firstOrNull()

    val deliveryAddresses: List<Address>
        get() = addressList
            .asSequence()
            .filter { it.addressType == Address.AddressType.DELIVERY }
            .sortedWith(compareByDescending<Address> { it.isDefault }.thenBy { it.id ?: Long.MAX_VALUE })
            .toList()

    fun addAddress(address: Address) {
        require(ownsAddress(address)) { "Address ${ownerLabel()} mismatch" }
        when (address.addressType) {
            Address.AddressType.BILLING -> designateBillingAddress(address)
            Address.AddressType.DELIVERY -> addDeliveryAddress(address)
        }
    }

    /** Makes [address] the only billing address, replacing any existing one. */
    fun designateBillingAddress(address: Address) = addressBook.designateBilling(address)

    fun addDeliveryAddress(address: Address) = addressBook.addDelivery(address)
}
