package de.littletrotter.ui.view.mail

import de.littletrotter.application.export.port.inbound.MailJournalWorkbookExportQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceIdByNumberQueryPort
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailJournalCriteria
import de.littletrotter.shared.time.ClockTimeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class MailJournalSupportTest {

    private val services = { service: MailJournalWorkbookExportQueryPort ->
        MailJournalExportServices(service, { key -> "<$key>" }, ClockTimeSource.fixedAt(Instant.parse("2026-10-02T08:00:00Z")))
    }

    @Test
    fun `the export file name carries the date bounds and the timestamp`() {
        val name = mailJournalExportFileName(
            MailJournalCriteria(from = LocalDate.of(2026, 9, 1)),
            LocalDateTime.of(2026, 10, 2, 10, 5, 7),
        )
        assertEquals("mail-journal_20260901_alle_20261002-100507.xlsx", name)
    }

    @Test
    fun `an upper date bound appears in the export file name`() {
        val name = mailJournalExportFileName(
            MailJournalCriteria(from = LocalDate.of(2026, 9, 1), to = LocalDate.of(2026, 9, 30)),
            LocalDateTime.of(2026, 10, 2, 10, 5, 7),
        )
        assertEquals("mail-journal_20260901_20260930_20261002-100507.xlsx", name)
    }

    @Test
    fun `the export streams the written workbook and names it`() {
        val service = mock<MailJournalWorkbookExportQueryPort> {
            on { writeWorkbook(any(), any(), any()) } doAnswer { (it.getArgument<OutputStream>(2)).write("xlsx".toByteArray()) }
        }

        val response = buildExportResponse(MailJournalCriteria(), services(service))

        val bytes = response.inputStream.use { it.readBytes() }
        assertEquals("xlsx", bytes.toString(Charsets.UTF_8))
        assertTrue(response.fileName.startsWith("mail-journal_alle_alle_"))
    }

    @Test
    fun `a failing component becomes an error response and leaves no temp file`() {
        val service = mock<MailJournalWorkbookExportQueryPort> {
            on { writeWorkbook(any(), any(), any()) } doThrow MailDispatchException("down")
        }

        val response = buildExportResponse(MailJournalCriteria(), services(service))

        assertEquals(500, response.error)
    }

    @Test
    fun `an unwritable file becomes an error response`() {
        val service = mock<MailJournalWorkbookExportQueryPort> {
            on { writeWorkbook(any(), any(), any()) } doThrow IOException("disk full")
        }

        val response = buildExportResponse(MailJournalCriteria(), services(service))

        assertEquals(500, response.error)
    }

    @Test
    fun `invoice links resolve a loaded page with one lookup`() {
        val port = mock<InvoiceIdByNumberQueryPort>()
        whenever(port.findIdsByNumbers(any())).thenReturn(mapOf("RE-1" to 5L))
        val links = MailJournalInvoiceLinks(port)

        links.load(listOf("RE-1", "RE-1", "Q-1"))

        assertEquals(5L, links.invoiceIdOf("RE-1"))
        assertEquals(5L, links.invoiceIdOf("RE-1"))
        assertNull(links.invoiceIdOf("Q-1"))
        verify(port, times(1)).findIdsByNumbers(setOf("RE-1", "Q-1"))
    }

    @Test
    fun `invoice links forget the page on reset`() {
        val port = mock<InvoiceIdByNumberQueryPort>()
        whenever(port.findIdsByNumbers(any())).thenReturn(mapOf("RE-1" to 5L))
        val links = MailJournalInvoiceLinks(port)
        links.load(listOf("RE-1"))

        links.reset()

        assertNull(links.invoiceIdOf("RE-1"))
    }

    @Test
    fun `sizes and hashes are shortened for the screen`() {
        assertEquals("512 B", MailJournalDetails.formatSize(512))
        assertEquals("1,5 KB", MailJournalDetails.formatSize(1_536))
        assertEquals("abcdef123456…", MailJournalDetails.shortHash("abcdef1234567890"))
        assertEquals("abc", MailJournalDetails.shortHash("abc"))
    }
}
