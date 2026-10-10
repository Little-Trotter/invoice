package de.littletrotter.domain.masterdata

/**
 * Shared address ownership behavior for entities that manage billing and delivery addresses.
 */
interface AddressOwner {
    val addresses: MutableList<Address>

    fun ownsAddress(address: Address): Boolean
    fun createDefaultBillingAddress(): Address
    fun ownerLabel(): String

    val billingAddress: Address?
        get() = addresses
            .asSequence()
            .filter { it.addressType == Address.AddressType.BILLING }
            .sortedWith(compareByDescending<Address> { it.isDefault }.thenBy { it.id ?: Long.MAX_VALUE })
            .firstOrNull()

    val deliveryAddresses: List<Address>
        get() = addresses
            .asSequence()
            .filter { it.addressType == Address.AddressType.DELIVERY }
            .sortedWith(compareByDescending<Address> { it.isDefault }.thenBy { it.id ?: Long.MAX_VALUE })
            .toList()

    fun addAddress(address: Address) {
        require(ownsAddress(address)) { "Address ${ownerLabel()} mismatch" }
        when (address.addressType) {
            Address.AddressType.BILLING -> setBillingAddress(address)
            Address.AddressType.DELIVERY -> addDeliveryAddress(address)
        }
    }

    fun setBillingAddress(address: Address) {
        require(ownsAddress(address) && address.addressType == Address.AddressType.BILLING) { "Invalid billing address for ${ownerLabel()}" }
        addresses.filter { it.addressType == Address.AddressType.BILLING }.forEach { it.isDefault = false }
        address.isDefault = true
        addresses.removeIf { it.addressType == Address.AddressType.BILLING }
        addresses.add(address)
    }

    fun addDeliveryAddress(address: Address) {
        require(ownsAddress(address) && address.addressType == Address.AddressType.DELIVERY) { "Invalid delivery address for ${ownerLabel()}" }
        addresses.add(address)
    }
}
