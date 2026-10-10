package de.littletrotter.infrastructure.export.csv

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CsvFieldEscaperTest {

    @Test
    fun `a value with no special character is left unquoted`() {
        assertThat(CsvFieldEscaper.escape("Ersatzteil")).isEqualTo("Ersatzteil")
    }

    @Test
    fun `a value containing the delimiter is quoted`() {
        assertThat(CsvFieldEscaper.escape("Ersatzteil; Zubehoer")).isEqualTo("\"Ersatzteil; Zubehoer\"")
    }

    @Test
    fun `a value containing a double quote is quoted and the quote is doubled`() {
        assertThat(CsvFieldEscaper.escape("12\" Bildschirm")).isEqualTo("\"12\"\" Bildschirm\"")
    }

    @Test
    fun `a value containing both the delimiter and a double quote is quoted with the quote doubled`() {
        assertThat(CsvFieldEscaper.escape("A;B \"C\"")).isEqualTo("\"A;B \"\"C\"\"\"")
    }

    @Test
    fun `a value containing a line break is quoted`() {
        assertThat(CsvFieldEscaper.escape("Zeile 1\nZeile 2")).isEqualTo("\"Zeile 1\nZeile 2\"")
    }

    @Test
    fun `a value containing a carriage return is quoted`() {
        assertThat(CsvFieldEscaper.escape("Zeile 1\rZeile 2")).isEqualTo("\"Zeile 1\rZeile 2\"")
    }

    @Test
    fun `an empty value is left unquoted`() {
        assertThat(CsvFieldEscaper.escape("")).isEqualTo("")
    }
}
