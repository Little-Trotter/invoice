package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.outbound.QuoteReplyPublisherPort
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.time.LocalDate
import java.util.UUID

class QuoteReplyRecorderTest {

    private val publisher: QuoteReplyPublisherPort = mock()
    private val recorder = QuoteReplyRecorder(publisher)
    private val command = CreateInvoiceFromQuoteCommand(UUID.randomUUID(), "AN-1", LocalDate.of(2026, 10, 5), null, "clerk", UUID.randomUUID())

    @Test
    fun `a created reply is forwarded to the publisher`() {
        recorder.created(command, 5L, "RE-1")

        verify(publisher).created(command, 5L, "RE-1")
    }

    @Test
    fun `a rejected reply is forwarded to the publisher`() {
        recorder.rejected(command, "Grund")

        verify(publisher).rejected(command, "Grund")
    }
}
