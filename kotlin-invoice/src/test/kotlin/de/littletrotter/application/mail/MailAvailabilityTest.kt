package de.littletrotter.application.mail

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The deployment switch. */
@DisplayName("MailAvailability")
class MailAvailabilityTest {

    @Test
    @DisplayName("availability is exactly the configured flag")
    fun availabilityIsTheFlag() {
        assertTrue(MailAvailability(true).enabled)
        assertFalse(MailAvailability(false).enabled)
    }
}
