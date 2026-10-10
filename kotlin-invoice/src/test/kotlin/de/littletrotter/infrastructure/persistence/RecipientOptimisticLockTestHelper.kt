package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.repository.RecipientRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import de.littletrotter.domain.value.CustomerNumber

/**
 * CDI helper bean for [RecipientOptimisticLockingIT] — same shape as
 * [de.littletrotter.domain.invoice.InvoiceOptimisticLockTestHelper]. Must be a top-level class:
 * non-static inner classes cannot be CDI beans (no zero-arg constructor visible to the container).
 */
@ApplicationScoped
class RecipientOptimisticLockTestHelper {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var recipientRepository: RecipientRepository

    data class Ids(val recipientId: Long, val billingAddressId: Long, val deliveryAddressId: Long)

    /** Persists a minimal [Recipient] with two child [Address] rows (billing + delivery) and
     *  returns their ids. */
    @Transactional
    fun createRecipientWithTwoAddresses(): Ids {
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("KD-OPTLOCK-${System.nanoTime()}")
            name = "Optimistic Lock Test GmbH"
        }
        em.persist(recipient)
        val billing = Address.forRecipient(recipient, Address.AddressType.BILLING, "Alte Straße 1", "10115", "Berlin", Country.DE, true)
        val delivery = Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Alte Straße 2", "10115", "Berlin", Country.DE, true)
        em.persist(billing)
        em.persist(delivery)
        em.flush()
        return Ids(recipient.id!!, billing.id!!, delivery.id!!)
    }

    /**
     * Loads [Recipient] by [recipientId], signals [loadedLatch], waits on [startSignal], then
     * changes the street of the [Address] identified by [addressId] and force-increments the
     * recipient — simulating one side of a concurrent recipient edit that only touches a child
     * address row (exactly the finding's shape: two sessions editing DIFFERENT address rows
     * of the same recipient).
     *
     * [Transactional.TxType.REQUIRES_NEW] ensures each call from a different thread starts a
     * completely independent JTA transaction. The transaction commits when this method returns,
     * triggering Hibernate's version check for the [PanacheRecipientRepository.forceVersionIncrement]
     * lock taken below.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun loadModifyAddressAndSave(
        recipientId: Long,
        addressId: Long,
        newStreet: String,
        loadedLatch: CountDownLatch,
        startSignal: CountDownLatch,
    ) {
        val recipient = em.find(Recipient::class.java, recipientId)
        // Signal that this transaction has loaded the entity (version is now fixed in this tx).
        loadedLatch.countDown()
        // Wait until both transactions have loaded before either commits.
        startSignal.await(10, TimeUnit.SECONDS)
        val address = em.find(Address::class.java, addressId)
        address.street = newStreet
        recipientRepository.forceVersionIncrement(recipient)
        // JTA transaction commits here; Hibernate's version-WHERE check runs on flush.
    }

    @Transactional
    fun currentVersion(recipientId: Long): Long? = em.find(Recipient::class.java, recipientId)?.version
}
