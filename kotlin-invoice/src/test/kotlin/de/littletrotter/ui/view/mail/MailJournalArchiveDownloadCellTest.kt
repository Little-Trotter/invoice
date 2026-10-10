package de.littletrotter.ui.view.mail

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailArchiveDownload
import de.littletrotter.application.mail.port.inbound.MailArchiveDownloadQueryPort
import de.littletrotter.application.mail.port.inbound.MailJournalArchiveStatus
import de.littletrotter.application.mail.port.inbound.MailJournalEntry
import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.ui.i18n.TranslationProvider
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant

class MailJournalArchiveDownloadCellTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private val query = mock<MailArchiveDownloadQueryPort>()
    private val translations = object : TranslationProvider {
        override fun getComponent(): Component? = null
        override fun tr(key: String, vararg params: Any): String = key
    }
    private val cell = MailJournalArchiveDownloadCell(query).also { it.bind(translations) }

    private fun entry(archive: MailJournalArchiveStatus) = MailJournalEntry(
        id = 5L, referenceKey = "RE-1", recipient = "r@example.com", subject = "s", messageId = null,
        status = MailJournalStatus.SENT, smtpResult = null, templateKind = MailTemplateKind.INVOICE_DISPATCH,
        templateLanguage = MailTemplateLanguage.DE, templateVersion = 1, bodyEditedByUser = false,
        retryOfJournalId = null, createdAt = Instant.parse("2026-09-06T10:15:30Z"),
        attachments = emptyList(), archiveStatus = archive, archiveS3Key = null,
    )

    @Test
    fun `archived row gets a download anchor and others an empty span`() {
        assertTrue(cell.build(entry(MailJournalArchiveStatus.ARCHIVED)) is Anchor)
        assertTrue(cell.build(entry(MailJournalArchiveStatus.PENDING)) is Span)
        assertTrue(cell.build(entry(MailJournalArchiveStatus.FAILED)) is Span)
    }

    @Test
    fun `click time serves the package with its name and type`() {
        whenever(query.findArchive(5L)).thenReturn(MailArchiveDownload("a.tar.gz", byteArrayOf(1, 2, 3)))

        val response = cell.respond(5L)

        assertEquals("a.tar.gz", response.fileName)
        assertEquals("application/gzip", response.contentType)
        assertArrayEquals(byteArrayOf(1, 2, 3), response.inputStream.use { it.readBytes() })
    }

    @Test
    fun `click time answers 404 when the package is gone or the component fails`() {
        assertEquals(404, cell.respond(6L).error)
        whenever(query.findArchive(5L)).thenThrow(MailDispatchException("down"))
        assertEquals(404, cell.respond(5L).error)
    }

    @Test
    fun `archive value equality covers name and content`() {
        val a = MailArchiveDownload("f", byteArrayOf(1))
        assertEquals(a, MailArchiveDownload("f", byteArrayOf(1)))
        assertEquals(a.hashCode(), MailArchiveDownload("f", byteArrayOf(1)).hashCode())
        assertNotEquals(a, MailArchiveDownload("f", byteArrayOf(2)))
        assertNotEquals(a, MailArchiveDownload("g", byteArrayOf(1)))
        assertFalse(a.equals("x"))
    }
}
