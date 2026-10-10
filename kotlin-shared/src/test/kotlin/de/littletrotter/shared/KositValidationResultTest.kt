package de.littletrotter.shared

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class KositValidationResultTest {
    @Test
    fun `data class equality and defaults`() {
        val msg = ValidationMessage("ERROR", "bad", "loc")
        val r1 = KositValidationResult(KositOutcome.INVALID, "sum", listOf(msg), "v1", "ref")
        val r2 = r1.copy(validatorVersion = "v1")

        assertThat(r1).isEqualTo(r2)
        assertThat(r1.summary).isEqualTo("sum")
        assertThat(r1.details).hasSize(1)
    }

    @Test
    fun `empty defaults`() {
        val r = KositValidationResult(KositOutcome.VALID)
        assertThat(r.details).isEmpty()
        assertThat(r.summary).isNull()
    }
}
