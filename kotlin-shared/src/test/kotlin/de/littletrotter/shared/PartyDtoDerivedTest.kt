package de.littletrotter.shared

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.assertj.core.api.Assertions.assertThat

/**
 * Tests for party-related DTOs: VatRegistrationDto and ShipToDto
 */
class PartyDtoDerivedTest {

    @Test
    fun `VatRegistrationDto constructor sets country and vatId`() {
        val dto = VatRegistrationDto("DE", "123456789")

        assertEquals("DE", dto.country)
        assertEquals("123456789", dto.vatId)
    }

    @Test
    fun `VatRegistrationDto equality`() {
        val dto1 = VatRegistrationDto("DE", "123")
        val dto2 = VatRegistrationDto("DE", "123")
        val dto3 = VatRegistrationDto("FR", "123")

        assertThat(dto1).isEqualTo(dto2)
        assertThat(dto1).isNotEqualTo(dto3)
    }

    @Test
    fun `VatRegistrationDto hash code consistent with equality`() {
        val dto1 = VatRegistrationDto("DE", "123")
        val dto2 = VatRegistrationDto("DE", "123")

        assertEquals(dto1.hashCode(), dto2.hashCode())
    }

    @Test
    fun `VatRegistrationDto copy function`() {
        val original = VatRegistrationDto("DE", "123")
        val copied = original.copy()

        assertEquals(original, copied)
        assertThat(original).isNotSameAs(copied)
    }

    @Test
    fun `VatRegistrationDto copy with modified fields`() {
        val original = VatRegistrationDto("DE", "123")
        val modified = original.copy(country = "FR")

        assertEquals("FR", modified.country)
        assertEquals("123", modified.vatId)
    }

    @Test
    fun `VatRegistrationDto toString is defined`() {
        val dto = VatRegistrationDto("DE", "123")
        val str = dto.toString()

        assertThat(str).contains("DE").contains("123")
    }

    @Test
    fun `ShipToDto with all fields`() {
        val dto = ShipToDto(
            name = "Company Inc.",
            street = "123 Main St",
            city = "Berlin",
            postalCode = "10115",
            country = "DE"
        )

        assertEquals("Company Inc.", dto.name)
        assertEquals("123 Main St", dto.street)
        assertEquals("Berlin", dto.city)
        assertEquals("10115", dto.postalCode)
        assertEquals("DE", dto.country)
    }

    @Test
    fun `ShipToDto with default values`() {
        val dto = ShipToDto(null, null, null, null, null)

        assertThat(dto.name).isNull()
        assertThat(dto.street).isNull()
        assertThat(dto.city).isNull()
        assertThat(dto.postalCode).isNull()
        assertThat(dto.country).isNull()
    }

    @Test
    fun `ShipToDto equality`() {
        val dto1 = ShipToDto("Company", "123 St", "10115", "Berlin", "DE")
        val dto2 = ShipToDto("Company", "123 St", "10115", "Berlin", "DE")
        val dto3 = ShipToDto("Other Co", "123 St", "10115", "Berlin", "DE")

        assertThat(dto1).isEqualTo(dto2)
        assertThat(dto1).isNotEqualTo(dto3)
    }

    @Test
    fun `ShipToDto copy function`() {
        val original = ShipToDto("Company", "123 St", "10115", "Berlin", "DE")
        val copied = original.copy()

        assertEquals(original, copied)
        assertThat(original).isNotSameAs(copied)
    }

    @Test
    fun `ShipToDto copy with modified fields`() {
        val original = ShipToDto("Company", "123 St", "10115", "Berlin", "DE")
        val modified = original.copy(city = "Frankfurt")

        assertEquals("Company", modified.name)
        assertEquals("Frankfurt", modified.city)
        assertEquals("DE", modified.country)
    }

    @Test
    fun `ShipToDto partial fields`() {
        val dto = ShipToDto(name = "Company", street = "123 St", city = null, postalCode = null, country = "DE")

        assertEquals("Company", dto.name)
        assertEquals("123 St", dto.street)
        assertThat(dto.city).isNull()
        assertEquals("DE", dto.country)
    }

    @Test
    fun `ShipToDto toString is defined`() {
        val dto = ShipToDto("Company", "123 St", "Berlin", "10115", "DE")
        val str = dto.toString()

        assertThat(str).contains("Company").contains("Berlin").contains("DE")
    }
}
