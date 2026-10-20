package de.littletrotter.application.masterdata

import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.masterdata.port.outbound.RecipientReadRepository
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aRecipient
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.spi.party.PartyFormExtension
import de.littletrotter.spi.party.PartyIdentifierSpec
import de.littletrotter.spi.party.PartyKind
import de.littletrotter.spi.party.PartyPairState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Optional

/** [RecipientQueries] on top of [CustomerManagementService]'s reads,
 *  plus [AddressManagementService]'s/[MasterDataNumberService]'s reads,
 *  plus [findRecipientDetail]'s [de.littletrotter.application.dto.RecipientDetailDto]
 *  assembly. */
class RecipientQueriesTest {

    private val customerManagementService: CustomerManagementService = mock()
    private val addressManagementService: AddressManagementService = mock()
    private val masterDataNumberService: MasterDataNumberService = mock()
    private val partyFormExtensionRegistry = PartyFormExtensionRegistry(emptyList())
    private val recipientDetailMapper = RecipientDetailMapper(addressManagementService, partyFormExtensionRegistry)
    private val recipientReadRepository: RecipientReadRepository = mock()
    private val queries = RecipientQueries(
        customerManagementService,
        addressManagementService,
        masterDataNumberService,
        recipientDetailMapper,
        recipientReadRepository,
    )

    private fun summaryDto(id: Long) = RecipientSummaryDto(
        id = id,
        name = "Acme GmbH",
        customerNumber = null,
        shortHash = null,
        contactPerson = null,
        routingId = null,
        vatId = null,
        customerType = null,
        vatIdVerificationStatus = null,
        reverseChargePreference = ReverseChargePreference.ASK,
        billingCity = null,
        billingCountryAlpha2 = null,
        deliveryCountryAlpha2 = null,
    )

    @Test
    fun `findRecipientById forwards to the read repository`() {
        val recipient = summaryDto(5L)
        whenever(recipientReadRepository.findRecipientById(5L)).thenReturn(recipient)

        assertEquals(recipient, queries.findRecipientById(5L))
    }

    @Test
    fun `findRecipients forwards page, filter and sort`() {
        val recipients = listOf(summaryDto(1L))
        whenever(recipientReadRepository.findRecipients(1, 20, "acme", "city", SortDirection.DESC)).thenReturn(recipients)

        assertEquals(recipients, queries.findRecipients(1, 20, "acme", "city", SortDirection.DESC))
    }

    @Test
    fun `countRecipients forwards the filter text`() {
        whenever(customerManagementService.count("acme")).thenReturn(3L)

        assertEquals(3L, queries.countRecipients("acme"))
    }

    @Test
    fun `happy path - findBillingAddress uses the address service result when present`() {
        val recipient = aRecipient { id = 7L }
        val fromService = Address.forRecipient(recipient, Address.AddressType.BILLING, "Dienstweg 1", "10115", "Berlin")
        whenever(customerManagementService.findById(7L)).thenReturn(recipient)
        whenever(addressManagementService.getBillingAddressForCustomer(recipient)).thenReturn(Optional.of(fromService))

        assertEquals("Dienstweg 1", queries.findBillingAddress(7L)?.street)
    }

    @Test
    fun `boundary - findBillingAddress is null when the recipient does not exist`() {
        whenever(customerManagementService.findById(99L)).thenReturn(null)

        assertNull(queries.findBillingAddress(99L))
    }

    @Test
    fun `boundary - findBillingAddress falls back to the recipient's own billingAddress without an address-service hit`() {
        val fallback = Address.forRecipient(Recipient(), Address.AddressType.BILLING, "Altbestand 1", "10115", "Berlin", isDefault = true)
        val recipient = aRecipient { id = 8L; addresses = mutableListOf(fallback) }
        whenever(customerManagementService.findById(8L)).thenReturn(recipient)
        whenever(addressManagementService.getBillingAddressForCustomer(recipient)).thenReturn(Optional.empty())

        assertEquals("Altbestand 1", queries.findBillingAddress(8L)?.street)
    }

    @Test
    fun `happy path - findDeliveryAddress uses the address service result when present`() {
        val recipient = aRecipient { id = 12L }
        val fromService = Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Zustellweg 1", "10115", "Berlin")
        whenever(customerManagementService.findById(12L)).thenReturn(recipient)
        whenever(addressManagementService.getDeliveryAddressForCustomer(recipient)).thenReturn(Optional.of(fromService))

        assertEquals("Zustellweg 1", queries.findDeliveryAddress(12L)?.street)
    }

    @Test
    fun `boundary - findDeliveryAddress is null when the recipient does not exist`() {
        whenever(customerManagementService.findById(99L)).thenReturn(null)

        assertNull(queries.findDeliveryAddress(99L))
    }

    @Test
    fun `boundary - findDeliveryAddress falls back to the recipient's own deliveryAddress without an address-service hit`() {
        val fallback = Address.forRecipient(Recipient(), Address.AddressType.DELIVERY, "Lagerweg 1", "10115", "Berlin", isDefault = true)
        val recipient = aRecipient { id = 9L; addresses = mutableListOf(fallback) }
        whenever(customerManagementService.findById(9L)).thenReturn(recipient)
        whenever(addressManagementService.getDeliveryAddressForCustomer(recipient)).thenReturn(Optional.empty())

        assertEquals("Lagerweg 1", queries.findDeliveryAddress(9L)?.street)
    }

    @Test
    fun `happy path - proposeCustomerNumber forwards to the number service`() {
        whenever(masterDataNumberService.proposeCustomer("Acme GmbH")).thenReturn("K-00042")

        assertEquals("K-00042", queries.proposeCustomerNumber("Acme GmbH"))
    }

    @Test
    fun `boundary - proposeCustomerNumber is null without an active number range`() {
        whenever(masterDataNumberService.proposeCustomer("Acme GmbH")).thenReturn(null)

        assertNull(queries.proposeCustomerNumber("Acme GmbH"))
    }

    @Test
    fun `happy path - findRecipientDetail assembles entity fields, addresses and SPI pairs`() {
        val spec = PartyIdentifierSpec(id = "electronicAddress", i18nKeyPrefix = "recipient.electronicAddress")
        val extension = mock<PartyFormExtension>()
        val registry = PartyFormExtensionRegistry(listOf(extension))
        val mapperWithSpi = RecipientDetailMapper(addressManagementService, registry)
        val queriesWithSpi = RecipientQueries(
            customerManagementService,
            addressManagementService,
            masterDataNumberService,
            mapperWithSpi,
            recipientReadRepository,
        )

        val recipient = aRecipient {
            id = 20L
            version = 3L
            name = "Acme GmbH"
            customerNumber = CustomerNumber.ofNullable("K-0001")
            vatId = VatId.ofNullable("DE123456789")
            customerType = CustomerType.BUSINESS
        }
        val billing = Address.forRecipient(recipient, Address.AddressType.BILLING, "Dienstweg 1", "10115", "Berlin")
        val delivery = Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Zustellweg 1", "10117", "Berlin")
        whenever(customerManagementService.findById(20L)).thenReturn(recipient)
        whenever(addressManagementService.getBillingAddressForCustomer(recipient)).thenReturn(Optional.of(billing))
        whenever(addressManagementService.getDeliveryAddressForCustomer(recipient)).thenReturn(Optional.of(delivery))
        whenever(extension.pairs(PartyKind.BUYER)).thenReturn(listOf(spec))
        whenever(extension.read(recipient, PartyKind.BUYER, spec)).thenReturn(PartyPairState("EM:buyer@example.invalid", "EM"))

        val detail = queriesWithSpi.findRecipientDetail(20L)

        assertEquals(20L, detail?.id)
        assertEquals(3L, detail?.version)
        assertEquals("Acme GmbH", detail?.name)
        assertEquals("K-0001", detail?.customerNumber)
        assertEquals("DE123456789", detail?.vatId)
        assertEquals(CustomerType.BUSINESS, detail?.customerType)
        assertNull(detail?.vatIdVerificationStatus)
        assertEquals("Dienstweg 1", detail?.billingAddress?.street)
        assertEquals("Zustellweg 1", detail?.deliveryAddress?.street)
        assertEquals(PartyPairState("EM:buyer@example.invalid", "EM"), detail?.partyPairs?.get("electronicAddress"))
        assertTrue(detail != null)
    }

    @Test
    fun `boundary - findRecipientDetail is null when the recipient does not exist`() {
        whenever(customerManagementService.findById(99L)).thenReturn(null)

        assertNull(queries.findRecipientDetail(99L))
    }
}
