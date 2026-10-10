package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.application.mail.port.inbound.MailJournalArchiveStatus
import de.littletrotter.application.mail.port.inbound.MailJournalCriteria
import de.littletrotter.application.mail.port.inbound.MailJournalEntry
import de.littletrotter.application.mail.port.inbound.MailJournalQueryPort
import de.littletrotter.application.mail.port.inbound.MailJournalSortColumn
import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.application.mail.port.inbound.MailSortOrder
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.time.Instant

class MailJournalWorkbookExportServiceTest {

    private val query = mock<MailJournalQueryPort>()
    private val service = MailJournalWorkbookExportService(query)
    private val translate: (String) -> String = { key -> "<$key>" }

    private fun entry(id: Long, retryOf: Long? = null) = MailJournalEntry(
        id = id,
        referenceKey = "RE-$id",
        recipient = "kunde$id@example.com",
        subject = "Rechnung $id",
        messageId = null,
        status = MailJournalStatus.SENT,
        smtpResult = null,
        templateKind = MailTemplateKind.INVOICE_DISPATCH,
        templateLanguage = MailTemplateLanguage.DE,
        templateVersion = 3,
        bodyEditedByUser = false,
        retryOfJournalId = retryOf,
        createdAt = Instant.parse("2026-09-06T10:15:30Z"),
        attachments = emptyList(),
        archiveStatus = MailJournalArchiveStatus.ARCHIVED,
        archiveS3Key = null,
    )

    private fun export(criteria: MailJournalCriteria): org.apache.poi.xssf.usermodel.XSSFWorkbook {
        val out = ByteArrayOutputStream()
        service.writeWorkbook(criteria, translate, out)
        return readWorkbook(out.toByteArray())
    }

    @Test
    fun `an empty journal still yields the header row`() {
        whenever(query.findPage(any())).thenReturn(emptyList())

        val sheet = export(MailJournalCriteria()).getSheetAt(0)

        assertEquals("<mail.journal.export.sheet>", sheet.sheetName)
        assertEquals("<mail.journal.column.createdAt>", sheet.getRow(0).getCell(0).stringCellValue)
        assertEquals(8, sheet.getRow(0).lastCellNum.toInt())
        assertEquals(0, sheet.lastRowNum)
    }

    @Test
    fun `every page is read until a short page and rows carry the list columns`() {
        val full = (1L..200L).map { entry(it) }
        whenever(query.findPage(any())).thenReturn(full, listOf(entry(201, retryOf = 5)))

        val sheet = export(MailJournalCriteria(sort = MailJournalSortColumn.RECIPIENT, direction = MailSortOrder.ASC)).getSheetAt(0)

        assertEquals(201, sheet.lastRowNum)
        val last = sheet.getRow(201)
        assertEquals("<mail.journal.status.SENT>", last.getCell(1).stringCellValue)
        assertEquals("RE-201", last.getCell(2).stringCellValue)
        assertEquals("<mail.journal.kind.INVOICE_DISPATCH> · DE · v3", last.getCell(5).stringCellValue)
        assertEquals("<mail.journal.archive.ARCHIVED>", last.getCell(6).stringCellValue)
        assertEquals(5.0, last.getCell(7).numericCellValue)
        val criteria = argumentCaptor<MailJournalCriteria>()
        verify(query, times(2)).findPage(criteria.capture())
        assertEquals(listOf(0, 1), criteria.allValues.map { it.page })
        assertEquals(MailJournalSortColumn.RECIPIENT, criteria.secondValue.sort)
    }
}
