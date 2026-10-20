package de.littletrotter.infrastructure.export.csv

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CsvFormulaGuardTest {

    @Test
    fun `a value starting with an equals sign is prefixed with an apostrophe`() {
        assertThat(CsvFormulaGuard.neutralize("=1+1")).isEqualTo("'=1+1")
    }

    @Test
    fun `a value starting with a plus sign is prefixed with an apostrophe`() {
        assertThat(CsvFormulaGuard.neutralize("+1")).isEqualTo("'+1")
    }

    @Test
    fun `a value starting with a minus sign is prefixed with an apostrophe`() {
        assertThat(CsvFormulaGuard.neutralize("-1")).isEqualTo("'-1")
    }

    @Test
    fun `a value starting with an at sign is prefixed with an apostrophe`() {
        assertThat(CsvFormulaGuard.neutralize("@SUM(A1)")).isEqualTo("'@SUM(A1)")
    }

    @Test
    fun `a value starting with a tab is prefixed with an apostrophe`() {
        assertThat(CsvFormulaGuard.neutralize("\t=x")).isEqualTo("'\t=x")
    }

    @Test
    fun `a value starting with a carriage return is prefixed with an apostrophe`() {
        assertThat(CsvFormulaGuard.neutralize("\r=x")).isEqualTo("'\r=x")
    }

    @Test
    fun `a minus that is not the leading character is left untouched`() {
        assertThat(CsvFormulaGuard.neutralize("Rabatt -10 %")).isEqualTo("Rabatt -10 %")
    }

    @Test
    fun `ordinary text with no trigger character is left untouched`() {
        assertThat(CsvFormulaGuard.neutralize("Ersatzteil")).isEqualTo("Ersatzteil")
    }

    @Test
    fun `an empty string is left untouched`() {
        assertThat(CsvFormulaGuard.neutralize("")).isEqualTo("")
    }
}
