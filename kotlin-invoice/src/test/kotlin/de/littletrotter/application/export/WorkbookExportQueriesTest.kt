package de.littletrotter.application.export

import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.export.port.outbound.InvoiceWorkbookExportPort
import de.littletrotter.application.export.port.outbound.MailJournalWorkbookExportPort
import de.littletrotter.application.export.port.outbound.TripWorkbookExportPort
import de.littletrotter.application.mail.port.inbound.MailJournalCriteria
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WorkbookExportQueriesTest {

    private val translate: (String) -> String = { "t:$it" }
    private val output = ByteArrayOutputStream()

    @Test
    fun `trip queries forward the workbook write and the file name to the adapter`() {
        val port = mock<TripWorkbookExportPort>()
        whenever(port.fileName("2026-1", 7L, "3")).thenReturn("trip.xlsx")
        val queries = TripWorkbookExportQueries(port)

        queries.writeTripWorkbook(7L, "3", translate, output)

        verify(port).writeTripWorkbook(7L, "3", translate, output)
        assertEquals("trip.xlsx", queries.fileName("2026-1", 7L, "3"))
    }

    @Test
    fun `mail journal queries forward the criteria to the adapter`() {
        val port = mock<MailJournalWorkbookExportPort>()
        val criteria = MailJournalCriteria(text = "abc")

        MailJournalWorkbookExportQueries(port).writeWorkbook(criteria, translate, output)

        verify(port).writeWorkbook(criteria, translate, output)
    }

    @Test
    fun `invoice queries forward single, filtered and csv exports to the matching adapter call`() {
        val port = mock<InvoiceWorkbookExportPort>()
        val queries = InvoiceWorkbookExportQueries(port)
        val filter = InvoiceExportFilter(filterText = "x")

        queries.writeWorkbook(5L, "selection", translate, output)
        queries.writeWorkbook(filter, "selection", translate, output)
        queries.writePositionsCsv(filter, translate, output)

        verify(port).writeWorkbook(5L, "selection", translate, output)
        verify(port).writeWorkbook(filter, "selection", translate, output)
        verify(port).writePositionsCsv(filter, translate, output)
    }
}
