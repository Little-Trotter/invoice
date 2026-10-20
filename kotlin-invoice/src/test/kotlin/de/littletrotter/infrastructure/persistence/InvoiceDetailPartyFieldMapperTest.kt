package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.shared.InvoiceFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InvoiceDetailPartyFieldMapperTest {

    private val senderTuple = MapTuple(
        mapOf(
            "senderName" to "Musterfirma GmbH", "senderTaxNumber" to "TAX-1", "senderVatId" to "DE1",
            "senderEmail" to "s@example.org", "senderPhone" to "+49-1", "senderCommercialRegisterNumber" to "HRB 1",
            "senderCourtOfJurisdiction" to "Berlin", "senderRegisterCourt" to "AG Berlin", "senderIban" to "DE21",
            "senderManagingDirector" to "Marty McFly", "senderBankName" to "Postbank", "senderBic" to "PBNKDEFFXXX",
        ),
    )

    @Test
    fun `mapSender maps every sender column and carries the additional VAT countries through`() {
        val result = InvoiceDetailPartyFieldMapper.mapSender(senderTuple, senderId = 10L, additionalVatCountries = listOf("FR", "AT"))

        assertEquals(10L, result.id)
        assertEquals("Musterfirma GmbH", result.name)
        assertEquals("TAX-1", result.taxNumber)
        assertEquals("AG Berlin", result.registerCourt)
        assertEquals("Marty McFly", result.managingDirector)
        assertEquals(listOf("FR", "AT"), result.additionalVatRegistrationCountries)
    }

    @Test
    fun `mapRecipient returns null when recipientId is absent`() {
        assertNull(InvoiceDetailPartyFieldMapper.mapRecipient(MapTuple(mapOf("recipientId" to null))))
    }

    @Test
    fun `mapRecipient maps every recipient column when present`() {
        val tuple = MapTuple(
            mapOf(
                "recipientId" to 20L, "recipientName" to "Kunde AG", "recipientCustomerNumber" to "CUST-1",
                "recipientVatId" to "DE2", "recipientEmail" to "k@example.org", "recipientPhone" to "+49-2",
                "recipientContactPerson" to "Max Mustermann", "recipientRoutingId" to "992-XYZ",
                "recipientB2bProfileId" to 5L, "recipientInvoiceFormat" to InvoiceFormat.INTERNATIONAL,
            ),
        )

        val result = InvoiceDetailPartyFieldMapper.mapRecipient(tuple)

        assertEquals(20L, result?.id)
        assertEquals("Kunde AG", result?.name)
        assertEquals("992-XYZ", result?.routingId)
        assertEquals(5L, result?.b2bProfileId)
        assertEquals(InvoiceFormat.INTERNATIONAL.ordinal, result?.invoiceFormat)
    }

    @Test
    fun `mapBillingAddress returns null when billingAddressId is absent, maps every column when present`() {
        assertNull(InvoiceDetailPartyFieldMapper.mapBillingAddress(MapTuple(mapOf("billingAddressId" to null))))

        val tuple = MapTuple(
            mapOf(
                "billingAddressId" to 301L, "billingStreet" to "Senderweg 1", "billingPostalCode" to "10115",
                "billingCity" to "Berlin", "billingCountry" to "DE",
            ),
        )
        val result = InvoiceDetailPartyFieldMapper.mapBillingAddress(tuple)

        assertEquals(301L, result?.id)
        assertEquals("Senderweg 1", result?.street)
        assertEquals("DE", result?.countryAlpha2)
    }

    @Test
    fun `mapDeliveryAddress returns null when deliveryAddressId is absent, maps every column when present`() {
        assertNull(InvoiceDetailPartyFieldMapper.mapDeliveryAddress(MapTuple(mapOf("deliveryAddressId" to null))))

        val tuple = MapTuple(
            mapOf(
                "deliveryAddressId" to 401L, "deliveryStreet" to "Lieferweg 2", "deliveryPostalCode" to "50667",
                "deliveryCity" to "Koeln", "deliveryCountry" to "DE",
            ),
        )
        val result = InvoiceDetailPartyFieldMapper.mapDeliveryAddress(tuple)

        assertEquals(401L, result?.id)
        assertEquals("Lieferweg 2", result?.street)
        assertEquals("DE", result?.countryAlpha2)
    }
}
