package de.littletrotter.shared.mail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * `mail.send.result`, component → host. The golden-JSON tests pin the exact key set the
 * component's `MailResultPublisher` has to produce and the host's projection has to parse; the two
 * are in different repositories, so a rename that only compiles on one side is exactly the failure
 * these assertions exist to catch.
 */
class MailSendResultEventTest {

    private val sent = MailSendResultEvent(
        journalId = 4711L,
        referenceKey = "RE-2026-0001",
        status = MailSendOutcome.SENT,
        messageId = "<20260907101530.4711@little-trotter>",
        sentAt = Instant.parse("2026-09-07T10:15:30Z"),
    )

    private val goldenSent =
        """{"schemaVersion":5,"journalId":4711,"referenceKey":"RE-2026-0001","status":"SENT",""" +
            """"messageId":"<20260907101530.4711@little-trotter>","sentAt":"2026-09-07T10:15:30Z",""" +
            """"documentKind":"INVOICE_DISPATCH"}"""

    private val failed = MailSendResultEvent(
        journalId = 4712L,
        referenceKey = "RE-2026-0002",
        status = MailSendOutcome.FAILED,
        messageId = null,
        sentAt = Instant.parse("2026-09-07T10:16:00Z"),
    )

    private val goldenFailed =
        """{"schemaVersion":5,"journalId":4712,"referenceKey":"RE-2026-0002","status":"FAILED",""" +
            """"messageId":null,"sentAt":"2026-09-07T10:16:00Z",""" +
            """"documentKind":"INVOICE_DISPATCH"}"""

    // ── Round trip and golden shape ────────────────────────────────────────────

    @Test
    fun `a QUOTE_DISPATCH event round-trips with its document kind`() {
        val quote = sent.copy(documentKind = MailDocumentKind.QUOTE_DISPATCH)
        val json = MailSendResultEvent.encode(quote)
        assertEquals(true, json.contains(""""documentKind":"QUOTE_DISPATCH""""))
        assertEquals(quote, MailSendResultEvent.decode(json))
    }

    @Test
    fun `an event without documentKind decodes as INVOICE_DISPATCH`() {
        val old = goldenSent.replace(""","documentKind":"INVOICE_DISPATCH"""", "")
        assertEquals(MailDocumentKind.INVOICE_DISPATCH, MailSendResultEvent.decode(old).documentKind)
    }

    @Test
    fun `encode-decode round-trips a SENT event`() {
        assertEquals(sent, MailSendResultEvent.decode(MailSendResultEvent.encode(sent)))
    }

    @Test
    fun `encode-decode round-trips a FAILED event without a messageId`() {
        val decoded = MailSendResultEvent.decode(MailSendResultEvent.encode(failed))
        assertEquals(failed, decoded)
        assertNull(decoded.messageId)
    }

    @Test
    fun `the SENT wire shape is pinned - an accidental field rename fails here`() {
        assertEquals(goldenSent, MailSendResultEvent.encode(sent))
    }

    @Test
    fun `the FAILED wire shape keeps messageId as an explicit null, same key set as SENT`() {
        assertEquals(goldenFailed, MailSendResultEvent.encode(failed))
    }

    @Test
    fun `the golden payloads decode back into the same events`() {
        assertEquals(sent, MailSendResultEvent.decode(goldenSent))
        assertEquals(failed, MailSendResultEvent.decode(goldenFailed))
    }

    @Test
    fun `an omitted messageId decodes as null, like an explicit one`() {
        val omitted = goldenFailed.replace(""""messageId":null,""", "")
        assertEquals(failed, MailSendResultEvent.decode(omitted))
    }

    // ── Independent deployment: unknown in, required out ───────────────────────

    @Test
    fun `unknown fields are ignored on read`() {
        val newerProducer = goldenSent.dropLast(1) +
            ""","smtpHost":"mail.example.de","attempt":1,"retryOfJournalId":null}"""
        assertEquals(sent, MailSendResultEvent.decode(newerProducer))
    }

    @Test
    fun `an unknown schemaVersion is recorded, not rejected`() {
        assertEquals(9, MailSendResultEvent.decode(goldenSent.replace("\"schemaVersion\":5", "\"schemaVersion\":9")).schemaVersion)
    }

    @Test
    fun `a missing referenceKey fails loudly instead of defaulting to an empty string`() {
        val without = goldenSent.replace(""""referenceKey":"RE-2026-0001",""", "")
        val thrown = assertThrows<MailContractViolationException> { MailSendResultEvent.decode(without) }
        assertEquals("MailSendResultEvent: required field 'referenceKey' is missing or blank", thrown.message)
    }

    @Test
    fun `a missing journalId fails loudly instead of defaulting to zero`() {
        val without = goldenSent.replace(""""journalId":4711,""", "")
        assertThrows<MailContractViolationException> { MailSendResultEvent.decode(without) }
    }

    @Test
    fun `a missing status fails loudly - no outcome may be assumed`() {
        val without = goldenSent.replace(""""status":"SENT",""", "")
        assertThrows<MailContractViolationException> { MailSendResultEvent.decode(without) }
    }

    @Test
    fun `a non-terminal component status is not a contract value`() {
        val queued = goldenSent.replace(""""status":"SENT"""", """"status":"QUEUED"""")
        val thrown = assertThrows<MailContractViolationException> { MailSendResultEvent.decode(queued) }
        assertTrue(thrown.message!!.contains("QUEUED"))
    }

    @Test
    fun `a missing sentAt fails loudly`() {
        val without = goldenSent.replace(""","sentAt":"2026-09-07T10:15:30Z"""", "")
        assertThrows<MailContractViolationException> { MailSendResultEvent.decode(without) }
    }

    @Test
    fun `a sentAt that is not an ISO-8601 instant fails loudly`() {
        val broken = goldenSent.replace("2026-09-07T10:15:30Z", "07.09.2026 10:15")
        val thrown = assertThrows<MailContractViolationException> { MailSendResultEvent.decode(broken) }
        assertTrue(thrown.message!!.contains("sentAt"))
    }

    @Test
    fun `a present but blank messageId fails loudly rather than passing an empty Message-ID on`() {
        val blank = goldenSent.replace(""""messageId":"<20260907101530.4711@little-trotter>"""", """"messageId":"  """")
        val thrown = assertThrows<MailContractViolationException> { MailSendResultEvent.decode(blank) }
        assertEquals("MailSendResultEvent: optional field 'messageId' is present but blank", thrown.message)
    }

    @Test
    fun `a messageId of the wrong JSON type fails loudly`() {
        val numeric = goldenSent.replace(""""messageId":"<20260907101530.4711@little-trotter>"""", """"messageId":4711""")
        assertThrows<MailContractViolationException> { MailSendResultEvent.decode(numeric) }
    }

    @Test
    fun `a payload that is not a JSON object fails loudly`() {
        assertThrows<MailContractViolationException> { MailSendResultEvent.decode(null) }
        assertThrows<MailContractViolationException> { MailSendResultEvent.decode("4711") }
        assertThrows<MailContractViolationException> { MailSendResultEvent.decode("{ truncated") }
    }
}
