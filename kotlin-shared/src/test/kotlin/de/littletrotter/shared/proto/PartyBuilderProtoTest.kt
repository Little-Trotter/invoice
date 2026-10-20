package de.littletrotter.shared.proto

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class PartyBuilderProtoTest {
    @Test
    fun `party builder and defaults`() {
        val p = Party.newBuilder()
            .setName("X Corp")
            .setStreet("Street 1")
            .setPostalCode("00001")
            .setCity("City")
            .setCountry("DE")
            .setVatId("DEVAT")
            .setIban("IBAN")
            .setBic("BIC")
            .setTaxNumber("TAX")
            .build()

        assertThat(p.name).isEqualTo("X Corp")
        assertThat(p.city).isEqualTo("City")
        assertThat(p.iban).isEqualTo("IBAN")
    }
}
