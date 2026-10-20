package de.littletrotter.infrastructure.export.csv

import de.littletrotter.application.export.InvoicePositionExportRow
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.infrastructure.export.workbook.InvoicePositionEnumLabels
import de.littletrotter.infrastructure.export.workbook.InvoicePositionSheetLabels
import de.littletrotter.infrastructure.export.workbook.InvoicePositionSheetWriter
import de.littletrotter.infrastructure.export.workbook.readWorkbook
import de.littletrotter.infrastructure.export.workbook.writeWorkbook
import de.littletrotter.shared.Money
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class InvoicePositionCsvWriterTest {

    private val columnHeaders = (0 until InvoicePositionSheetWriter.COLUMN_COUNT).map { "Col$it" }

    private val labels = InvoicePositionSheetLabels(
        columnHeaders = columnHeaders,
        enumLabels = InvoicePositionEnumLabels(
            category = mapOf(
                InvoiceItemCategory.SERVICE to "Dienstleistung",
                InvoiceItemCategory.PRODUCT to "Produkt",
                InvoiceItemCategory.TRIP to "Dienstreise",
            ),
            vatCategory = mapOf(
                VatCategory.STANDARD to "Regelsteuersatz",
                VatCategory.REVERSE_CHARGE to "Reverse Charge",
                VatCategory.EXEMPT to "Steuerbefreit",
                VatCategory.ZERO_RATED to "Nullsatz",
            ),
            tripExpenseKind = TripExpenseKind.entries.associateWith { it.name },
        ),
        verdictOkLabel = "OK",
        verdictDeviationLabel = "Abweichung",
        verdictTaxRateMissingLabel = "Steuersatz fehlt",
        perDiemKindLabel = "Verpflegungspauschale",
    )

    private fun baseRow(invoiceId: Long = 1L, positionId: String = "1") = InvoicePositionExportRow(invoiceId = invoiceId, positionId = positionId)

    // ── Envelope: BOM, CRLF, delimiter, header, guard clause ────────────────────────────────────

    @Test
    fun `starts with a UTF-8 byte order mark`() {
        val bytes = writeCsv(labels, emptyList())
        assertThat(bytes.copyOfRange(0, BOM.size)).isEqualTo(BOM)
    }

    @Test
    fun `uses CRLF line endings throughout, never a lone LF or CR`() {
        val bytes = writeCsv(labels, listOf(baseRow(), baseRow(positionId = "2")))
        val text = String(bytes, BOM.size, bytes.size - BOM.size, StandardCharsets.UTF_8)
        assertThat(text).endsWith("\r\n")
        assertThat(text.replace("\r\n", "")).doesNotContain("\r").doesNotContain("\n")
    }

    @Test
    fun `an empty selection still writes a header row and the file is not empty`() {
        val bytes = writeCsv(labels, emptyList())
        assertThat(bytes.size).isGreaterThan(BOM.size)
        val records = parseAllRecords(bytes)
        assertThat(records).hasSize(1)
        assertThat(records[0].toList()).isEqualTo(columnHeaders.take(InvoicePositionSheetWriter.DATA_COLUMN_COUNT))
    }

    @Test
    fun `the header row carries exactly the sheet's own first data-column labels, in order`() {
        val bytes = writeCsv(labels, listOf(baseRow()))
        val header = parseAllRecords(bytes)[0]
        assertThat(header.size()).isEqualTo(InvoicePositionSheetWriter.DATA_COLUMN_COUNT)
        assertThat(header.get(0)).isEqualTo("Col0")
        assertThat(header.get(39)).isEqualTo("Col39")
    }

    @Test
    fun `a header label needing RFC 4180 quoting round-trips through a real CSV parser`() {
        val quotingLabels = labels.copy(columnHeaders = columnHeaders.toMutableList().also { it[0] = "Spalte; mit Semikolon" })
        val bytes = writeCsv(quotingLabels, emptyList())
        assertThat(parseAllRecords(bytes)[0].get(0)).isEqualTo("Spalte; mit Semikolon")
    }

    @Test
    fun `rejects a label set whose column headers do not match the sheet's column count`() {
        val badLabels = labels.copy(columnHeaders = listOf("only one"))
        assertThatThrownBy { writeCsv(badLabels, emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── One row, every column ───────────────────────────────────────────────────────────────────

    @Test
    fun `writes every column of a fully populated row in the sheet's own order, decimal comma and DD-MM-YYYY dates`() {
        val row = InvoicePositionExportRow(
            invoiceId = 7L,
            invoiceNumber = "IP-2026-03-00042",
            positionId = "3",
            sortOrder = 2,
            category = InvoiceItemCategory.PRODUCT,
            description = "Ersatzteil",
            itemDescription = "Ersatzteil, 40 Stueck",
            sellerItemIdentifier = "SELLER-1",
            buyerItemIdentifier = "BUYER-1",
            articleNumber = "ART-9",
            quantity = BigDecimal("40.0000"),
            unit = InvoiceItemUnit.PIECE,
            unitPriceNet = Money(BigDecimal("12.50")),
            taxCategoryCode = "S",
            taxRatePercent = BigDecimal("19.00"),
            taxRuleCode = "TR-STD",
            netAmount = Money(BigDecimal("500.00")),
            vatAmount = Money(BigDecimal("95.00")),
            grossAmount = Money(BigDecimal("595.00")),
            servicePeriodFrom = LocalDate.of(2026, 3, 1),
            servicePeriodTo = LocalDate.of(2026, 3, 31),
            costCenter = "CC-1",
            deliveryNoteReference = "LS-2026-042",
            receiptsCount = 2,
            receiptFileNames = "beleg1.pdf, beleg2.pdf",
        )

        val record = parseDataRecords(writeCsv(labels, listOf(row)))[0]

        assertThat(record.get(0)).isEqualTo("7")
        assertThat(record.get(1)).isEqualTo("IP-2026-03-00042")
        assertThat(record.get(2)).isEqualTo("3")
        assertThat(record.get(3)).isEqualTo("2")
        assertThat(record.get(4)).isEqualTo("Produkt")
        assertThat(record.get(5)).isEqualTo("Ersatzteil")
        assertThat(record.get(6)).isEqualTo("Ersatzteil, 40 Stueck")
        assertThat(record.get(7)).isEqualTo("SELLER-1")
        assertThat(record.get(8)).isEqualTo("BUYER-1")
        assertThat(record.get(9)).isEqualTo("ART-9")
        assertThat(record.get(10)).isEqualTo("40,0000")
        assertThat(record.get(11)).isEqualTo("Stk")
        assertThat(record.get(12)).isEqualTo("H87")
        assertThat(record.get(13)).isEqualTo("12,50")
        assertThat(record.get(14)).isEqualTo("S")
        assertThat(record.get(15)).isEqualTo("Regelsteuersatz")
        assertThat(record.get(16)).isEqualTo("19,00")
        assertThat(record.get(17)).isEqualTo("TR-STD")
        assertThat(record.get(18)).isEqualTo("")
        assertThat(record.get(19)).isEqualTo("")
        assertThat(record.get(20)).isEqualTo("")
        assertThat(record.get(21)).isEqualTo("500,00")
        assertThat(record.get(22)).isEqualTo("95,00")
        assertThat(record.get(23)).isEqualTo("595,00")
        assertThat(record.get(24)).isEqualTo("01.03.2026")
        assertThat(record.get(25)).isEqualTo("31.03.2026")
        assertThat(record.get(26)).isEqualTo("CC-1")
        assertThat(record.get(27)).isEqualTo("LS-2026-042")
        assertThat(record.get(28)).isEqualTo("2")
        assertThat(record.get(29)).isEqualTo("beleg1.pdf, beleg2.pdf")
        // A plain (non-TRIP) position row: level 0, every trip/per-diem column blank.
        assertThat(record.get(30)).isEqualTo("0")
        (31..39).forEach { column -> assertThat(record.get(column)).describedAs("column %d", column).isEmpty() }
    }

    @Test
    fun `a blank draft invoice number and every unset optional column render as an empty field, never the string null`() {
        val record = parseDataRecords(writeCsv(labels, listOf(baseRow())))[0]
        assertThat(record.get(1)).isEmpty() // invoiceNumber blank
        assertThat(record.get(6)).isEmpty() // itemDescription
        assertThat(record.get(7)).isEmpty() // sellerItemIdentifier
        assertThat(record.get(8)).isEmpty() // buyerItemIdentifier
        assertThat(record.get(9)).isEmpty() // articleNumber
        assertThat(record.get(14)).isEmpty() // taxCategoryCode
        assertThat(record.get(15)).isEmpty() // taxCategoryLabel
        assertThat(record.get(16)).isEmpty() // taxRatePercent (missing, not zero)
        assertThat(record.get(17)).isEmpty() // taxRuleCode
        assertThat(record.get(24)).isEmpty() // servicePeriodFrom
        assertThat(record.get(25)).isEmpty() // servicePeriodTo
        assertThat(record.get(26)).isEmpty() // costCenter
        assertThat(record.get(27)).isEmpty() // deliveryNoteReference
        assertThat(record.get(29)).isEmpty() // receiptFileNames
        for (field in record) {
            assertThat(field).isNotEqualTo("null")
        }
    }

    @Test
    fun `an unrecognised tax category code writes the raw code but a blank label, never a fallback name`() {
        val row = baseRow().copy(taxCategoryCode = "ZZZ-UNKNOWN")
        val record = parseDataRecords(writeCsv(labels, listOf(row)))[0]
        assertThat(record.get(14)).isEqualTo("ZZZ-UNKNOWN")
        assertThat(record.get(15)).isEmpty()
    }

    @Test
    fun `a discount line - negative unit price and net amount - keeps a single leading minus, no thousands separator`() {
        val row = baseRow().copy(
            unitPriceNet = Money(BigDecimal("-1234.56")),
            netAmount = Money(BigDecimal("-1234.56")),
        )
        val record = parseDataRecords(writeCsv(labels, listOf(row)))[0]
        assertThat(record.get(13)).isEqualTo("-1234,56")
        assertThat(record.get(21)).isEqualTo("-1234,56")
    }

    @Test
    fun `a quantity in the thousands never gains a thousands separator`() {
        val row = baseRow().copy(quantity = BigDecimal("12345.5"))
        val record = parseDataRecords(writeCsv(labels, listOf(row)))[0]
        assertThat(record.get(10)).isEqualTo("12345,5000")
    }

    // ── Formula injection, end to end through the whole writer ──────────────────────────────────

    @Test
    fun `formula-injection descriptions are neutralized end-to-end and readable through a real CSV parser`() {
        val cases = listOf(
            "=1+1" to "'=1+1",
            "+1" to "'+1",
            "-1" to "'-1",
            "@SUM(A1)" to "'@SUM(A1)",
            "\t=x" to "'\t=x",
            // TextGuard (shared with the XLSX text path) strips a lone, non-leading-newline \r
            // before the injection check ever runs; the character that becomes newly leading
            // (here '=') is what actually triggers the guard — the value still never reaches a
            // reader unguarded.
            "\r=x" to "'=x",
        )
        val rows = cases.mapIndexed { index, (input, _) -> baseRow(positionId = "p$index").copy(description = input) }
        val records = parseDataRecords(writeCsv(labels, rows))

        cases.forEachIndexed { index, (input, expected) ->
            assertThat(records[index].get(5)).describedAs("input %s", input).isEqualTo(expected)
        }
    }

    @Test
    fun `a non-leading minus - a discount description - is neither neutralized nor quoted`() {
        val row = baseRow().copy(description = "Rabatt -10 %")
        val record = parseDataRecords(writeCsv(labels, listOf(row)))[0]
        assertThat(record.get(5)).isEqualTo("Rabatt -10 %")
    }

    // ── RFC 4180 round trip through a real CSV parser ────────────────────────────────────────────

    @Test
    fun `special characters round-trip exactly through a real CSV parser`() {
        val values = listOf(
            "Ersatzteil; Zubehoer",
            "12\" Bildschirm",
            "A;B \"C\"",
            "Zeile 1\nZeile 2",
            "Prüfung süß 🎉 你好",
        )
        val rows = values.mapIndexed { index, value -> baseRow(positionId = "p$index").copy(description = value) }
        val records = parseDataRecords(writeCsv(labels, rows))

        values.forEachIndexed { index, expected ->
            assertThat(records[index].get(5)).isEqualTo(expected)
        }
    }

    // ── The bulk CSV excludes a trip's own detail rows ──────────────────────────────────────────

    @Test
    fun `RK-6 - a trip's own detail rows never reach the CSV, only its level-0 position row does`() {
        val tripPosition = baseRow(positionId = "3").copy(category = InvoiceItemCategory.TRIP)
        val detailRow = baseRow(positionId = "3.1").copy(
            category = InvoiceItemCategory.TRIP,
            level = 1,
            parentPositionId = "3",
            detailNumber = "3.1",
            tripKind = TripExpenseKind.HOTEL,
        )
        val records = parseDataRecords(writeCsv(labels, listOf(tripPosition, detailRow)))

        assertThat(records).hasSize(1)
        assertThat(records[0].get(2)).isEqualTo("3")
    }

    // ── Parity with the XLSX position sheet ──────────────────────────────────────────────────────

    @Test
    fun `the same fixture exported as XLSX and CSV agrees on every data cell`() {
        val rows = listOf(
            baseRow(invoiceId = 1L, positionId = "1").copy(
                invoiceNumber = "IP-2026-01-00001",
                sortOrder = 1,
                category = InvoiceItemCategory.SERVICE,
                description = "Beratung",
                itemDescription = "Vor-Ort-Beratung",
                sellerItemIdentifier = "S-1",
                buyerItemIdentifier = "B-1",
                articleNumber = "A-1",
                quantity = BigDecimal("3.5000"),
                unit = InvoiceItemUnit.HOUR,
                unitPriceNet = Money(BigDecimal("120.00")),
                taxCategoryCode = "S",
                taxRatePercent = BigDecimal("19.00"),
                taxRuleCode = "TR-STD",
                netAmount = Money(BigDecimal("420.00")),
                vatAmount = Money(BigDecimal("79.80")),
                grossAmount = Money(BigDecimal("499.80")),
                servicePeriodFrom = LocalDate.of(2026, 1, 1),
                servicePeriodTo = LocalDate.of(2026, 1, 31),
                costCenter = "CC-9",
                deliveryNoteReference = "LS-1",
                receiptsCount = 1,
                receiptFileNames = "beleg.pdf",
            ),
            // discount line: negative price, missing tax rate, no optional fields at all.
            baseRow(invoiceId = 1L, positionId = "2"),
            // reverse-charge, zero rate, unrecognised tax category code.
            baseRow(invoiceId = 2L, positionId = "1").copy(
                taxCategoryCode = "AE",
                taxRatePercent = BigDecimal.ZERO,
                unitPriceNet = Money(BigDecimal("-50.00")),
                netAmount = Money(BigDecimal("-50.00")),
            ),
        )

        val xlsxBytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, rows) }
        val csvRecords = parseDataRecords(writeCsv(labels, rows))

        readWorkbook(xlsxBytes).use { workbook ->
            val sheet = workbook.getSheet(InvoicePositionSheetWriter.SHEET_NAME)
            rows.indices.forEach { rowIndex ->
                val xlsxRow = sheet.getRow(rowIndex + 1)
                val csvRecord = csvRecords[rowIndex]
                columnKinds.forEachIndexed { columnIndex, kind ->
                    val expected = renderXlsxCellAsCsvField(xlsxRow.getCell(columnIndex), kind)
                    assertThat(csvRecord.get(columnIndex))
                        .describedAs("row %d column %d (%s)", rowIndex, columnIndex, kind)
                        .isEqualTo(expected)
                }
            }
        }
    }

    // ── Test infrastructure ─────────────────────────────────────────────────────────────────────

    private fun writeCsv(labels: InvoicePositionSheetLabels, rows: List<InvoicePositionExportRow>): ByteArray {
        val output = ByteArrayOutputStream()
        InvoicePositionCsvWriter.write(output, labels, rows)
        return output.toByteArray()
    }

    private fun parseAllRecords(bytes: ByteArray): List<CSVRecord> {
        val withoutBom = bytes.copyOfRange(BOM.size, bytes.size)
        val format = CSVFormat.Builder.create(CSVFormat.DEFAULT).setDelimiter(';').get()
        return CSVParser.parse(ByteArrayInputStream(withoutBom), StandardCharsets.UTF_8, format).use { it.records }
    }

    private fun parseDataRecords(bytes: ByteArray): List<CSVRecord> = parseAllRecords(bytes).drop(1)

    /** Column kinds in the exact order [InvoicePositionCsvWriter] and [InvoicePositionSheetWriter]
     *  both write them — mirrors the writer's own private `Col` object, kept here (not shared)
     *  because a test-side re-derivation is exactly what proves the two writers agree, rather than
     *  both consulting one shared table that could itself be wrong. */
    private enum class ColumnKind { TEXT, INTEGER, MONEY, QUANTITY, PERCENT, DATE }

    private val columnKinds = listOf(
        ColumnKind.INTEGER, ColumnKind.TEXT, ColumnKind.TEXT, ColumnKind.INTEGER,
        ColumnKind.TEXT, ColumnKind.TEXT, ColumnKind.TEXT,
        ColumnKind.TEXT, ColumnKind.TEXT, ColumnKind.TEXT,
        ColumnKind.QUANTITY, ColumnKind.TEXT, ColumnKind.TEXT, ColumnKind.MONEY,
        ColumnKind.TEXT, ColumnKind.TEXT, ColumnKind.PERCENT, ColumnKind.TEXT,
        ColumnKind.TEXT, ColumnKind.TEXT, ColumnKind.TEXT,
        ColumnKind.MONEY, ColumnKind.MONEY, ColumnKind.MONEY,
        ColumnKind.DATE, ColumnKind.DATE, ColumnKind.TEXT, ColumnKind.TEXT,
        ColumnKind.INTEGER, ColumnKind.TEXT,
        // Level, parent link, kind, travel date, per-diem block; every fixture row below is
        // a plain (non-TRIP) position, so all ten are blank in both formats regardless of the
        // exact kind used here (a blank XLSX cell always renders as an empty CSV field).
        ColumnKind.INTEGER, ColumnKind.TEXT, ColumnKind.TEXT, ColumnKind.DATE,
        ColumnKind.TEXT, ColumnKind.TEXT, ColumnKind.TEXT, ColumnKind.TEXT,
        ColumnKind.MONEY, ColumnKind.MONEY,
    )

    private fun renderXlsxCellAsCsvField(cell: Cell, kind: ColumnKind): String {
        if (cell.cellType == CellType.BLANK) return ""
        return when (kind) {
            ColumnKind.TEXT -> cell.stringCellValue
            ColumnKind.INTEGER -> cell.numericCellValue.toLong().toString()
            ColumnKind.MONEY, ColumnKind.PERCENT -> decimalString(cell.numericCellValue, scale = 2)
            ColumnKind.QUANTITY -> decimalString(cell.numericCellValue, scale = 4)
            ColumnKind.DATE -> cell.localDateTimeCellValue.toLocalDate().format(DATE_FORMATTER)
        }
    }

    private fun decimalString(value: Double, scale: Int): String =
        BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).toPlainString().replace('.', ',')

    private companion object {
        val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
