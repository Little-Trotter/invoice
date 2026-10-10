package de.littletrotter.shared

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class PartyDtoTest {
    @Test
    fun `construct party with all fields`() {
        val p = PartyDto(
            name = "Acme",
            street = "Main",
            postalCode = "12345",
            city = "Town",
            country = "DE",
            vatId = "DE123",
            iban = "DEIBAN",
            bic = "BIC",
            taxNumber = "TAX"
        )
        assertThat(p.name).isEqualTo("Acme")
        assertThat(p.city).isEqualTo("Town")
        assertThat(p.iban).isEqualTo("DEIBAN")
    }
}
