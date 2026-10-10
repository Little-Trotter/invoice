package de.littletrotter.xpdf.infrastructure.messaging

import de.littletrotter.shared.documentjob.DocumentJobRequest
import de.littletrotter.shared.documentjob.DocumentJobWireCodec
import de.littletrotter.shared.documentjob.QuoteDocumentJob
import de.littletrotter.shared.documentjob.QuoteDocumentPayload
import de.littletrotter.shared.documentjob.QuoteDocumentPayloadCodec
import de.littletrotter.xpdf.impl.QuoteFixtures
import io.nats.client.Message
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.Executor

/** Offer jobs are routed to [QuoteJobProcessor] before any invoice mapping happens. */
class JobConsumerQuoteJobTest {

    private val processor: QuoteJobProcessor = mock()
    private val dispatcher: JobDispatcher = mock()
    private val poison: PoisonMessageHandler = mock()
    private val consumer = JobConsumer(mock(), mock(), poison, dispatcher, processor).apply {
        executor = Executor { it.run() }
    }

    private fun message(request: DocumentJobRequest): Message {
        val message: Message = mock()
        whenever(message.data).thenReturn(DocumentJobWireCodec.encodeRequest(request).toByteArray())
        return message
    }

    private fun quoteRequest(payload: Map<String, Any>?) = DocumentJobRequest(
        id = "q-1",
        type = QuoteDocumentJob.TYPE,
        correlationId = "c-1",
        payload = payload,
        resultSubject = QuoteDocumentJob.RESULT_SUBJECT,
    )

    @Test
    fun `an offer job is processed by the quote processor and acked without touching the invoice dispatcher`() {
        val message = message(quoteRequest(QuoteDocumentPayloadCodec.toMap(QuoteFixtures.payload())))

        consumer.receive(message)

        val captor = argumentCaptor<QuoteDocumentPayload>()
        verify(processor).process(any(), captor.capture())
        org.junit.jupiter.api.Assertions.assertEquals(QuoteFixtures.payload(), captor.firstValue)
        verify(message).ack()
        verify(dispatcher, never()).dispatch(any(), any())
    }

    @Test
    fun `an offer job without a usable payload is poison and fails definitively`() {
        val message = message(quoteRequest(mapOf("unrelated" to "x")))

        consumer.receive(message)

        verify(poison).handleUnmappableInvoice(eq(message), any(), any())
        verify(processor, never()).process(any(), any())
    }

    @Test
    fun `a failure escaping the processor naks so the job is redelivered`() {
        val message = message(quoteRequest(QuoteDocumentPayloadCodec.toMap(QuoteFixtures.payload())))
        doThrow(IllegalStateException("nats down")).whenever(processor).process(any(), any())

        consumer.receive(message)

        verify(message).nak()
        verify(message, never()).ack()
    }
}
