package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Address.AddressType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.AddressRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class PanacheAddressRepository : BasePanacheRepository(), AddressRepository {

    @Transactional
    override fun findByRecipientAndType(recipient: Recipient, type: AddressType, pageIndex: Int, pageSize: Int): List<Address> =
        em.createQuery(
            "SELECT a FROM Address a WHERE a.recipient = :r AND a.addressType = :t",
            Address::class.java
        ).setParameter("r", recipient).setParameter("t", type)
            .setFirstResult(pageIndex * pageSize)
            .setMaxResults(pageSize)
            .resultList

    @Transactional
    override fun findDefaultByRecipient(recipient: Recipient): Address? =
        em.createQuery(
            "SELECT a FROM Address a WHERE a.recipient = :r AND a.isDefault = true",
            Address::class.java
        ).setParameter("r", recipient).setMaxResults(1).resultList.firstOrNull()

    @Transactional
    override fun findDefaultByRecipientAndType(recipient: Recipient, type: AddressType): Address? =
        em.createQuery(
            "SELECT a FROM Address a WHERE a.recipient = :r AND a.addressType = :t AND a.isDefault = true",
            Address::class.java
        ).setParameter("r", recipient).setParameter("t", type).setMaxResults(1).resultList.firstOrNull()

    @Transactional
    override fun findBySenderAndType(sender: SenderProfile, type: AddressType, pageIndex: Int, pageSize: Int): List<Address> =
        em.createQuery("SELECT a FROM Address a WHERE a.senderProfile = :s AND a.addressType = :t", Address::class.java)
            .setParameter("s", sender).setParameter("t", type)
            .setFirstResult(pageIndex * pageSize)
            .setMaxResults(pageSize)
            .resultList

    @Transactional
    override fun findDefaultBySender(sender: SenderProfile): Address? =
        em.createQuery("SELECT a FROM Address a WHERE a.senderProfile = :s AND a.isDefault = true", Address::class.java)
            .setParameter("s", sender).setMaxResults(1).resultList.firstOrNull()

    @Transactional
    override fun findDefaultBySenderAndType(sender: SenderProfile, type: AddressType): Address? =
        em.createQuery("SELECT a FROM Address a WHERE a.senderProfile = :s AND a.addressType = :t AND a.isDefault = true", Address::class.java)
            .setParameter("s", sender).setParameter("t", type).setMaxResults(1).resultList.firstOrNull()

    @Transactional
    override fun clearDefaultByRecipientAndType(recipient: Recipient, type: AddressType) {
        em.createQuery("UPDATE Address a SET a.isDefault = false WHERE a.recipient = :r AND a.addressType = :t")
            .setParameter("r", recipient)
            .setParameter("t", type)
            .executeUpdate()
    }

    @Transactional
    override fun clearDefaultBySenderAndType(sender: SenderProfile, type: AddressType) {
        em.createQuery("UPDATE Address a SET a.isDefault = false WHERE a.senderProfile = :s AND a.addressType = :t")
            .setParameter("s", sender)
            .setParameter("t", type)
            .executeUpdate()
    }

    override fun save(address: Address): Address = saveEntity(address) { address.id }

    override fun delete(id: Long) { deleteById(id, Address::class.java) }
}
