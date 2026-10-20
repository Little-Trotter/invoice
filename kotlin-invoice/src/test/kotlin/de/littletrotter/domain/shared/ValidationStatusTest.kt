package de.littletrotter.domain.shared

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ValidationStatusTest {

    @Test
    fun `enum values present`() {
        val vals = ValidationStatus.values().map { it.name }
        assertTrue(vals.containsAll(listOf("PENDING", "VALID", "INVALID", "UNAVAILABLE", "NOT_VALIDATED")))
    }
}
