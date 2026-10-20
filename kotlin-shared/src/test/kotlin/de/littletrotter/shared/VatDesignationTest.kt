package de.littletrotter.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VatDesignationTest {

    @Test
    fun `a null country has no designation`() {
        assertThat(registrationVatDesignation(null)).isNull()
    }

    @Test
    fun `a known country code returns its official designation`() {
        assertThat(registrationVatDesignation("AT")).isEqualTo("UID-Nr.")
    }

    @Test
    fun `a known country code is matched case-insensitively`() {
        assertThat(registrationVatDesignation("at")).isEqualTo("UID-Nr.")
    }

    @Test
    fun `an unknown country code has no designation`() {
        assertThat(registrationVatDesignation("US")).isNull()
    }
}
