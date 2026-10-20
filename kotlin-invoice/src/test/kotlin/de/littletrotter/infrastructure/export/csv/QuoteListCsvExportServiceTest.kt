package de.littletrotter.infrastructure.export.csv

import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.inbound.QuotePage
import de.littletrotter.application.quote.port.inbound.QuoteSortDirection
import de.littletrotter.application.quote.port.inbound.QuoteSortField
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.outbound.QuoteListRemote
import de.littletrotter.shared.Money
import de.littletrotter.ui.view.quote.quoteSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream

class QuoteListCsvExportServiceTest {

    private val listRemote: QuoteListRemote = mock()
    private val service = QuoteListCsvExportService(listRemote)

    private fun export(criteria: QuoteListCriteria = QuoteListCriteria()): String {
        val out = ByteArrayOutputStream()
        service.writeCsv(criteria, { it }, out)
        val bytes = out.toByteArray()
        assertEquals(listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.take(3), "UTF-8 byte-order mark")
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }

    @Test
    fun `no hit still writes the header row`() {
        whenever(listRemote.findPage(any())).thenReturn(QuotePage(emptyList(), 0))

        assertEquals(
            "quoteExport.column.number;quoteExport.column.customer;quoteExport.column.subject;" +
                "quoteExport.column.status;quoteExport.column.quoteDate;quoteExport.column.validUntil;" +
                "quoteExport.column.net\r\n",
            export(),
        )
    }

    @Test
    fun `a row follows the list columns with German date and decimal comma`() {
        whenever(listRemote.findPage(any())).thenReturn(QuotePage(listOf(quoteSummary("q-1")), 1))

        val lines = export().split("\r\n").filter { it.isNotEmpty() }

        assertEquals(2, lines.size)
        assertEquals("AN-2026-0001;Muster GmbH;Beratung;quote.status.SENT;01.10.2026;31.10.2026;250,00", lines[1])
    }

    @Test
    fun `umlauts semicolons and quotes in the subject are quoted and large amounts stay plain`() {
        val row = quoteSummary("q-1").copy(subject = "Büro; \"Neu\"\nzweite Zeile", netTotal = Money.of("1234567890.50"))
        whenever(listRemote.findPage(any())).thenReturn(QuotePage(listOf(row), 1))

        val text = export()

        assertTrue(text.contains("\"Büro; \"\"Neu\"\"\nzweite Zeile\""), text)
        assertTrue(text.contains(";1234567890,50\r\n"), text)
    }

    @Test
    fun `a subject starting with an equals sign cannot become a formula`() {
        val row = quoteSummary("q-1").copy(subject = "=1+1", number = null)
        whenever(listRemote.findPage(any())).thenReturn(QuotePage(listOf(row), 1))

        val line = export().split("\r\n")[1]

        assertTrue(line.startsWith(";Muster GmbH;'=1+1;"), line)
    }

    @Test
    fun `the rows come in the page order and the sort and filter reach the remote`() {
        whenever(listRemote.findPage(any()))
            .thenReturn(QuotePage(listOf(quoteSummary("q-2").copy(number = "B")), 2))
            .thenReturn(QuotePage(listOf(quoteSummary("q-1").copy(number = "A")), 2))
        val criteria = QuoteListCriteria(
            status = QuoteStatus.SENT,
            text = "x",
            sortField = QuoteSortField.QUOTE_DATE,
            sortDirection = QuoteSortDirection.ASC,
        )

        val lines = export(criteria).split("\r\n").filter { it.isNotEmpty() }

        assertEquals(listOf("B", "A"), lines.drop(1).map { it.substringBefore(';') })
        val captor = argumentCaptor<QuoteListCriteria>()
        verify(listRemote, org.mockito.kotlin.times(2)).findPage(captor.capture())
        captor.allValues.forEach {
            assertEquals(QuoteStatus.SENT, it.status)
            assertEquals(QuoteSortDirection.ASC, it.sortDirection)
        }
        assertEquals(listOf(0, 1), captor.allValues.map { it.offset })
    }
}
