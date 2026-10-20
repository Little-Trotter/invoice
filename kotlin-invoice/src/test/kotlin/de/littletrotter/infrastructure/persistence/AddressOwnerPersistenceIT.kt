package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.AddressInput
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Addresses are written through their owners (recipient, seller) and the owner's repository;
 * there is no address repository. Own `COV32-ADR-` token keeps cleanup scoped to this test's
 * fixtures in the shared dev-services database.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class AddressOwnerPersistenceIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var recipients: RecipientRepository

    @Inject
    lateinit var senders: SenderProfileRepository

    @AfterEach
    @Transactional
    fun removeOwnRows() {
        em.createNativeQuery("DELETE FROM address WHERE recipient_id IN (SELECT id FROM recipient WHERE customer_number LIKE 'COV32-ADR-%')").executeUpdate()
        em.createNativeQuery("DELETE FROM address WHERE sender_profile_id IN (SELECT id FROM sender_profile WHERE invoice_prefix LIKE 'COV32-ADR-%')").executeUpdate()
        em.createNativeQuery("DELETE FROM recipient WHERE customer_number LIKE 'COV32-ADR-%'").executeUpdate()
        em.createNativeQuery("DELETE FROM sender_profile WHERE invoice_prefix LIKE 'COV32-ADR-%'").executeUpdate()
    }

    private fun recipient(number: String) = aRecipient {
        customerNumber = CustomerNumber.ofNullable(number)
        name = "COV32-ADR Recipient $number"
        vatId = VatId("DE${number.hashCode().mod(900000000) + 100000000}")
        email = Email.ofNullable("$number@cov32.example")
    }.let { recipients.save(it) }

    private fun sender(prefix: String) = aSenderProfile {
        name = "COV32-ADR Sender $prefix"
        invoicePrefix = prefix
        taxNumber = TaxNumber("11/616/35840")
        vatId = VatId("DE123456789")
        email = Email.ofNullable("$prefix@cov32.example")
    }.let { senders.save(it) }

    private fun input(type: Address.AddressType, street: String) =
        AddressInput(type, street, "10115", "Berlin", Country.DE)

    @Test
    @Transactional
    fun `happy path - owners persist their addresses and a reload finds them`() {
        val recipient = recipient("COV32-ADR-0001")
        val sender = sender("COV32-ADR-0001")
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Musterstr. 1", "12345", "Musterstadt", Country.DE, true))
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "54321", "Anbieterstadt", Country.DE, true))
        recipients.save(recipient)
        senders.save(sender)
        em.flush()
        em.clear()

        assertEquals("Musterstr. 1", recipients.findById(recipient.id!!)!!.billingAddress?.street)
        assertEquals("Anbieterweg 1", senders.findById(sender.id!!)!!.billingAddress?.street)
    }

    @Test
    @Transactional
    fun `boundary - revoking the default of one type leaves other owners and types alone`() {
        val recipient = recipient("COV32-ADR-0003")
        val sender = sender("COV32-ADR-0003")
        val recipientDefault = recipient.addressBook.saveDefault(input(Address.AddressType.BILLING, "Str. 5"), null) { false }
        val senderDefault = sender.addressBook.saveDefault(input(Address.AddressType.BILLING, "Str. 6"), null) { false }
        recipients.save(recipient)
        senders.save(sender)
        em.flush()

        assertEquals(recipientDefault, recipient.addressBook.revokeDefaults(Address.AddressType.BILLING))
        recipients.saveAndFlush(recipient)
        em.clear()

        assertFalse(em.find(Address::class.java, recipientDefault.id)!!.isDefault)
        assertTrue(em.find(Address::class.java, senderDefault.id)!!.isDefault)
    }

    @Test
    @Transactional
    fun `boundary - a new default replaces the old one without violating the single-default index`() {
        val sender = sender("COV32-ADR-0004")
        val first = sender.addressBook.saveDefault(input(Address.AddressType.BILLING, "Alt 1"), null) { false }
        senders.save(sender)
        em.flush()

        val previous = sender.addressBook.revokeDefaults(Address.AddressType.BILLING)
        senders.saveAndFlush(sender)
        val second = sender.addressBook.saveDefault(input(Address.AddressType.BILLING, "Neu 2"), previous) { it === first }
        senders.save(sender)
        em.flush()

        assertNotNull(second.id)
        assertFalse(first.isDefault)
        assertTrue(second.isDefault)
    }
}
