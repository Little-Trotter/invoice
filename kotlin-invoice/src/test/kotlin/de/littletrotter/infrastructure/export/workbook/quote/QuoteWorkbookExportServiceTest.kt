package de.littletrotter.infrastructure.export.workbook.quote

import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.inbound.QuotePage
import de.littletrotter.application.quote.port.outbound.QuoteDetailRemote
import de.littletrotter.application.quote.port.outbound.QuoteListRemote
import de.littletrotter.infrastructure.export.workbook.readWorkbook
import de.littletrotter.ui.view.quote.quoteDetail
import de.littletrotter.ui.view.quote.quoteSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream

class QuoteWorkbookExportServiceTest {

    private val listRemote: QuoteListRemote = mock()
    private val detailRemote: QuoteDetailRemote = mock()
    private val service = QuoteWorkbookExportService(listRemote, detailRemote)

    @Test
    fun `one offer becomes a header sheet with one row and an items sheet with its items`() {
        whenever(detailRemote.find("q-1")).thenReturn(quoteDetail())

        val workbook = readWorkbook(ByteArrayOutputStream().also { service.writeWorkbook("q-1", { it }, it) }.toByteArray())

        assertEquals(listOf("quoteExport.sheet.header", "quoteExport.sheet.items"), workbook.sheetNames())
        val header = workbook.getSheetAt(0)
        assertEquals("quoteExport.column.number", header.getRow(0).getCell(0).stringCellValue)
        assertEquals(1, header.lastRowNum)
        assertEquals("AN-2026-0001", header.getRow(1).getCell(0).stringCellValue)
        val items = workbook.getSheetAt(1)
        assertEquals(2, items.lastRowNum)
        assertEquals("AN-2026-0001", items.getRow(1).getCell(0).stringCellValue)
        assertEquals(1.0, items.getRow(1).getCell(1).numericCellValue)
    }

    @Test
    fun `the list export walks every page and writes every matched offer with its items`() {
        val first = quoteSummary("q-1")
        val second = quoteSummary("q-2")
        whenever(listRemote.findPage(any()))
            .thenReturn(QuotePage(listOf(first), 2))
            .thenReturn(QuotePage(listOf(second), 2))
        whenever(detailRemote.find("q-1")).thenReturn(quoteDetail())
        whenever(detailRemote.find("q-2")).thenReturn(quoteDetail(number = null))

        val workbook = readWorkbook(
            ByteArrayOutputStream().also { service.writeWorkbook(QuoteListCriteria(), { it }, it) }.toByteArray(),
        )

        assertEquals(listOf("quoteExport.sheet.quotes", "quoteExport.sheet.items"), workbook.sheetNames())
        assertEquals(2, workbook.getSheetAt(0).lastRowNum)
        assertEquals("quote.list.noNumber", workbook.getSheetAt(0).getRow(2).getCell(0).stringCellValue)
        assertEquals(4, workbook.getSheetAt(1).lastRowNum)
    }

    @Test
    fun `an empty list still yields both sheets with their header rows`() {
        whenever(listRemote.findPage(any())).thenReturn(QuotePage(emptyList(), 0))

        val workbook = readWorkbook(
            ByteArrayOutputStream().also { service.writeWorkbook(QuoteListCriteria(), { it }, it) }.toByteArray(),
        )

        assertEquals(0, workbook.getSheetAt(0).lastRowNum)
        assertEquals(0, workbook.getSheetAt(1).lastRowNum)
    }

    @Test
    fun `a vanished offer is reported to the caller`() {
        whenever(detailRemote.find("gone")).thenThrow(QuoteNotFoundException("gone"))

        assertThrows<QuoteNotFoundException> { service.writeWorkbook("gone", { it }, ByteArrayOutputStream()) }
    }

    private fun org.apache.poi.ss.usermodel.Workbook.sheetNames() = (0 until numberOfSheets).map { getSheetName(it) }
}
