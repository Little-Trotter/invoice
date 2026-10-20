package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * [PayloadExchangeHolder] backs both little-trotter-invoice's and kotlin-xpdf's own
 * `PayloadExchangeProducer`: an eagerly-built, memoized [PayloadExchange] plus a `by`-delegated
 * [close] (Sonar rule S6514). No live Valkey server is needed here — [PayloadExchange]'s own
 * constructor only configures a connection pool (lazy borrow-on-demand, see [PayloadExchange]'s
 * own KDoc), and `close()` just tears that pool down; nothing in this test ever issues a command
 * against it (that behavior is [PayloadExchangeTest]'s/`PayloadExchangeIT`'s concern).
 */
class PayloadExchangeHolderTest {

    private class TestHolder : PayloadExchangeHolder("localhost", 6379, "", 0)

    @Test
    fun `happy path - payloadExchange is the same instance across accesses`() {
        val holder = TestHolder()

        val first = holder.payloadExchange
        val second = holder.payloadExchange

        assertSame(first, second)
    }

    @Test
    fun `boundary - close tears down the PayloadExchange without throwing`() {
        val holder = TestHolder()
        holder.payloadExchange // touch it once, same instance close() will tear down

        holder.close()
    }

    @Test
    fun `boundary - close tears down the PayloadExchange even when it was never accessed first`() {
        val holder = TestHolder()

        holder.close()
    }

    @Test
    fun `boundary - the no-arg constructor ArC needs for the CDI proxy never eagerly connects`() {
        val holder = PayloadExchangeHolder()

        val payloadExchange = holder.payloadExchange

        assertSame(payloadExchange, holder.payloadExchange)
        holder.close()
    }
}
