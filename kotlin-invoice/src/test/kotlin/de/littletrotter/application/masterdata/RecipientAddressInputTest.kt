package de.littletrotter.application.masterdata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** [RecipientAddressInput], the parameter object of the recipient save commands. */
class RecipientAddressInputTest {

    @Test
    fun `billing-only input defaults the country to DE and carries no delivery address`() {
        val input = RecipientAddressInput("Weg 1", "12345", "Berlin")

        assertEquals("DE", input.billingCountry)
        assertNull(input.deliveryStreet)
        assertNull(input.deliveryPostalCode)
        assertNull(input.deliveryCity)
        assertNull(input.deliveryCountry)
    }

    @Test
    fun `trimmed strips street, postal code and city on both addresses`() {
        val input = RecipientAddressInput(
            billingStreet = "  Weg 1 ",
            billingPostalCode = " 12345",
            billingCity = "Berlin  ",
            billingCountry = "DE",
            deliveryStreet = " Lagerweg 2 ",
            deliveryPostalCode = " 20095 ",
            deliveryCity = " Hamburg",
            deliveryCountry = "AT",
        )

        assertEquals(
            RecipientAddressInput("Weg 1", "12345", "Berlin", "DE", "Lagerweg 2", "20095", "Hamburg", "AT"),
            input.trimmed(),
        )
    }

    @Test
    fun `boundary - trimmed keeps null delivery lines null`() {
        val trimmed = RecipientAddressInput("Weg 1", "12345", "Berlin", deliveryStreet = " Lagerweg 2 ").trimmed()

        assertEquals("Lagerweg 2", trimmed.deliveryStreet)
        assertNull(trimmed.deliveryPostalCode)
        assertNull(trimmed.deliveryCity)
        assertNull(trimmed.deliveryCountry)
    }

    @Test
    fun `boundary - trimmed leaves country codes untouched, even padded ones`() {
        val trimmed = RecipientAddressInput("Weg 1", "12345", "Berlin", billingCountry = " LU", deliveryCountry = "AT ").trimmed()

        assertEquals(" LU", trimmed.billingCountry)
        assertEquals("AT ", trimmed.deliveryCountry)
    }

    @Test
    fun `boundary - whitespace-only lines trim to empty strings, not null`() {
        val trimmed = RecipientAddressInput("   ", "\t", " ", deliveryStreet = "  ").trimmed()

        assertEquals("", trimmed.billingStreet)
        assertEquals("", trimmed.billingPostalCode)
        assertEquals("", trimmed.billingCity)
        assertEquals("", trimmed.deliveryStreet)
    }

    @Test
    fun `boundary - an already trimmed input is equal to its trimmed copy`() {
        val input = RecipientAddressInput("Weg 1", "12345", "Berlin", "DE", "Lagerweg 2", "20095", "Hamburg", "DE")

        assertEquals(input, input.trimmed())
        assertNotEquals(input, input.copy(deliveryStreet = null))
    }
}
