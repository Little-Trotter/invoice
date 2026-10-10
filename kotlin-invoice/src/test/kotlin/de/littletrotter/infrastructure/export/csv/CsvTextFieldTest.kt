package de.littletrotter.infrastructure.export.csv

import de.littletrotter.infrastructure.export.workbook.TextGuard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CsvTextFieldTest {

    @Test
    fun `null renders as an empty field, never the string null`() {
        assertThat(CsvTextField.render(null)).isEqualTo("")
    }

    @Test
    fun `an empty string renders as an empty field`() {
        assertThat(CsvTextField.render("")).isEqualTo("")
    }

    @Test
    fun `ordinary text passes through unchanged`() {
        assertThat(CsvTextField.render("Ersatzteil")).isEqualTo("Ersatzteil")
    }

    @Test
    fun `umlauts, ß, an emoji and a Chinese character all pass through unchanged`() {
        assertThat(CsvTextField.render("Prüfung süß 🎉 你好")).isEqualTo("Prüfung süß 🎉 你好")
    }

    @Test
    fun `formula injection is neutralized before quoting is decided`() {
        // "=1+1" alone needs no quoting (no delimiter/quote/line-break), only the apostrophe prefix.
        assertThat(CsvTextField.render("=1+1")).isEqualTo("'=1+1")
    }

    @Test
    fun `a value that is both an injection risk and contains the delimiter is neutralized then quoted`() {
        assertThat(CsvTextField.render("=1+1;2")).isEqualTo("\"'=1+1;2\"")
    }

    @Test
    fun `a non-leading minus is neither neutralized nor quoted`() {
        assertThat(CsvTextField.render("Rabatt -10 %")).isEqualTo("Rabatt -10 %")
    }

    @Test
    fun `a value beyond the OOXML cell text limit is truncated exactly like the XLSX text cell`() {
        val huge = "x".repeat(40_000)
        val rendered = CsvTextField.render(huge)
        assertThat(rendered.length).isLessThan(huge.length)
        assertThat(rendered.length).isLessThanOrEqualTo(TextGuard.MAX_LENGTH)
    }

    @Test
    fun `a control character other than tab or newline is stripped, matching the XLSX text cell`() {
        assertThat(CsvTextField.render("AB")).isEqualTo("AB")
    }
}
