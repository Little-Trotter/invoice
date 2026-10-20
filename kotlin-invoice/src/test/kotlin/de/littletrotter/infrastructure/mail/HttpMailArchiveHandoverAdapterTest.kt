package de.littletrotter.infrastructure.mail

import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettingsUpdate
import de.littletrotter.application.mail.port.inbound.MailJournalHandoverStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HttpMailArchiveHandoverAdapter (setting and retry)")
class HttpMailArchiveHandoverAdapterTest {

    private val stub = MailStubServer()
    private val adapter = HttpMailArchiveHandoverAdapter(MailStubServer.client(stub.baseUrl))
    private val view = """{"address":"archiv@example.com","invoiceEnabled":true,"quoteEnabled":false,"updatedAt":"2026-10-01T10:00:00Z","updatedBy":"anna"}"""

    @AfterEach
    fun tearDown() = stub.close()

    @Test
    fun `the setting is read with the acting user's identity`() {
        stub.respond("/mail/api/archive-handover", 200, view)

        val settings = adapter.settings()

        assertEquals("archiv@example.com", settings.address)
        assertEquals(false, settings.quoteEnabled)
        assertEquals("GET", stub.last().method)
        assertEquals("anna", stub.last().headers["x-lt-user"])
    }

    @Test
    fun `saving puts the whole setting and trims a blank address to none`() {
        stub.respond("/mail/api/archive-handover", 200, view)

        adapter.save(ArchiveHandoverSettingsUpdate("  archiv@example.com ", invoiceEnabled = true, quoteEnabled = false))
        assertEquals("PUT", stub.last().method)
        assertTrue(stub.last().body.contains("\"address\":\"archiv@example.com\""), stub.last().body)
        assertTrue(stub.last().body.contains("\"quoteEnabled\":false"), stub.last().body)

        adapter.save(ArchiveHandoverSettingsUpdate("   "))
        assertTrue(stub.last().body.contains("\"address\":null"), stub.last().body)
    }

    @Test
    fun `a refused save is an error`() {
        stub.respond("/mail/api/archive-handover", 403, "forbidden")
        assertThrows(MailDispatchException::class.java) { adapter.save(ArchiveHandoverSettingsUpdate("a@b.de")) }
    }

    @Test
    fun `the retry posts to the row and maps the resulting state`() {
        stub.respond("/mail/api/journal/7/retry-archive-handover", 200, """{"journalId":7,"archiveHandoverStatus":"FAILED","archiveHandoverError":"550 no"}""")

        val outcome = adapter.retry(7)

        assertEquals(MailJournalHandoverStatus.FAILED, outcome.status)
        assertEquals("550 no", outcome.error)
        assertEquals("POST", stub.last().method)
    }

    @Test
    fun `a successful retry carries no error`() {
        stub.respond("/mail/api/journal/7/retry-archive-handover", 200, """{"journalId":7,"archiveHandoverStatus":"HANDED_OVER","archiveHandoverError":null}""")

        assertNull(adapter.retry(7).error)
    }
}
