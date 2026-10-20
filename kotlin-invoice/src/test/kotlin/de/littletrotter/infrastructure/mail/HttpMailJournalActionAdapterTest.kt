package de.littletrotter.infrastructure.mail

import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailJournalArchiveStatus
import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HttpMailJournalActionAdapter (retry and rearchive)")
class HttpMailJournalActionAdapterTest {

    private val stub = MailStubServer()
    private val adapter = HttpMailJournalActionAdapter(MailStubServer.client(stub.baseUrl))

    @AfterEach
    fun tearDown() = stub.close()

    @Test
    fun `retry posts to the row and maps the new linked row`() {
        stub.respond("/mail/api/journal/7/retry", 202, """{"journalId":8,"status":"QUEUED"}""")

        val outcome = adapter.retry(7)

        assertEquals(8L, outcome.journalId)
        assertEquals(MailJournalStatus.QUEUED, outcome.status)
        assertEquals("POST", stub.last().method)
    }

    @Test
    fun `a refused retry is an error`() {
        stub.respond("/mail/api/journal/7/retry", 409, "not failed")
        assertThrows(MailDispatchException::class.java) { adapter.retry(7) }
    }

    @Test
    fun `rearchive maps the archive outcome`() {
        stub.respond("/mail/api/journal/7/rearchive", 200, """{"journalId":7,"archiveStatus":"ARCHIVED","archiveError":null}""")

        val outcome = adapter.rearchive(7)

        assertEquals(7L, outcome.journalId)
        assertEquals(MailJournalArchiveStatus.ARCHIVED, outcome.archiveStatus)
        assertNull(outcome.archiveError)
    }

    @Test
    fun `rearchive carries a failure text`() {
        stub.respond("/mail/api/journal/7/rearchive", 200, """{"journalId":7,"archiveStatus":"FAILED","archiveError":"bucket down"}""")

        val outcome = adapter.rearchive(7)

        assertEquals(MailJournalArchiveStatus.FAILED, outcome.archiveStatus)
        assertEquals("bucket down", outcome.archiveError)
    }
}
