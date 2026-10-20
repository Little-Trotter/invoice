package de.littletrotter.infrastructure.export.workbook

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WorkbookLimitsTest {

    @Test
    fun `the last valid row index does not throw`() {
        assertThatCode {
            WorkbookLimits.requireRowIndexInBounds(WorkbookLimits.MAX_ROWS_PER_SHEET - 1, "Sheet")
        }.doesNotThrowAnyException()
    }

    @Test
    fun `the first out-of-bounds row index throws a named exception`() {
        assertThatThrownBy {
            WorkbookLimits.requireRowIndexInBounds(WorkbookLimits.MAX_ROWS_PER_SHEET, "Sheet")
        }.isInstanceOf(SheetRowLimitExceededException::class.java)
            .hasMessageContaining("Sheet")
            .hasMessageContaining("${WorkbookLimits.MAX_ROWS_PER_SHEET}")
    }

    @Test
    fun `the exception names the sheet and the attempted row index`() {
        val exception = SheetRowLimitExceededException("Positionen", 2_000_000)
        assertThat(exception.message).contains("Positionen").contains("2000000")
    }
}
