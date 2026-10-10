package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.mail.MailDeliveryOutcome
import de.littletrotter.application.mail.MailDeliveryReport
import de.littletrotter.application.mail.MailProjectionOutcome
import de.littletrotter.application.mail.MailSendResultProjection
import de.littletrotter.shared.mail.MailContractViolationException
import de.littletrotter.shared.mail.MailDocumentKind
import de.littletrotter.shared.mail.MailSendOutcome
import de.littletrotter.shared.mail.MailSendResultEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * The consumer's own contract: decode with the shared codec, delegate, and let a
 * contract violation propagate.
 *
 * The "rejected loudly" cases matter more here than the happy path. The sibling
 * [GenerationJobResultConsumer] swallows a malformed payload into a simulated-DLQ log line,
 * because a broken render result degrades to "no report". A mail result decides whether a
 * customer-facing document counts as sent, so a payload that decodes into an empty referenceKey
 * would mark the wrong invoice — or no invoice — as mailed. That is exactly the defect
 * [MailContractViolationException]'s KDoc records, and these tests pin the strict behaviour.
 */
@DisplayName("MailSendResultConsumer (mail.send.result -> projection)")
class MailSendResultConsumerTest {

    private lateinit var projection: MailSendResultProjection
    private lateinit var consumer: MailSendResultConsumer

    @BeforeEach
    fun setUp() {
        projection = mock()
        consumer = MailSendResultConsumer(projection)
    }

    private val validPayload = MailSendResultEvent.encode(
        MailSendResultEvent(
            journalId = 17L,
            referenceKey = "RE-2026-0001",
            status = MailSendOutcome.SENT,
            messageId = "<abc@mail.example>",
            sentAt = Instant.parse("2026-09-07T10:15:30Z"),
        ),
    )

    @Test
    @DisplayName("a well-formed payload is decoded with the shared codec and handed to the projection")
    fun decodesAndProjects() {
        whenever(projection.project(any())).thenReturn(MailProjectionOutcome.APPLIED)

        assertEquals(MailProjectionOutcome.APPLIED, consumer.consume(validPayload))

        val captor = argumentCaptor<MailDeliveryReport>()
        verify(projection).project(captor.capture())
        val report = captor.firstValue
        assertEquals(17L, report.journalId)
        assertEquals("RE-2026-0001", report.referenceKey)
        assertEquals(MailDeliveryOutcome.SENT, report.outcome)
        assertEquals("<abc@mail.example>", report.messageId)
        assertEquals(Instant.parse("2026-09-07T10:15:30Z"), report.sentAt)
    }

    @Test
    @DisplayName("a duplicate delivery is reported as such and stays a no-op")
    fun duplicateIsPassedThrough() {
        whenever(projection.project(any())).thenReturn(MailProjectionOutcome.DUPLICATE)
        assertEquals(MailProjectionOutcome.DUPLICATE, consumer.consume(validPayload))
    }

    @Test
    @DisplayName("an unknown referenceKey reaches the consumer as a tolerated outcome, not an exception")
    fun unknownReferenceIsTolerated() {
        whenever(projection.project(any())).thenReturn(MailProjectionOutcome.UNKNOWN_REFERENCE)
        assertEquals(MailProjectionOutcome.UNKNOWN_REFERENCE, consumer.consume(validPayload))
    }

    @Test
    @DisplayName("a FAILED payload decodes fine and is passed on; the projection decides it writes nothing")
    fun failedPayloadDecodes() {
        val failed = MailSendResultEvent.encode(
            MailSendResultEvent(
                journalId = 18L,
                referenceKey = "RE-2026-0002",
                status = MailSendOutcome.FAILED,
                messageId = null,
                sentAt = Instant.parse("2026-09-07T11:00:00Z"),
            ),
        )
        whenever(projection.project(any())).thenReturn(MailProjectionOutcome.FAILURE_NOT_PROJECTED)
        assertEquals(MailProjectionOutcome.FAILURE_NOT_PROJECTED, consumer.consume(failed))
    }

    @Test
    @DisplayName("a payload that is not JSON is rejected loudly, never projected")
    fun rejectsNonJson() {
        assertThrows(MailContractViolationException::class.java) { consumer.consume("not json at all") }
        verify(projection, never()).project(any())
    }

    @Test
    @DisplayName("a payload missing referenceKey is rejected — it must never mark the wrong invoice")
    fun rejectsMissingReferenceKey() {
        val truncated = """{"schemaVersion":1,"journalId":5,"status":"SENT","messageId":null,"sentAt":"2026-09-07T10:15:30Z"}"""
        val e = assertThrows(MailContractViolationException::class.java) { consumer.consume(truncated) }
        assertTrue(e.message!!.contains("referenceKey"))
        verify(projection, never()).project(any())
    }

    @Test
    @DisplayName("a payload with a blank referenceKey is rejected too, not decoded into an empty key")
    fun rejectsBlankReferenceKey() {
        val blank = """{"schemaVersion":1,"journalId":5,"referenceKey":"  ","status":"SENT","sentAt":"2026-09-07T10:15:30Z"}"""
        assertThrows(MailContractViolationException::class.java) { consumer.consume(blank) }
        verify(projection, never()).project(any())
    }

    @Test
    @DisplayName("a payload with an unreadable sentAt is rejected")
    fun rejectsUnparseableTimestamp() {
        val bad = """{"schemaVersion":1,"journalId":5,"referenceKey":"RE-1","status":"SENT","sentAt":"gestern"}"""
        assertThrows(MailContractViolationException::class.java) { consumer.consume(bad) }
        verify(projection, never()).project(any())
    }

    @Test
    @DisplayName("a payload with an unknown status constant is rejected")
    fun rejectsUnknownStatus() {
        val bad = """{"schemaVersion":1,"journalId":5,"referenceKey":"RE-1","status":"QUEUED","sentAt":"2026-09-07T10:15:30Z"}"""
        assertThrows(MailContractViolationException::class.java) { consumer.consume(bad) }
        verify(projection, never()).project(any())
    }

    @Test
    @DisplayName("an unknown field a newer component added is ignored, not an error")
    fun ignoresUnknownFields() {
        whenever(projection.project(any())).thenReturn(MailProjectionOutcome.APPLIED)
        val forwardCompatible =
            """{"schemaVersion":2,"journalId":5,"referenceKey":"RE-1","status":"SENT","sentAt":"2026-09-07T10:15:30Z","retryOf":4}"""
        assertEquals(MailProjectionOutcome.APPLIED, consumer.consume(forwardCompatible))
    }

    @Test
    @DisplayName("an offer-mail result is acknowledged without touching the projection")
    fun quoteDispatchIsIgnored() {
        val quote = MailSendResultEvent.encode(
            MailSendResultEvent(
                journalId = 4L,
                referenceKey = "AN-2026-0006-R2",
                status = MailSendOutcome.SENT,
                messageId = "<q@mail.example>",
                sentAt = Instant.parse("2026-09-07T10:15:30Z"),
                documentKind = MailDocumentKind.QUOTE_DISPATCH,
            ),
        )
        assertEquals(MailProjectionOutcome.OTHER_DOCUMENT_KIND, consumer.consume(quote))
        verify(projection, never()).project(any())
    }
}
