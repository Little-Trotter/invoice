package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.masterdata.port.outbound.RecipientReadRepository
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aRecipient
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [RecipientReadRepositoryImpl] against real Postgres: the projection must return the
 * same scalar fields and preferred billing/delivery address [PanacheRecipientRepository]'s
 * entity-backed reads used to expose, without loading the entity or its lazy address collections.
 * Same Testcontainers pattern as [InvoiceReadRepositoryImplIT].
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class RecipientReadRepositoryImplIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var recipientReadRepository: RecipientReadRepository

    @Test
    @Transactional
    fun `findRecipientById projects the preferred billing and delivery address`() {
        val recipient = aRecipient {
            name = "Projektions GmbH"
            customerNumber = CustomerNumber.ofNullable("RR-0001")
            vatId = VatId.ofNullable("DE123456789")
            customerType = CustomerType.BUSINESS
            reverseChargePreference = ReverseChargePreference.ASK
        }
        em.persist(recipient)
        // A non-default billing row (id lower, isDefault false) must lose to the isDefault one.
        em.persist(Address.forRecipient(recipient, Address.AddressType.BILLING, "Alte Straße 1", "10115", "Berlin", Country.DE, false))
        em.persist(Address.forRecipient(recipient, Address.AddressType.BILLING, "Hauptstraße 1", "10115", "Berlin", Country.DE, true))
        em.persist(Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Lagerweg 1", "22765", "Hamburg", Country.DE, true))
        em.flush()

        val dto = recipientReadRepository.findRecipientById(recipient.id!!)

        assertEquals("Projektions GmbH", dto?.name)
        assertEquals("RR-0001", dto?.customerNumber)
        assertEquals("DE123456789", dto?.vatId)
        assertEquals(CustomerType.BUSINESS, dto?.customerType)
        assertEquals("Berlin", dto?.billingCity, "the default billing address must win over the non-default one")
        assertEquals("DE", dto?.billingCountryAlpha2)
        assertEquals("DE", dto?.deliveryCountryAlpha2)
    }

    @Test
    @Transactional
    fun `findRecipientById is null when no such recipient exists`() {
        assertNull(recipientReadRepository.findRecipientById(-1L))
    }

    @Test
    @Transactional
    fun `findRecipients filters, sorts and pages, without a billing address for a token match on city`() {
        val matching = aRecipient { name = "Filter Treffer GmbH"; customerNumber = CustomerNumber.ofNullable("RR-0010") }
        val other = aRecipient { name = "Anderer Kunde"; customerNumber = CustomerNumber.ofNullable("RR-0011") }
        em.persist(matching)
        em.persist(other)
        em.persist(Address.forRecipient(matching, Address.AddressType.BILLING, "Weg 1", "12345", "Filterstadt", Country.DE, true))
        em.flush()

        val byName = recipientReadRepository.findRecipients(0, 60, "Filter Treffer", "name", SortDirection.ASC)
        assertEquals(listOf(matching.id), byName.map { it.id })

        val byCity = recipientReadRepository.findRecipients(0, 60, "Filterstadt", "name", SortDirection.ASC)
        assertEquals(listOf(matching.id), byCity.map { it.id })
    }
}
