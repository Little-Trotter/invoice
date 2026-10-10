package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.AddressRepository
import de.littletrotter.domain.value.Email
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.domain.value.CustomerNumber

/**
 * real-Hibernate coverage for [PanacheAddressRepository]. No prior IT existed for this
 * class; `delete` in particular was fully uncovered. Own `COV32-ADR-` token keeps cleanup and
 * assertions scoped to this test's own fixtures in the shared dev-services database.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheAddressRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: AddressRepository

    @AfterEach
    @Transactional
    fun removeOwnRows() {
        em.createNativeQuery("DELETE FROM address WHERE recipient_id IN (SELECT id FROM recipient WHERE customer_number LIKE 'COV32-ADR-%')").executeUpdate()
        em.createNativeQuery("DELETE FROM address WHERE sender_profile_id IN (SELECT id FROM sender_profile WHERE invoice_prefix LIKE 'COV32-ADR-%')").executeUpdate()
        em.createNativeQuery("DELETE FROM recipient WHERE customer_number LIKE 'COV32-ADR-%'").executeUpdate()
        em.createNativeQuery("DELETE FROM sender_profile WHERE invoice_prefix LIKE 'COV32-ADR-%'").executeUpdate()
    }

    private fun recipient(number: String) = Recipient().apply {
        customerNumber = CustomerNumber.ofNullable(number)
        name = "COV32-ADR Recipient $number"
        vatId = VatId("DE${number.hashCode().mod(900000000) + 100000000}")
        email = Email.ofNullable("$number@cov32.example")
    }.also { em.persist(it) }

    private fun sender(prefix: String) = SenderProfile().apply {
        name = "COV32-ADR Sender $prefix"
        invoicePrefix = prefix
        taxNumber = TaxNumber("11/616/35840")
        vatId = VatId("DE123456789")
        email = Email.ofNullable("$prefix@cov32.example")
    }.also { em.persist(it) }

    @Test
    @Transactional
    fun `happy path - save persists an address, it is found by recipient and by sender, and delete removes it`() {
        val recipient = recipient("COV32-ADR-0001")
        val sender = sender("COV32-ADR-0001")

        val recipientAddress = Address.forRecipient(recipient, Address.AddressType.BILLING, "Musterstr. 1", "12345", "Musterstadt", Country.DE, true)
        val savedRecipientAddress = repository.save(recipientAddress)
        val senderAddress = Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "54321", "Anbieterstadt", Country.DE, true)
        val savedSenderAddress = repository.save(senderAddress)
        em.flush()

        assertEquals(savedRecipientAddress.id, repository.findByRecipientAndType(recipient, Address.AddressType.BILLING).firstOrNull()?.id)
        assertEquals(savedSenderAddress.id, repository.findBySenderAndType(sender, Address.AddressType.BILLING).firstOrNull()?.id)

        repository.delete(savedRecipientAddress.id!!)
        repository.delete(savedSenderAddress.id!!)
        em.flush()
        assertTrue(repository.findByRecipientAndType(recipient, Address.AddressType.BILLING).isEmpty())
        assertTrue(repository.findBySenderAndType(sender, Address.AddressType.BILLING).isEmpty())
    }

    @Test
    @Transactional
    fun `boundary - delete of an unknown id is a no-op`() {
        repository.delete(-987654321L)
    }

    @Test
    @Transactional
    fun `boundary - findDefaultByRecipient and findDefaultByRecipientAndType only return the address flagged as default`() {
        val recipient = recipient("COV32-ADR-0002")
        val default = repository.save(Address.forRecipient(recipient, Address.AddressType.BILLING, "Default Str. 1", "10115", "Berlin", Country.DE, true))
        repository.save(Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Other Str. 2", "10115", "Berlin", Country.DE, false))
        em.flush()

        assertEquals(default.id, repository.findDefaultByRecipient(recipient)?.id)
        assertEquals(default.id, repository.findDefaultByRecipientAndType(recipient, Address.AddressType.BILLING)?.id)
        assertNull(repository.findDefaultByRecipientAndType(recipient, Address.AddressType.DELIVERY))
    }

    @Test
    @Transactional
    fun `boundary - findDefaultBySender and findDefaultBySenderAndType only return the address flagged as default`() {
        val sender = sender("COV32-ADR-0003")
        val default = repository.save(Address.forSender(sender, Address.AddressType.BILLING, "Default Str. 3", "10115", "Berlin", Country.DE, true))
        repository.save(Address.forSender(sender, Address.AddressType.DELIVERY, "Other Str. 4", "10115", "Berlin", Country.DE, false))
        em.flush()

        assertEquals(default.id, repository.findDefaultBySender(sender)?.id)
        assertEquals(default.id, repository.findDefaultBySenderAndType(sender, Address.AddressType.BILLING)?.id)
        assertNull(repository.findDefaultBySenderAndType(sender, Address.AddressType.DELIVERY))
    }

    @Test
    @Transactional
    fun `boundary - clearDefaultByRecipientAndType and clearDefaultBySenderAndType unset isDefault on their scope only`() {
        val recipient = recipient("COV32-ADR-0004")
        val sender = sender("COV32-ADR-0004")
        val recipientDefault = repository.save(Address.forRecipient(recipient, Address.AddressType.BILLING, "Str. 5", "10115", "Berlin", Country.DE, true))
        val senderDefault = repository.save(Address.forSender(sender, Address.AddressType.BILLING, "Str. 6", "10115", "Berlin", Country.DE, true))
        em.flush()

        repository.clearDefaultByRecipientAndType(recipient, Address.AddressType.BILLING)
        repository.clearDefaultBySenderAndType(sender, Address.AddressType.BILLING)
        em.flush()
        em.clear()

        assertFalse(em.find(Address::class.java, recipientDefault.id)!!.isDefault)
        assertFalse(em.find(Address::class.java, senderDefault.id)!!.isDefault)
    }
}
