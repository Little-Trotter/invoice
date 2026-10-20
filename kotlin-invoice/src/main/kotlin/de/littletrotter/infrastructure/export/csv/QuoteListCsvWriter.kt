package de.littletrotter.infrastructure.export.csv

import de.littletrotter.application.quote.port.inbound.QuoteSummary
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.infrastructure.export.workbook.TextGuard
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Writes the offer list as CSV in the dialect of [InvoicePositionCsvWriter]: `;`-delimited, UTF-8 with a
 * byte-order mark (German Excel otherwise mangles umlauts), CRLF, RFC 4180 quoting, decimal comma, dates as
 * `DD.MM.YYYY`. The columns are those of the list; user-supplied text passes the formula guard.
 */
internal object QuoteListCsvWriter {

    private const val DELIMITER = ";"
    private const val LINE_ENDING = "\r\n"
    private const val MONEY_SCALE = 2
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** Header row first, then one row per element of [rows]; [headers] are the already translated labels. */
    fun write(output: OutputStream, headers: List<String>, statusLabel: (QuoteStatus) -> String, rows: Sequence<QuoteSummary>) {
        output.write(BOM)
        val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
        writeLine(writer, headers.map { CsvFieldEscaper.escape(TextGuard.sanitize(it)) })
        rows.forEach { writeLine(writer, render(it, statusLabel)) }
        // Flushes into `output` without closing it; the caller owns the stream.
        writer.flush()
    }

    private fun writeLine(writer: BufferedWriter, fields: List<String>) {
        writer.write(fields.joinToString(DELIMITER))
        writer.write(LINE_ENDING)
    }

    private fun render(row: QuoteSummary, statusLabel: (QuoteStatus) -> String): List<String> = listOf(
        CsvTextField.render(row.number),
        CsvTextField.render(row.customerName),
        CsvTextField.render(row.subject),
        CsvTextField.render(statusLabel(row.status)),
        date(row.quoteDate),
        date(row.validUntil),
        row.netTotal.amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString().replace('.', ','),
    )

    private fun date(value: LocalDate): String = value.format(DATE_FORMATTER)
}
