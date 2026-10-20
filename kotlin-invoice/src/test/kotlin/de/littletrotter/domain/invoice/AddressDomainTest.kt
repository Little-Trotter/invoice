package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import de.littletrotter.shared.country.Country

class AddressDomainTest {

    // ── Address.validate() / validateOwner() ─────────────────────────────────

    @Test
    fun `a fully specified address passes validation`() {
        val address = Address.forRecipient(aRecipient { id = 1L }, Address.AddressType.BILLING, "Musterstraße 1", "12345", "Musterstadt")
        assertDoesNotThrow { address.validate() }
    }

    @Test
    fun `a blank postal code is rejected`() {
        val address = Address.forRecipient(aRecipient { id = 1L }, Address.AddressType.BILLING, "Musterstraße 1", "", "Musterstadt")
        assertThrows(InvalidParameterException::class.java) { address.validate() }
    }

    @Test
    fun `a blank city is rejected`() {
        val address = Address.forRecipient(aRecipient { id = 1L }, Address.AddressType.BILLING, "Musterstraße 1", "12345", "")
        assertThrows(InvalidParameterException::class.java) { address.validate() }
    }

    @Test
    fun `an address with both a recipient and a sender owner is rejected`() {
        val address = Address.forRecipient(aRecipient { id = 1L }, Address.AddressType.BILLING, "Musterstraße 1", "12345", "Musterstadt")
        // Both factories enforce exactly one owner at construction; a later mutation is the only
        // way an address could end up with two (or zero) owners — precisely what validateOwner() guards against.
        address.edit { senderProfile = aSenderProfile { id = 2L } }

        assertThrows(IllegalStateException::class.java) { address.validateOwner() }
    }

    // ── Address.isOwnedByRecipient / isOwnedBySender — null-safe ownership checks ──

    @Test
    fun `an address with no recipient at all is never owned by any recipient`() {
        val recipient = aRecipient { id = 1L }
        val senderAddress = Address.forSender(aSenderProfile { id = 9L }, Address.AddressType.BILLING, "Straße", "12345", "Stadt")

        assertFalse(senderAddress.isOwnedByRecipient(recipient))
    }

    @Test
    fun `an address with no senderProfile at all is never owned by any sender`() {
        val sender = aSenderProfile { id = 1L }
        val recipientAddress = Address.forRecipient(aRecipient { id = 9L }, Address.AddressType.BILLING, "Straße", "12345", "Stadt")

        assertFalse(recipientAddress.isOwnedBySender(sender))
    }

    @Test
    fun `recipient designateBillingAddress replaces existing billing and keeps singleton`() {
        val recipient = aRecipient { id = 1L }
        val first = Address.forRecipient(recipient, Address.AddressType.BILLING, "Straße 1", "10115", "Berlin", Country.DE, true)
        recipient.designateBillingAddress(first)

        assertEquals(1, recipient.addressList.size)
        assertEquals("Straße 1", recipient.billingAddress?.street)
        assertTrue(recipient.billingAddress!!.isDefault)

        val second = Address.forRecipient(recipient, Address.AddressType.BILLING, "Straße 2", "10117", "Berlin", Country.DE, true)
        recipient.designateBillingAddress(second)

        assertEquals(1, recipient.addressList.size)
        assertEquals("Straße 2", recipient.billingAddress?.street)
        assertTrue(recipient.billingAddress!!.isDefault)
    }

    @Test
    fun `sender addDeliveryAddress accepts multiple deliveries`() {
        val sender = aSenderProfile { id = 1L }
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
    fun `addAddress routes a BILLING address through designateBillingAddress`() {
        val recipient = aRecipient { id = 1L }
        val billing = Address.forRecipient(recipient, Address.AddressType.BILLING, "Rechnungsweg 1", "10115", "Berlin")

        recipient.addAddress(billing)

        assertTrue(recipient.billingAddress === billing)
        assertTrue(billing.isDefault, "designateBillingAddress always makes its argument the default")
    }

    @Test
    fun `addAddress routes a DELIVERY address through addDeliveryAddress`() {
        val recipient = aRecipient { id = 1L }
        val delivery = Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Lieferweg 1", "10117", "Berlin")

        recipient.addAddress(delivery)

        assertEquals(1, recipient.deliveryAddresses.size)
        assertTrue(recipient.deliveryAddresses.single() === delivery)
    }

    // ── designateBillingAddress / addDeliveryAddress — ownership and type guards ──────

    @Test
    fun `designateBillingAddress rejects an address that belongs to a different owner`() {
        val owner = aRecipient { id = 1L }
        val foreign = Address.forRecipient(aRecipient { id = 2L }, Address.AddressType.BILLING, "Straße", "12345", "Stadt")

        assertThrows(IllegalArgumentException::class.java) { owner.designateBillingAddress(foreign) }
    }

    @Test
    fun `designateBillingAddress rejects an address whose type is DELIVERY`() {
        val owner = aRecipient { id = 1L }
        val delivery = Address.forRecipient(owner, Address.AddressType.DELIVERY, "Straße", "12345", "Stadt")

        assertThrows(IllegalArgumentException::class.java) { owner.designateBillingAddress(delivery) }
    }

    @Test
    fun `addDeliveryAddress rejects an address that belongs to a different owner`() {
        val owner = aRecipient { id = 1L }
        val foreign = Address.forRecipient(aRecipient { id = 2L }, Address.AddressType.DELIVERY, "Straße", "12345", "Stadt")

        assertThrows(IllegalArgumentException::class.java) { owner.addDeliveryAddress(foreign) }
    }

    @Test
    fun `addDeliveryAddress rejects an address whose type is BILLING`() {
        val owner = aRecipient { id = 1L }
        val billing = Address.forRecipient(owner, Address.AddressType.BILLING, "Straße", "12345", "Stadt")

        assertThrows(IllegalArgumentException::class.java) { owner.addDeliveryAddress(billing) }
    }

    @Test
    fun `designateBillingAddress only demotes existing BILLING addresses, delivery addresses are untouched`() {
        val owner = aRecipient { id = 1L }
        val delivery = Address.forRecipient(owner, Address.AddressType.DELIVERY, "Lager", "10117", "Berlin", isDefault = true)
        owner.addAddress(delivery)
        val firstBilling = Address.forRecipient(owner, Address.AddressType.BILLING, "Rechnung 1", "10115", "Berlin")
        owner.designateBillingAddress(firstBilling)

        val secondBilling = Address.forRecipient(owner, Address.AddressType.BILLING, "Rechnung 2", "10119", "Berlin")
        owner.designateBillingAddress(secondBilling)

        assertTrue(delivery.isDefault, "the unrelated delivery address's default flag must survive a billing-address swap")
        assertTrue(secondBilling.isDefault)
        assertFalse(firstBilling.isDefault)
    }

    // ── revokeDefaults / saveDefault — the rules the address book owns ──

    @Test
    fun `saveDefault relocates an unreferenced default in place and adds a row for a referenced one`() {
        val owner = aSenderProfile { id = 3L }
        val input = de.littletrotter.domain.masterdata.AddressInput(Address.AddressType.BILLING, "Neu 1", "20095", "Hamburg", Country.DE)
        owner.addAddress(Address.forSender(owner, Address.AddressType.BILLING, "Alt 1", "10115", "Berlin", Country.DE, true))

        val previous = owner.addressBook.revokeDefaults(Address.AddressType.BILLING)
        val relocated = owner.addressBook.saveDefault(input, previous) { false }
        assertSame(previous, relocated)
        assertEquals("Neu 1", relocated.street)

        val previousAgain = owner.addressBook.revokeDefaults(Address.AddressType.BILLING)
        val added = owner.addressBook.saveDefault(input.copy(street = "Neu 2"), previousAgain) { true }
        assertNotSame(previousAgain, added)
        assertEquals(2, owner.addressList.size)
        assertTrue(added.isDefault)
    }

    @Test
    fun `revokeDefaults reports no previous default when none was set`() {
        val owner = aSenderProfile { id = 3L }

        assertNull(owner.addressBook.revokeDefaults(Address.AddressType.BILLING))
    }
}
