package de.littletrotter.infrastructure.messaging.acl

import de.littletrotter.application.invoice.port.outbound.PdfCheckOrder
import de.littletrotter.infrastructure.messaging.OutboxAppended
import de.littletrotter.infrastructure.messaging.OutboxRepository
import de.littletrotter.shared.documentjob.DocumentJobSubjects
import de.littletrotter.shared.documentjob.DocumentJobWireCodec
import jakarta.enterprise.event.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class PdfCheckOutboxPublisherTest {

    private val outbox: OutboxRepository = mock()
    private val appended: Event<OutboxAppended> = mock()
    private val publisher = PdfCheckOutboxPublisher(outbox, appended)

    @Test
    fun `an order is appended as a claim check on the check subject and announced to the relay`() {
        publisher.request(PdfCheckOrder(7L, "inv/7.pdf", "ab", "c-7"))

        val payload = argumentCaptor<String>()
        verify(outbox).append(eq("GENERATION_JOB"), eq("7"), eq(DocumentJobSubjects.PDF_CHECKS), payload.capture())
        val request = DocumentJobWireCodec.decodePdfCheckRequest(payload.firstValue)
        assertEquals("verapdf-7", request.checkId)
        assertEquals("7", request.jobId)
        assertEquals("inv/7.pdf", request.objectKey)
        assertEquals("ab", request.sha256)
        assertEquals("c-7", request.correlationId)
        assertFalse(payload.firstValue.contains("%PDF"), "no document bytes travel in the message")
        verify(appended).fire(any())
    }
}
