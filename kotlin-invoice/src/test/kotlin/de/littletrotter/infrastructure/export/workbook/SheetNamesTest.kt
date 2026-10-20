package de.littletrotter.infrastructure.export.workbook

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SheetNamesTest {

    @Test
    fun `a short, safe name passes through unchanged`() {
        assertThat(SheetNames.sanitize("Rechnungen")).isEqualTo("Rechnungen")
    }

    @Test
    fun `forbidden characters are replaced`() {
        assertThat(SheetNames.sanitize("A:B\\C/D?E*F[G]H")).isEqualTo("A_B_C_D_E_F_G_H")
    }

    @Test
    fun `names longer than 31 characters are truncated`() {
        val name = "This name is definitely longer than thirty-one characters"
        val sanitized = SheetNames.sanitize(name)
        assertThat(sanitized).hasSize(31)
        assertThat(sanitized).isEqualTo(name.take(31))
    }

    @Test
    fun `a name of exactly 31 characters is not truncated`() {
        val name = "a".repeat(31)
        assertThat(SheetNames.sanitize(name)).isEqualTo(name)
    }
}
