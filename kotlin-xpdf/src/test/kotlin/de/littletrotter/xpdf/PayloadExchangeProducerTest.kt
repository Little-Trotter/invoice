package de.littletrotter.xpdf

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * [PayloadExchangeProducer] is the CDI wiring around [de.littletrotter.shared.PayloadExchangeHolder]
 * ([PayloadExchangeHolderTest] in kotlin-shared already covers the lazy-build/close plumbing itself).
 * No live Valkey server is needed: construction only configures a connection pool, it never dials
 * out (see [de.littletrotter.shared.PayloadExchange]'s own KDoc).
 */
class PayloadExchangeProducerTest {

    @Test
    fun `happy path - producer built with the documented Valkey defaults hands out a PayloadExchange`() {
        val producer = PayloadExchangeProducer("valkey", 6379, "", 0)

        val payloadExchange = producer.producePayloadExchange()

        assertNotNull(payloadExchange)
        producer.closePayloadExchange()
    }

    @Test
    fun `boundary - producePayloadExchange is memoized across repeated calls`() {
        val producer = PayloadExchangeProducer("valkey", 6379, "", 0)

        val first = producer.producePayloadExchange()
        val second = producer.producePayloadExchange()

        assertSame(first, second)
        producer.closePayloadExchange()
    }

    @Test
    fun `boundary - a non-blank password is accepted without the producer eagerly connecting`() {
        val producer = PayloadExchangeProducer("valkey", 6379, "s3cr3t", 0)

        val payloadExchange = producer.producePayloadExchange()

        assertNotNull(payloadExchange)
        producer.closePayloadExchange()
    }

    @Test
    fun `boundary - a non-default payload database index is forwarded through to the underlying exchange`() {
        val producer = PayloadExchangeProducer("valkey", 6379, "", 3)

        val payloadExchange = producer.producePayloadExchange()

        assertNotNull(payloadExchange)
        producer.closePayloadExchange()
    }
}
