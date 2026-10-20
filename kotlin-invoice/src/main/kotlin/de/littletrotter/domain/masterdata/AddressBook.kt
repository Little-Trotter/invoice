package de.littletrotter.domain.masterdata

/**
 * The addresses of one [owner] (recipient or seller) and the rules for changing them. Handed out
 * by [AddressOwner.addressBook] for the owner's own rows, so no caller ever holds the mutable list.
 */
class AddressBook(private val owner: AddressOwner, private val rows: MutableList<Address>) {

    /** Makes [address] the only billing address, replacing any existing one. */
    fun designateBilling(address: Address) {
        require(owner.ownsAddress(address) && address.addressType == Address.AddressType.BILLING) {
            "Invalid billing address for ${owner.ownerLabel()}"
        }
        rows.filter { it.addressType == Address.AddressType.BILLING }.forEach { it.revokeDefault() }
        address.markAsDefault()
        rows.removeIf { it.addressType == Address.AddressType.BILLING }
        rows.add(address)
    }

    fun addDelivery(address: Address) {
        require(owner.ownsAddress(address) && address.addressType == Address.AddressType.DELIVERY) {
            "Invalid delivery address for ${owner.ownerLabel()}"
        }
        rows.add(address)
    }

    /**
     * Takes the default flag from every address of [type] and returns the address that was the
     * default. The caller flushes before [saveDefault] adds the next default row, so the database
     * never holds two defaults of one type at the same moment.
     */
    fun revokeDefaults(type: Address.AddressType): Address? {
        val previous = rows.firstOrNull { it.addressType == type && it.isDefault }
        rows.filter { it.addressType == type }.forEach { it.revokeDefault() }
        return previous
    }

    /**
     * Makes the address described by [input] the default of its type. The [previous] default is
     * kept as a non-default historical row when [isReferenced] (an invoice points at it) and a new
     * row is added; otherwise it is relocated in place.
     */
    fun saveDefault(input: AddressInput, previous: Address?, isReferenced: (Address) -> Boolean): Address {
        val reusable = previous?.takeUnless(isReferenced)
        val address = reusable?.apply { relocate(input.street, input.postalCode, input.city, input.country) }
            ?: newAddress(input).also { rows.add(it) }
        address.markAsDefault()
        address.validate()
        return address
    }

    private fun newAddress(input: AddressInput): Address = when (owner) {
        is Recipient -> Address.forRecipient(owner, input.type, input.street, input.postalCode, input.city, input.country, true)
        is SenderProfile -> Address.forSender(owner, input.type, input.street, input.postalCode, input.city, input.country, true)
        else -> error("Unsupported address owner ${owner.ownerLabel()}")
    }
}
