package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.application.export.InvoiceJournalExportRow
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import org.apache.poi.ss.usermodel.CellType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class InvoiceJournalSheetWriterTest {

    private val columnHeaders = (0 until InvoiceJournalSheetWriter.COLUMN_COUNT).map { "Col$it" }

    private val labels = InvoiceJournalSheetLabels(
        columnHeaders = columnHeaders,
        eventLabels = mapOf(
            InvoiceAuditEvent.INVOICE_CREATED to "Rechnung angelegt",
            InvoiceAuditEvent.INVOICE_UPDATED to "Rechnung geändert",
            InvoiceAuditEvent.INVOICE_DOCUMENT_CREATED to "Dokument angelegt",
        ),
        noEntriesNoteLabel = "Für diese Rechnung liegen keine Journal-Einträge vor.",
        actionLabels = mapOf(
            InvoiceAuditAction.STATUS_CHANGED to "Statuswechsel",
            InvoiceAuditAction.ITEM_ADDED to "Position hinzugefügt",
        ),
    )

    private fun baseRow(invoiceId: Long = 1L, seq: Long = 1L) = InvoiceJournalExportRow(invoiceId = invoiceId, seq = seq)

    @Test
    fun `writes the header row from the supplied labels and creates exactly one row per entry`() {
        val bytes = writeWorkbook { writer -> InvoiceJournalSheetWriter.write(writer, labels, listOf(baseRow(), baseRow(seq = 2L))) }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheet(InvoiceJournalSheetWriter.SHEET_NAME)
            assertThat(sheet).isNotNull
            assertThat(sheet.getRow(0).getCell(0).stringCellValue).isEqualTo("Col0")
            assertThat(sheet.getRow(0).getCell(10).stringCellValue).isEqualTo("Col10")
            assertThat(sheet.getRow(0).getCell(12).stringCellValue).isEqualTo("Col12")
            assertThat(sheet.lastRowNum).isEqualTo(2)
        }
    }

    @Test
    fun `rejects a label set whose column headers do not match the sheet's column count`() {
        val badLabels = labels.copy(columnHeaders = listOf("only one"))
        assertThatThrownBy { writeWorkbook { writer -> InvoiceJournalSheetWriter.write(writer, badLabels, emptyList()) } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `writes every typed cell of a fully populated row with the right CellType, value and format`() {
        val row = InvoiceJournalExportRow(
            invoiceId = 7L,
            invoiceNumber = "RE-2026-05-00001",
            seq = 3L,
            occurredAt = OffsetDateTime.parse("2026-05-01T10:15:30+02:00"),
            event = InvoiceAuditEvent.INVOICE_UPDATED,
            actorName = "Alice",
            actor = "oidc-sub-1",
            detail = "free_text: a → b",
            documentSha256 = null,
            entryHash = "00abc123",
            prevHash = "00def456",
        )

        val bytes = writeWorkbook { writer -> InvoiceJournalSheetWriter.write(writer, labels, listOf(row)) }

        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoiceJournalSheetWriter.SHEET_NAME).getRow(1)

            assertThat(dataRow.getCell(0).cellType).isEqualTo(CellType.NUMERIC)
            assertThat(dataRow.getCell(0).numericCellValue).isEqualTo(7.0)
            assertThat(dataRow.getCell(1).stringCellValue).isEqualTo("RE-2026-05-00001")
            assertThat(dataRow.getCell(2).cellType).isEqualTo(CellType.NUMERIC)
            assertThat(dataRow.getCell(2).numericCellValue).isEqualTo(3.0)
            assertThat(dataRow.getCell(2).cellStyle.dataFormatString).isEqualTo(CellStyles.INTEGER_FORMAT)
            assertThat(dataRow.getCell(3).cellType).isEqualTo(CellType.NUMERIC)
            assertThat(dataRow.getCell(3).localDateTimeCellValue).isEqualTo(OffsetDateTime.parse("2026-05-01T10:15:30+02:00").toLocalDateTime())
            assertThat(dataRow.getCell(3).cellStyle.dataFormatString).isEqualTo(CellStyles.DATE_TIME_FORMAT)
            assertThat(dataRow.getCell(4).stringCellValue).isEqualTo("Rechnung geändert")
            assertThat(dataRow.getCell(5).stringCellValue).isEqualTo("Alice")
            assertThat(dataRow.getCell(6).stringCellValue).isEqualTo("oidc-sub-1")
            assertThat(dataRow.getCell(7).stringCellValue).isEqualTo("free_text: a → b")
            assertThat(dataRow.getCell(8).cellType).isEqualTo(CellType.BLANK)
            assertThat(dataRow.getCell(9).cellType).isEqualTo(CellType.STRING)
            assertThat(dataRow.getCell(9).stringCellValue).isEqualTo("00abc123")
            assertThat(dataRow.getCell(10).cellType).isEqualTo(CellType.STRING)
            assertThat(dataRow.getCell(10).stringCellValue).isEqualTo("00def456")
        }
    }

    @Test
    fun `a blank invoice number writes a genuinely blank cell, not an empty string`() {
        val row = baseRow().copy(invoiceNumber = "")
        val bytes = writeWorkbook { writer -> InvoiceJournalSheetWriter.write(writer, labels, listOf(row)) }
        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoiceJournalSheetWriter.SHEET_NAME).getRow(1)
            assertThat(dataRow.getCell(1).cellType).isEqualTo(CellType.BLANK)
        }
    }

    @Test
    fun `a null actorName falls back to actor, but the actor column itself is unaffected`() {
        val row = baseRow().copy(actorName = null, actor = "oidc-sub-9")
        val bytes = writeWorkbook { writer -> InvoiceJournalSheetWriter.write(writer, labels, listOf(row)) }
        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoiceJournalSheetWriter.SHEET_NAME).getRow(1)
            assertThat(dataRow.getCell(5).stringCellValue).isEqualTo("oidc-sub-9")
            assertThat(dataRow.getCell(6).stringCellValue).isEqualTo("oidc-sub-9")
        }
    }

    @Test
    fun `an event value missing from the supplied label map falls back to its own enum name, never a raw ordinal`() {
        val row = baseRow().copy(event = InvoiceAuditEvent.INVOICE_ITEM_DELETED)
        val bytes = writeWorkbook { writer -> InvoiceJournalSheetWriter.write(writer, labels, listOf(row)) }
        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoiceJournalSheetWriter.SHEET_NAME).getRow(1)
            assertThat(dataRow.getCell(4).stringCellValue).isEqualTo("INVOICE_ITEM_DELETED")
        }
    }

    @Test
    fun `a hash or checksum starting with leading zeroes is written as text and keeps every digit`() {
        val row = baseRow().copy(entryHash = "0000abcd", prevHash = "0001beef", documentSha256 = "007cafe0".repeat(8))
        val bytes = writeWorkbook { writer -> InvoiceJournalSheetWriter.write(writer, labels, listOf(row)) }
        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoiceJournalSheetWriter.SHEET_NAME).getRow(1)
            assertThat(dataRow.getCell(8).cellType).isEqualTo(CellType.STRING)
            assertThat(dataRow.getCell(8).stringCellValue).startsWith("007cafe0")
            assertThat(dataRow.getCell(9).cellType).isEqualTo(CellType.STRING)
            assertThat(dataRow.getCell(9).stringCellValue).isEqualTo("0000abcd")
            assertThat(dataRow.getCell(10).cellType).isEqualTo(CellType.STRING)
            assertThat(dataRow.getCell(10).stringCellValue).isEqualTo("0001beef")
        }
    }

    @Test
    fun `an empty selection still produces the sheet with its header row and a single note row`() {
        val bytes = writeWorkbook { writer -> InvoiceJournalSheetWriter.write(writer, labels, emptyList()) }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheet(InvoiceJournalSheetWriter.SHEET_NAME)
            assertThat(sheet).isNotNull
            assertThat(sheet.lastRowNum).isEqualTo(1)
            assertThat(sheet.getRow(1).getCell(0).stringCellValue).isEqualTo(labels.noEntriesNoteLabel)
        }
    }

    @Test
    fun `the note row does not count toward the sheet's own recordCount`() {
        var recordCount = 0L
        writeWorkbook { writer -> recordCount = InvoiceJournalSheetWriter.write(writer, labels, emptyList()).recordCount }
        assertThat(recordCount).isEqualTo(0L)
    }

    @Test
    fun `two invoices whose entries interleave in seq order are written exactly as handed in, not regrouped`() {
        val rows = listOf(baseRow(invoiceId = 1L, seq = 1L), baseRow(invoiceId = 2L, seq = 2L), baseRow(invoiceId = 1L, seq = 3L))
        val bytes = writeWorkbook { writer -> InvoiceJournalSheetWriter.write(writer, labels, rows) }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheet(InvoiceJournalSheetWriter.SHEET_NAME)
            assertThat(sheet.getRow(1).getCell(0).numericCellValue).isEqualTo(1.0)
            assertThat(sheet.getRow(1).getCell(2).numericCellValue).isEqualTo(1.0)
            assertThat(sheet.getRow(2).getCell(0).numericCellValue).isEqualTo(2.0)
            assertThat(sheet.getRow(2).getCell(2).numericCellValue).isEqualTo(2.0)
            assertThat(sheet.getRow(3).getCell(0).numericCellValue).isEqualTo(1.0)
            assertThat(sheet.getRow(3).getCell(2).numericCellValue).isEqualTo(3.0)
        }
    }

    @Test
    fun `a detail value far beyond the OOXML cell text limit is truncated and the workbook still opens`() {
        val hugeDetail = "x".repeat(40_000)
        val row = baseRow().copy(detail = hugeDetail)
        val bytes = writeWorkbook { writer -> InvoiceJournalSheetWriter.write(writer, labels, listOf(row)) }
        readWorkbook(bytes).use { wb ->
            val value = wb.getSheet(InvoiceJournalSheetWriter.SHEET_NAME).getRow(1).getCell(7).stringCellValue
            assertThat(value.length).isLessThan(hugeDetail.length)
            assertThat(value.length).isLessThanOrEqualTo(TextGuard.MAX_LENGTH)
        }
    }

    // ── "Vorgang"/"Vorgangs-Nr." appended after the eleven pre-existing columns ─────────

    @Test
    fun `Vorgang and Vorgangs-Nr are appended last, after every pre-existing column`() {
        val row = baseRow().copy(action = InvoiceAuditAction.STATUS_CHANGED, businessTx = 4242L)
        val bytes = writeWorkbook { writer -> InvoiceJournalSheetWriter.write(writer, labels, listOf(row)) }
        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoiceJournalSheetWriter.SHEET_NAME).getRow(1)
            assertThat(dataRow.getCell(11).stringCellValue).isEqualTo("Statuswechsel")
            assertThat(dataRow.getCell(12).cellType).isEqualTo(CellType.NUMERIC)
            assertThat(dataRow.getCell(12).numericCellValue).isEqualTo(4242.0)
        }
    }

    @Test
    fun `an action value missing from the supplied label map falls back to its own enum name, never a raw ordinal`() {
        val row = baseRow().copy(action = InvoiceAuditAction.RECEIPT_DETACHED)
        val bytes = writeWorkbook { writer -> InvoiceJournalSheetWriter.write(writer, labels, listOf(row)) }
        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoiceJournalSheetWriter.SHEET_NAME).getRow(1)
            assertThat(dataRow.getCell(11).stringCellValue).isEqualTo("RECEIPT_DETACHED")
        }
    }

    // ── column widths declared next to the column catalogue ───────────────────────────

    @Test
    fun `COLUMN_WIDTHS has exactly one entry per column`() {
        assertThat(InvoiceJournalSheetWriter.COLUMN_WIDTHS).hasSize(InvoiceJournalSheetWriter.COLUMN_COUNT)
    }

    @Test
    fun `the timestamp column is DATE_TIME and the detail column is TEXT and wraps`() {
        assertThat(InvoiceJournalSheetWriter.COLUMN_WIDTHS[3]).isEqualTo(ColumnWidths.DATE_TIME)
        assertThat(InvoiceJournalSheetWriter.COLUMN_WIDTHS[7]).isEqualTo(ColumnWidths.TEXT)

        val bytes = writeWorkbook { writer -> InvoiceJournalSheetWriter.write(writer, labels, listOf(baseRow().copy(detail = "a long free-text detail"))) }
        readWorkbook(bytes).use { wb ->
            val cell = wb.getSheet(InvoiceJournalSheetWriter.SHEET_NAME).getRow(1).getCell(7)
            assertThat(cell.cellStyle.wrapText).isTrue()
        }
    }
}
