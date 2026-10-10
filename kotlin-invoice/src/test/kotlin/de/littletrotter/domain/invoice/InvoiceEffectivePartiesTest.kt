package de.littletrotter.domain.invoice

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber

/**
 * Which address counts as "this is where the goods/services actually go" for this
 * document: the invoice-level override first, otherwise the recipient's own delivery address.
 *
 * These tests exercise [Invoice.effectiveRecipientDeliveryAddress] directly -- the fallback
 * rule used in production by `CoreCompletenessRules`, `Invoice.validate()`, and the overview.
 */
class InvoiceEffectivePartiesTest {

    private fun recipient() = Recipient().apply {
        customerNumber = CustomerNumber("K-1")
        name = "Kunde GmbH"
    }

    @Test
    fun `an invoice-level delivery override wins over the recipient's own delivery address`() {
        val r = recipient()
        val recipientDelivery = Address.forRecipient(r, Address.AddressType.DELIVERY, "Lagerweg 1", "50667", "Koeln", Country.DE, true)
        r.addAddress(recipientDelivery)
        val override = Address.forRecipient(r, Address.AddressType.DELIVERY, "Sonderweg 9", "10115", "Berlin", Country.DE, false)
        val invoice = Invoice().apply { recipient = r; deliveryAddress = override }

        assertEquals(override, invoice.effectiveRecipientDeliveryAddress())
    }

    @Test
    fun `without an invoice-level override, the recipient's own delivery address is used`() {
        val r = recipient()
        val delivery = Address.forRecipient(r, Address.AddressType.DELIVERY, "Lagerweg 1", "50667", "Koeln", Country.DE, true)
        r.addAddress(delivery)
        val invoice = Invoice().apply { recipient = r; deliveryAddress = null }

        assertEquals(delivery, invoice.effectiveRecipientDeliveryAddress())
    }

    @Test
    fun `with neither an override nor a recipient delivery address, there is nothing to display`() {
        val invoice = Invoice().apply { recipient = recipient(); deliveryAddress = null }

        assertNull(invoice.effectiveRecipientDeliveryAddress())
    }
}
