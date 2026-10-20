package de.littletrotter.domain.masterdata

import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [Address.forRecipient]/[Address.forSender] after the S107 reshaping of their shared
 *  private builder: every field lands where it did before, exactly one owner is set. */
class AddressFactoryTest {

    @Test
    fun `forRecipient sets every field and the recipient as sole owner`() {
        val recipient = aRecipient { id = 7L }

        val address = Address.forRecipient(
            recipient, Address.AddressType.DELIVERY, "Lieferweg 3", "54321", "Hamburg", Country.AT, isDefault = true,
        )

        assertSame(recipient, address.recipient)
        assertNull(address.senderProfile)
        assertEquals(Address.AddressType.DELIVERY, address.addressType)
        assertEquals("Lieferweg 3", address.street)
        assertEquals("54321", address.postalCode.value)
        assertEquals("Hamburg", address.city)
        assertEquals(Country.AT, address.country)
        assertTrue(address.isDefault)
        assertTrue(address.isOwnedByRecipient(recipient))
    }

    @Test
    fun `forSender sets every field and the sender as sole owner`() {
        val sender = aSenderProfile { id = 3L }

        val address = Address.forSender(sender, Address.AddressType.BILLING, "Teststrasse 42", "50667", "Koeln", Country.DE, true)

        assertSame(sender, address.senderProfile)
        assertNull(address.recipient)
        assertEquals("Teststrasse 42", address.street)
        assertEquals("50667", address.postalCode.value)
        assertEquals("Koeln", address.city)
        assertTrue(address.isDefault)
        assertTrue(address.isOwnedBySender(sender))
    }

    @Test
    fun `boundary - omitted country and isDefault fall back to DE and false`() {
        val address = Address.forRecipient(Recipient(), Address.AddressType.BILLING, "Weg 1", "12345", "Berlin")

        assertEquals(Country.DE, address.country)
        assertFalse(address.isDefault)
    }

    @Test
    fun `boundary - blank lines are accepted by the factory and only rejected by validate`() {
        val address = Address.forRecipient(Recipient(), Address.AddressType.BILLING, "", "", "")

        val ex = assertThrows(InvalidParameterException::class.java) { address.validate() }
        assertEquals("Straße darf nicht leer sein.", ex.message)
    }

    @Test
    fun `boundary - validate names the first blank field after the street`() {
        val noPostal = Address.forSender(SenderProfile(), Address.AddressType.BILLING, "Weg 1", " ", "Berlin")
        val noCity = Address.forSender(SenderProfile(), Address.AddressType.BILLING, "Weg 1", "12345", "")

        assertEquals("Postleitzahl darf nicht leer sein.", assertThrows(InvalidParameterException::class.java) { noPostal.validate() }.message)
        assertEquals("Stadt darf nicht leer sein.", assertThrows(InvalidParameterException::class.java) { noCity.validate() }.message)
    }

    @Test
    fun `boundary - a second owner assigned later fails validateOwner`() {
        val address = Address.forRecipient(Recipient(), Address.AddressType.BILLING, "Weg 1", "12345", "Berlin")
        address.edit { senderProfile = SenderProfile() }

        assertThrows(IllegalStateException::class.java) { address.validateOwner() }
    }

    @Test
    fun `boundary - ownership checks compare ids, not instances`() {
        val address = Address.forRecipient(aRecipient { id = 1L }, Address.AddressType.BILLING, "Weg 1", "12345", "Berlin")

        assertTrue(address.isOwnedByRecipient(aRecipient { id = 1L }))
        assertFalse(address.isOwnedByRecipient(aRecipient { id = 2L }))
        assertFalse(address.isOwnedBySender(aSenderProfile { id = 1L }))
    }

    @Test
    fun `relocate moves street postal code city and country`() {
        val address = Address.forRecipient(Recipient(), Address.AddressType.BILLING, "Alt 1", "50667", "Köln", Country.DE)
        address.relocate("Neu 2", "1010", "Wien", Country.AT)
        assertEquals("Neu 2", address.street)
        assertEquals("1010", address.postalCode.value)
        assertEquals("Wien", address.city)
        assertEquals(Country.AT, address.country)
    }

    @Test
    fun `relocate rejects a postal code that does not fit the country`() {
        val address = Address.forRecipient(Recipient(), Address.AddressType.BILLING, "Alt 1", "50667", "Köln", Country.DE)
        assertThrows(InvalidParameterException::class.java) { address.relocate("Neu 2", "ABC", "Berlin", Country.DE) }
    }

    @Test
    fun `markAsDefault and revokeDefault toggle the default flag`() {
        val address = Address.forSender(SenderProfile(), Address.AddressType.DELIVERY, "Alt 1", "50667", "Köln")
        assertFalse(address.isDefault)
        address.markAsDefault()
        assertTrue(address.isDefault)
        address.revokeDefault()
        assertFalse(address.isDefault)
    }
}
