package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GenerationJobStatusTest {

    @Test
    fun `statuses include expected ones`() {
        val names = GenerationJobStatus.values().map { it.name }
        assertTrue(names.containsAll(listOf("PENDING", "DELEGATED", "RUNNING", "COMPLETED", "FAILED")))
    }
}
