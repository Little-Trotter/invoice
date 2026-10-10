package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.infrastructure.export.workbook.trip.TripDetailSheetWriter
import de.littletrotter.infrastructure.export.workbook.trip.TripExpenseSheetWriter
import de.littletrotter.shared.Money
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SheetWriterTest {

    @Test
    fun `header writes labelled cells with the header style`() {
        val bytes = writeWorkbook { writer ->
            val sheet = writer.sheet("Sheet")
            sheet.header(listOf("First", "Second"), listOf(10, 20))
        }
        readWorkbook(bytes).use { wb ->
            val row = wb.getSheetAt(0).getRow(0)
            assertThat(row.getCell(0).stringCellValue).isEqualTo("First")
            assertThat(row.getCell(1).stringCellValue).isEqualTo("Second")
            assertThat(row.getCell(0).cellStyle.font.bold).isTrue()
        }
    }

    @Test
    fun `header row height is fixed at two lines of 14pt, 36 points`() {
        val bytes = writeWorkbook { writer -> writer.sheet("Sheet").header(listOf("A", "B"), listOf(8, 8)) }
        readWorkbook(bytes).use { wb ->
            assertThat(wb.getSheetAt(0).getRow(0).heightInPoints).isEqualTo(36f)
        }
    }

    @Test
    fun `header rejects a columnWidths list of the wrong length`() {
        assertThatThrownBy { writeWorkbook { writer -> writer.sheet("Sheet").header(listOf("A", "B", "C"), listOf(8, 8)) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { writeWorkbook { writer -> writer.sheet("Sheet").header(listOf("A"), listOf(8, 8)) } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `zero data rows still freeze the header and autofilter over it alone`() {
        val bytes = writeWorkbook { writer ->
            writer.sheet("Sheet").header(listOf("A", "B"), listOf(8, 8))
        }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheetAt(0)
            val pane = sheet.paneInformation
            assertThat(pane.isFreezePane).isTrue()
            assertThat(pane.horizontalSplitPosition).isEqualTo(1)
            val ref = (sheet as XSSFSheet).ctWorksheet.autoFilter.ref
            assertThat(ref).isEqualTo("A1:B1")
        }
    }

    @Test
    fun `autofilter spans the header and every data row`() {
        val bytes = writeWorkbook { writer ->
            val sheet = writer.sheet("Sheet")
            sheet.header(listOf("A", "B", "C"), listOf(8, 8, 8))
            sheet.row().text("1")
            sheet.row().text("2")
        }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheetAt(0) as XSSFSheet
            assertThat(sheet.ctWorksheet.autoFilter.ref).isEqualTo("A1:C3")
        }
    }

    @Test
    fun `column widths are applied from the header declaration, in characters, times 256`() {
        val bytes = writeWorkbook { writer ->
            writer.sheet("Sheet").header(listOf("A", "B"), listOf(10, 20))
        }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheetAt(0)
            assertThat(sheet.getColumnWidth(0)).isEqualTo(10 * 256)
            assertThat(sheet.getColumnWidth(1)).isEqualTo(20 * 256)
        }
    }

    @Test
    fun `columnWidths alone applies widths without a header, freeze pane or autofilter`() {
        val bytes = writeWorkbook { writer -> writer.sheet("Sheet").columnWidths(listOf(40, 110)) }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheetAt(0)
            assertThat(sheet.getColumnWidth(0)).isEqualTo(40 * 256)
            assertThat(sheet.getColumnWidth(1)).isEqualTo(110 * 256)
            assertThat(sheet.paneInformation).isNull()
        }
    }

    @Test
    fun `subHeader writes a header-styled row without freeze pane or autofilter`() {
        val bytes = writeWorkbook { writer ->
            val sheet = writer.sheet("Sheet")
            sheet.columnWidths(listOf(40, 110))
            sheet.noteRow().text("Title")
            sheet.subHeader(listOf("Field", "Value"))
        }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheetAt(0)
            val subHeaderRow = sheet.getRow(1)
            assertThat(subHeaderRow.getCell(0).stringCellValue).isEqualTo("Field")
            assertThat(subHeaderRow.getCell(1).stringCellValue).isEqualTo("Value")
            assertThat(subHeaderRow.getCell(0).cellStyle.font.bold).isTrue()
            assertThat(sheet.paneInformation).isNull()
        }
    }

    @Test
    fun `recordCount counts data rows but not the header`() {
        var count = 0L
        writeWorkbook { writer ->
            val sheet = writer.sheet("Sheet")
            sheet.header(listOf("A"), listOf(8))
            sheet.row().text("1")
            sheet.row().text("2")
            sheet.row().text("3")
            count = sheet.recordCount
        }
        assertThat(count).isEqualTo(3L)
    }

    @Test
    fun `noteRow writes a row without counting it toward recordCount`() {
        var count = 0L
        val bytes = writeWorkbook { writer ->
            val sheet = writer.sheet("Sheet")
            sheet.header(listOf("A"), listOf(8))
            sheet.noteRow().text("keine Eintraege")
            count = sheet.recordCount
        }
        assertThat(count).isEqualTo(0L)
        readWorkbook(bytes).use { wb ->
            assertThat(wb.getSheetAt(0).getRow(1).getCell(0).stringCellValue).isEqualTo("keine Eintraege")
        }
    }

    @Test
    fun `sheet name is sanitised via WorkbookWriter`() {
        val bytes = writeWorkbook { writer ->
            writer.sheet("Bad:Name/With*Forbidden[Chars]AndWayTooManyCharactersInIt").header(listOf("A"), listOf(8))
        }
        readWorkbook(bytes).use { wb ->
            val name = wb.getSheetName(0)
            assertThat(name).hasSizeLessThanOrEqualTo(31)
            assertThat(name.none { it in charArrayOf(':', '\\', '/', '?', '*', '[', ']') }).isTrue()
        }
    }

    @Test
    fun `a sheet without a header does not fail to finalise`() {
        val bytes = writeWorkbook { writer -> writer.sheet("Empty") }
        readWorkbook(bytes).use { wb ->
            assertThat(wb.getSheetAt(0).paneInformation).isNull()
        }
    }

    // ── No declared width in any real sheet writer's own COLUMN_WIDTHS falls below 8 ────────────

    @Test
    fun `no width declared by any sheet writer falls below the FLAG class floor of 8`() {
        assertThat(InvoiceHeaderSheetWriter.COLUMN_WIDTHS).hasSize(InvoiceHeaderSheetWriter.COLUMN_COUNT).allMatch { it >= ColumnWidths.FLAG }
        assertThat(InvoicePositionSheetWriter.COLUMN_WIDTHS).hasSize(InvoicePositionSheetWriter.COLUMN_COUNT).allMatch { it >= ColumnWidths.FLAG }
        assertThat(InvoiceJournalSheetWriter.COLUMN_WIDTHS).hasSize(InvoiceJournalSheetWriter.COLUMN_COUNT).allMatch { it >= ColumnWidths.FLAG }
        assertThat(listOf(ColumnWidths.EXPORT_FIELD, ColumnWidths.EXPORT_VALUE)).allMatch { it >= ColumnWidths.FLAG }
        // The trip workbook's own two sheet writers.
        assertThat(TripDetailSheetWriter.COLUMN_WIDTHS).hasSize(2).allMatch { it >= ColumnWidths.FLAG }
        assertThat(TripExpenseSheetWriter.COLUMN_WIDTHS).hasSize(TripExpenseSheetWriter.COLUMN_COUNT).allMatch { it >= ColumnWidths.FLAG }
    }

    // ── markDeviationCell: a single-cell R4 highlight, not a whole-column rule ──────────────────

    @Test
    fun `markDeviationCell applies the deviation rule to exactly one cell, not the whole column`() {
        val bytes = writeWorkbook { writer ->
            val sheet = writer.sheet("Sheet")
            sheet.header(listOf("A", "B"), listOf(8, 8))
            sheet.row().text("x").money(Money.of("500.00"))
            sheet.row().text("y").money(Money.of("64.00"))
            sheet.markDeviationCell(2, 1)
        }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheetAt(0) as XSSFSheet
            assertThat(sheet.sheetConditionalFormatting.numConditionalFormattings).isEqualTo(1)
            val region = sheet.sheetConditionalFormatting.getConditionalFormattingAt(0).formattingRanges.single()
            assertThat(region.firstRow).isEqualTo(2)
            assertThat(region.lastRow).isEqualTo(2)
            assertThat(region.firstColumn).isEqualTo(1)
            assertThat(region.lastColumn).isEqualTo(1)
        }
    }
}
