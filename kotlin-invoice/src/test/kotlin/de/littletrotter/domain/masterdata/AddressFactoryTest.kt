package de.littletrotter.domain.masterdata

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
        val recipient = Recipient().apply { id = 7L }

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
        val sender = SenderProfile().apply { id = 3L }

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
        address.senderProfile = SenderProfile()

        assertThrows(IllegalStateException::class.java) { address.validateOwner() }
    }

    @Test
    fun `boundary - ownership checks compare ids, not instances`() {
        val address = Address.forRecipient(Recipient().apply { id = 1L }, Address.AddressType.BILLING, "Weg 1", "12345", "Berlin")

        assertTrue(address.isOwnedByRecipient(Recipient().apply { id = 1L }))
        assertFalse(address.isOwnedByRecipient(Recipient().apply { id = 2L }))
        assertFalse(address.isOwnedBySender(SenderProfile().apply { id = 1L }))
    }
}
