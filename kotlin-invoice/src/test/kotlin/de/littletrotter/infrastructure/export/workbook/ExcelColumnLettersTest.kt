package de.littletrotter.infrastructure.export.workbook

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExcelColumnLettersTest {

    @Test
    fun `single-letter columns match Excel's own A to Z range`() {
        assertThat(ExcelColumnLetters.of(0)).isEqualTo("A")
        assertThat(ExcelColumnLetters.of(25)).isEqualTo("Z")
    }

    @Test
    fun `the first double-letter column follows Z`() {
        assertThat(ExcelColumnLetters.of(26)).isEqualTo("AA")
        assertThat(ExcelColumnLetters.of(51)).isEqualTo("AZ")
        assertThat(ExcelColumnLetters.of(52)).isEqualTo("BA")
    }

    @Test
    fun `xls-2's own control-block columns resolve to the letters its formulas rely on`() {
        assertThat(ExcelColumnLetters.of(49)).isEqualTo("AX")
        assertThat(ExcelColumnLetters.of(61)).isEqualTo("BJ")
        assertThat(ExcelColumnLetters.of(69)).isEqualTo("BR")
    }

    @Test
    fun `a negative index is rejected`() {
        assertThatThrownBy { ExcelColumnLetters.of(-1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
