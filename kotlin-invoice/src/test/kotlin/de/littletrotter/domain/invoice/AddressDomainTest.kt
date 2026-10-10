package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import de.littletrotter.shared.country.Country

class AddressDomainTest {

    // ── Address.validate() / validateOwner() ─────────────────────────────────

    @Test
    fun `a fully specified address passes validation`() {
        val address = Address.forRecipient(Recipient().apply { id = 1L }, Address.AddressType.BILLING, "Musterstraße 1", "12345", "Musterstadt")
        assertDoesNotThrow { address.validate() }
    }

    @Test
    fun `a blank postal code is rejected`() {
        val address = Address.forRecipient(Recipient().apply { id = 1L }, Address.AddressType.BILLING, "Musterstraße 1", "", "Musterstadt")
        assertThrows(InvalidParameterException::class.java) { address.validate() }
    }

    @Test
    fun `a blank city is rejected`() {
        val address = Address.forRecipient(Recipient().apply { id = 1L }, Address.AddressType.BILLING, "Musterstraße 1", "12345", "")
        assertThrows(InvalidParameterException::class.java) { address.validate() }
    }

    @Test
    fun `an address with both a recipient and a sender owner is rejected`() {
        val address = Address.forRecipient(Recipient().apply { id = 1L }, Address.AddressType.BILLING, "Musterstraße 1", "12345", "Musterstadt")
        // Both factories enforce exactly one owner at construction; a later mutation is the only
        // way an address could end up with two (or zero) owners — precisely what validateOwner() guards against.
        address.senderProfile = SenderProfile().apply { id = 2L }

        assertThrows(IllegalStateException::class.java) { address.validateOwner() }
    }

    // ── Address.isOwnedByRecipient / isOwnedBySender — null-safe ownership checks ──

    @Test
    fun `an address with no recipient at all is never owned by any recipient`() {
        val recipient = Recipient().apply { id = 1L }
        val senderAddress = Address.forSender(SenderProfile().apply { id = 9L }, Address.AddressType.BILLING, "Straße", "12345", "Stadt")

        assertFalse(senderAddress.isOwnedByRecipient(recipient))
    }

    @Test
    fun `an address with no senderProfile at all is never owned by any sender`() {
        val sender = SenderProfile().apply { id = 1L }
        val recipientAddress = Address.forRecipient(Recipient().apply { id = 9L }, Address.AddressType.BILLING, "Straße", "12345", "Stadt")

        assertFalse(recipientAddress.isOwnedBySender(sender))
    }

    @Test
    fun `recipient setBillingAddress replaces existing billing and keeps singleton`() {
        val recipient = Recipient().apply { id = 1L }
        val first = Address.forRecipient(recipient, Address.AddressType.BILLING, "Straße 1", "10115", "Berlin", Country.DE, true)
        recipient.setBillingAddress(first)

        assertEquals(1, recipient.addresses.size)
        assertEquals("Straße 1", recipient.billingAddress?.street)
        assertTrue(recipient.billingAddress!!.isDefault)

        val second = Address.forRecipient(recipient, Address.AddressType.BILLING, "Straße 2", "10117", "Berlin", Country.DE, true)
        recipient.setBillingAddress(second)

        assertEquals(1, recipient.addresses.size)
        assertEquals("Straße 2", recipient.billingAddress?.street)
        assertTrue(recipient.billingAddress!!.isDefault)
    }

    @Test
    fun `sender addDeliveryAddress accepts multiple deliveries`() {
        val sender = SenderProfile().apply { id = 1L }
        val d1 = Address.forSender(sender, Address.AddressType.DELIVERY, "Lager 1", "10117", "Berlin", Country.DE, false)
        val d2 = Address.forSender(sender, Address.AddressType.DELIVERY, "Lager 2", "10243", "Berlin", Country.DE, false)

        sender.addDeliveryAddress(d1)
        sender.addDeliveryAddress(d2)

        assertEquals(2, sender.deliveryAddresses.size)
        assertEquals("Lager 1", sender.deliveryAddresses[0].street)
        assertEquals("Lager 2", sender.deliveryAddresses[1].street)
    }

    // ── AddressOwner.addAddress() — routes by addressType to the right handler ──

    @Test
    fun `addAddress routes a BILLING address through setBillingAddress`() {
        val recipient = Recipient().apply { id = 1L }
        val billing = Address.forRecipient(recipient, Address.AddressType.BILLING, "Rechnungsweg 1", "10115", "Berlin")

        recipient.addAddress(billing)

        assertTrue(recipient.billingAddress === billing)
        assertTrue(billing.isDefault, "setBillingAddress always makes its argument the default")
    }

    @Test
    fun `addAddress routes a DELIVERY address through addDeliveryAddress`() {
        val recipient = Recipient().apply { id = 1L }
        val delivery = Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Lieferweg 1", "10117", "Berlin")

        recipient.addAddress(delivery)

        assertEquals(1, recipient.deliveryAddresses.size)
        assertTrue(recipient.deliveryAddresses.single() === delivery)
    }

    // ── setBillingAddress / addDeliveryAddress — ownership and type guards ──────

    @Test
    fun `setBillingAddress rejects an address that belongs to a different owner`() {
        val owner = Recipient().apply { id = 1L }
        val foreign = Address.forRecipient(Recipient().apply { id = 2L }, Address.AddressType.BILLING, "Straße", "12345", "Stadt")

        assertThrows(IllegalArgumentException::class.java) { owner.setBillingAddress(foreign) }
    }

    @Test
    fun `setBillingAddress rejects an address whose type is DELIVERY`() {
        val owner = Recipient().apply { id = 1L }
        val delivery = Address.forRecipient(owner, Address.AddressType.DELIVERY, "Straße", "12345", "Stadt")

        assertThrows(IllegalArgumentException::class.java) { owner.setBillingAddress(delivery) }
    }

    @Test
    fun `addDeliveryAddress rejects an address that belongs to a different owner`() {
        val owner = Recipient().apply { id = 1L }
        val foreign = Address.forRecipient(Recipient().apply { id = 2L }, Address.AddressType.DELIVERY, "Straße", "12345", "Stadt")

        assertThrows(IllegalArgumentException::class.java) { owner.addDeliveryAddress(foreign) }
    }

    @Test
    fun `addDeliveryAddress rejects an address whose type is BILLING`() {
        val owner = Recipient().apply { id = 1L }
        val billing = Address.forRecipient(owner, Address.AddressType.BILLING, "Straße", "12345", "Stadt")

        assertThrows(IllegalArgumentException::class.java) { owner.addDeliveryAddress(billing) }
    }

    @Test
    fun `setBillingAddress only demotes existing BILLING addresses, delivery addresses are untouched`() {
        val owner = Recipient().apply { id = 1L }
        val delivery = Address.forRecipient(owner, Address.AddressType.DELIVERY, "Lager", "10117", "Berlin", isDefault = true)
        owner.addAddress(delivery)
        val firstBilling = Address.forRecipient(owner, Address.AddressType.BILLING, "Rechnung 1", "10115", "Berlin")
        owner.setBillingAddress(firstBilling)

        val secondBilling = Address.forRecipient(owner, Address.AddressType.BILLING, "Rechnung 2", "10119", "Berlin")
        owner.setBillingAddress(secondBilling)

        assertTrue(delivery.isDefault, "the unrelated delivery address's default flag must survive a billing-address swap")
        assertTrue(secondBilling.isDefault)
        assertFalse(firstBilling.isDefault)
    }
}
