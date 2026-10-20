package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.test.inMemoryTypedQuery
import jakarta.persistence.EntityManager
import jakarta.persistence.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [RecipientReadRepositoryImpl.mapRows]' projection: the scalar recipient fields, the
 * tolerant enum/country parsing ([de.littletrotter.infrastructure.persistence
 * .InvoiceDetailRowSupport]'s "converted-or-raw" cell shape applies here too) and — the one
 * mapping [RecipientReadRepositoryImplIT] cannot exercise without real address rows — the
 * isDefault-first/lowest-id preferred-address pick between two billing candidates. Exercised
 * through the public [de.littletrotter.application.masterdata.port.outbound.RecipientReadRepository] surface,
 * against a mocked [EntityManager], same shape as [InvoiceReadRepositoryImplTest].
 */
class RecipientReadRepositoryImplTest {

    private fun recipientTuple(
        id: Long = 1L,
        name: String = "Acme GmbH",
        customerNumber: Any? = "K-0001",
        vatId: Any? = "DE123456789",
        customerType: Any? = CustomerType.BUSINESS,
        vatIdVerificationStatus: Any? = VatIdVerificationStatus.VERIFIED,
        reverseChargePreference: Any? = ReverseChargePreference.ASK,
    ): Tuple = mock<Tuple>().also { t ->
        whenever(t.get("id")).thenReturn(id)
        whenever(t.get("name")).thenReturn(name)
        whenever(t.get("customerNumber")).thenReturn(customerNumber)
        whenever(t.get("shortHash")).thenReturn(null)
        whenever(t.get("contactPerson")).thenReturn(null)
        whenever(t.get("routingId")).thenReturn(null)
        whenever(t.get("vatId")).thenReturn(vatId)
        whenever(t.get("customerType")).thenReturn(customerType)
        whenever(t.get("vatIdVerificationStatus")).thenReturn(vatIdVerificationStatus)
        whenever(t.get("reverseChargePreference")).thenReturn(reverseChargePreference)
    }

    private fun addressTuple(
        recipientId: Long,
        id: Long,
        city: String?,
        country: Any?,
        type: Address.AddressType,
        isDefault: Boolean,
    ): Tuple = mock<Tuple>().also { t ->
        whenever(t.get("recipientId")).thenReturn(recipientId)
        whenever(t.get("id")).thenReturn(id)
        whenever(t.get("city")).thenReturn(city)
        whenever(t.get("country")).thenReturn(country)
        whenever(t.get("addressType")).thenReturn(type)
        whenever(t.get("isDefault")).thenReturn(isDefault)
    }

    private fun mockedEm(recipientRows: List<Tuple>, addressRows: List<Tuple>): EntityManager {
        val em: EntityManager = mock()
        // The replacement query objects are built BEFORE either `whenever(...)` call starts: Mockito
        // tracks "an unfinished stub" as thread-global state between `whenever(x)` and its
        // `.thenReturn(y)`, so evaluating `y` via another `whenever(...)` call (as
        // `inMemoryTypedQuery` does internally) while that state is still open throws
        // UnfinishedStubbingException.
        val recipientQuery = inMemoryTypedQuery(recipientRows)
        val addressQuery = inMemoryTypedQuery(addressRows)
        whenever(em.createQuery(argThat<String> { contains("FROM Recipient r") }, eq(Tuple::class.java))).thenReturn(recipientQuery)
        whenever(em.createQuery(argThat<String> { contains("FROM Address a") }, eq(Tuple::class.java))).thenReturn(addressQuery)
        return em
    }

    private fun repositoryWith(em: EntityManager): RecipientReadRepositoryImpl =
        RecipientReadRepositoryImpl().also { repo ->
            val field = BasePanacheRepository::class.java.getDeclaredField("em")
            field.isAccessible = true
            field.set(repo, em)
        }

    @Test
    fun `findRecipientById maps every scalar field`() {
        val em = mockedEm(listOf(recipientTuple()), emptyList())

        val dto = repositoryWith(em).findRecipientById(1L)

        assertEquals(1L, dto?.id)
        assertEquals("Acme GmbH", dto?.name)
        assertEquals("K-0001", dto?.customerNumber)
        assertEquals("DE123456789", dto?.vatId)
        assertEquals(CustomerType.BUSINESS, dto?.customerType)
        assertEquals(VatIdVerificationStatus.VERIFIED, dto?.vatIdVerificationStatus)
        assertEquals(ReverseChargePreference.ASK, dto?.reverseChargePreference)
    }

    @Test
    fun `findRecipientById is null when the scalar query returns no row`() {
        val em = mockedEm(emptyList(), emptyList())

        assertNull(repositoryWith(em).findRecipientById(99L))
    }

    @Test
    fun `the default billing address wins over a non-default one, and its city and country are projected`() {
        val em = mockedEm(
            listOf(recipientTuple(id = 5L)),
            listOf(
                addressTuple(5L, id = 10L, city = "Altstadt", country = Country.DE, type = Address.AddressType.BILLING, isDefault = false),
                addressTuple(5L, id = 11L, city = "Berlin", country = Country.DE, type = Address.AddressType.BILLING, isDefault = true),
                addressTuple(5L, id = 12L, city = "Hamburg", country = Country.DE, type = Address.AddressType.DELIVERY, isDefault = true),
            ),
        )

        val dto = repositoryWith(em).findRecipientById(5L)

        assertEquals("Berlin", dto?.billingCity)
        assertEquals("DE", dto?.billingCountryAlpha2)
        assertEquals("DE", dto?.deliveryCountryAlpha2)
    }

    @Test
    fun `a recipient without any address rows projects null billing and delivery fields`() {
        val em = mockedEm(listOf(recipientTuple(id = 7L)), emptyList())

        val dto = repositoryWith(em).findRecipientById(7L)

        assertNull(dto?.billingCity)
        assertNull(dto?.billingCountryAlpha2)
        assertNull(dto?.deliveryCountryAlpha2)
    }

    @Test
    fun `findRecipients delegates paging, filter and sort to the query`() {
        val em: EntityManager = mock()
        val query = inMemoryTypedQuery(emptyList<Tuple>())
        whenever(em.createQuery(any<String>(), eq(Tuple::class.java))).thenReturn(query)

        val result = repositoryWith(em).findRecipients(2, 20, "acme", "customerNumber", SortDirection.DESC)

        assertEquals(emptyList<Any>(), result)
    }
}
