package de.littletrotter.domain.repository

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Address.AddressType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile

interface AddressRepository {
    fun findByRecipientAndType(recipient: Recipient, type: AddressType, pageIndex: Int = 0, pageSize: Int = 60): List<Address>
    fun findDefaultByRecipient(recipient: Recipient): Address?
    fun findDefaultByRecipientAndType(recipient: Recipient, type: AddressType): Address?
    fun findBySenderAndType(sender: SenderProfile, type: AddressType, pageIndex: Int = 0, pageSize: Int = 60): List<Address>
    fun findDefaultBySender(sender: SenderProfile): Address?
    fun findDefaultBySenderAndType(sender: SenderProfile, type: AddressType): Address?
    /** Bulk-clears the is_default flag for all billing/delivery addresses of a recipient (runs as SQL UPDATE). */
    fun clearDefaultByRecipientAndType(recipient: Recipient, type: AddressType)
    /** Bulk-clears the is_default flag for all billing/delivery addresses of a sender (runs as SQL UPDATE). */
    fun clearDefaultBySenderAndType(sender: SenderProfile, type: AddressType)
    fun save(address: Address): Address
    fun delete(id: Long)
}
