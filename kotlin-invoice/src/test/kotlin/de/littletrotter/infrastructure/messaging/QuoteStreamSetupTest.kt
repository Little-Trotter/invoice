package de.littletrotter.infrastructure.messaging

import de.littletrotter.shared.quote.QuoteStreams
import io.nats.client.JetStreamManagement
import io.nats.client.api.DiscardPolicy
import io.nats.client.api.RetentionPolicy
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration

class QuoteStreamSetupTest {

    @Test
    fun `the acceptance stream carries the acceptance subject with the offer side's limits`() {
        val config = QuoteStreamSetup.invoiceOrderConfiguration()

        assertEquals(QuoteStreams.INVOICE_ORDER, config.name)
        assertEquals("LITTLE_TROTTER_QUOTE_INVOICE_ORDER", config.name)
        assertEquals(listOf("quote.invoice-order.requested"), config.subjects)
        assertLimits(config)
    }

    @Test
    fun `the reply stream carries both reply subjects`() {
        val config = QuoteStreamSetup.replyConfiguration()

        assertEquals(QuoteStreams.INVOICE_REPLY, config.name)
        assertEquals("LITTLE_TROTTER_QUOTE_INVOICE", config.name)
        assertEquals(listOf("invoice.created-from-quote", "invoice.creation-from-quote.rejected"), config.subjects)
        assertLimits(config)
    }

    @Test
    fun `both streams are created when neither exists`() {
        val jsm = mock<JetStreamManagement>()
        whenever(jsm.streamNames).thenReturn(emptyList())

        QuoteStreamSetup.ensure(jsm)

        val created = argumentCaptor<StreamConfiguration>()
        verify(jsm, org.mockito.kotlin.times(2)).addStream(created.capture())
        assertEquals(setOf(QuoteStreams.INVOICE_ORDER, QuoteStreams.INVOICE_REPLY), created.allValues.map { it.name }.toSet())
    }

    @Test
    fun `an existing stream is left alone and only the missing one is created`() {
        val jsm = mock<JetStreamManagement>()
        whenever(jsm.streamNames).thenReturn(listOf(QuoteStreams.INVOICE_ORDER))

        QuoteStreamSetup.ensure(jsm)

        val created = argumentCaptor<StreamConfiguration>()
        verify(jsm).addStream(created.capture())
        assertEquals(QuoteStreams.INVOICE_REPLY, created.firstValue.name)
        verify(jsm, never()).updateStream(org.mockito.kotlin.any())
    }

    private fun assertLimits(config: StreamConfiguration) {
        assertEquals(StorageType.File, config.storageType)
        assertEquals(RetentionPolicy.Limits, config.retentionPolicy)
        assertEquals(DiscardPolicy.Old, config.discardPolicy)
        assertEquals(Duration.ofDays(30), config.maxAge)
        assertEquals(268_435_456L, config.maxBytes)
        assertEquals(Duration.ofHours(1), config.duplicateWindow)
    }
}
