package de.littletrotter.shared.mail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class MailArchiveHandoverNoticeEventTest {

    private val failed = MailArchiveHandoverNoticeEvent(
        journalId = 12L,
        referenceKey = "AN-2026-0003",
        reason = MailArchiveHandoverNoticeReason.HANDOVER_FAILED,
        error = "550 mailbox full",
        occurredAt = Instant.parse("2026-10-08T08:00:00Z"),
        documentKind = MailDocumentKind.QUOTE_DISPATCH,
    )

    private val golden =
        """{"schemaVersion":5,"journalId":12,"referenceKey":"AN-2026-0003","reason":"HANDOVER_FAILED",""" +
            """"error":"550 mailbox full","occurredAt":"2026-10-08T08:00:00Z","documentKind":"QUOTE_DISPATCH"}"""

    @Test
    fun `encodes the golden wire shape`() {
        assertEquals(golden, MailArchiveHandoverNoticeEvent.encode(failed))
    }

    @Test
    fun `round trips and writes a null error explicitly`() {
        val missing = failed.copy(reason = MailArchiveHandoverNoticeReason.ADDRESS_MISSING, error = null)
        val json = MailArchiveHandoverNoticeEvent.encode(missing)
        assertEquals(true, json.contains("\"error\":null"))
        assertEquals(missing, MailArchiveHandoverNoticeEvent.decode(json))
        assertEquals(failed, MailArchiveHandoverNoticeEvent.decode(golden))
    }

    @Test
    fun `defaults the document kind and ignores unknown fields`() {
        val json = """{"schemaVersion":5,"journalId":1,"referenceKey":"RE-1","reason":"ADDRESS_MISSING",""" +
            """"occurredAt":"2026-10-08T08:00:00Z","future":true}"""
        val event = MailArchiveHandoverNoticeEvent.decode(json)
        assertEquals(MailDocumentKind.INVOICE_DISPATCH, event.documentKind)
        assertNull(event.error)
    }

    @Test
    fun `rejects a malformed instant and a missing reason`() {
        assertThrows<MailContractViolationException> {
            MailArchiveHandoverNoticeEvent.decode(golden.replace("2026-10-08T08:00:00Z", "yesterday"))
        }
        assertThrows<MailContractViolationException> {
            MailArchiveHandoverNoticeEvent.decode(golden.replace("\"reason\":\"HANDOVER_FAILED\",", ""))
        }
    }
}
