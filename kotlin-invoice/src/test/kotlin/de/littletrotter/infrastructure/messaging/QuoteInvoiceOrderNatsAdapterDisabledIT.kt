package de.littletrotter.infrastructure.messaging

import de.littletrotter.shared.quote.QuoteStreams
import io.nats.client.Connection
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** Without the offer module the instance carries neither quote stream nor a consumer on it. */
@QuarkusTest
class QuoteInvoiceOrderNatsAdapterDisabledIT {

    @Inject
    lateinit var connection: Connection

    @Test
    fun `a disabled offer module leaves the quote streams uncreated and unconsumed`() {
        val streams = connection.jetStreamManagement().streamNames

        assertFalse(QuoteStreams.INVOICE_ORDER in streams)
        assertFalse(QuoteStreams.INVOICE_REPLY in streams)
    }
}
