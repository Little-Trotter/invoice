package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.port.inbound.QuoteSendGateState
import de.littletrotter.shared.quote.QuoteSendGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Send gate mapping (offer contract to local model)")
class QuoteSendGateMappingTest {

    @Test
    fun `every contract value maps to the local value of the same name`() {
        QuoteSendGate.entries.forEach { assertEquals(it.name, it.toState().name) }
        assertEquals(QuoteSendGate.entries.size, QuoteSendGateState.entries.size)
    }

    @Test
    fun `a wire name resolves to the local value and an unknown name to nothing`() {
        QuoteSendGate.entries.forEach { assertEquals(QuoteSendGateState.valueOf(it.name), sendGateStateOf(it.name)) }
        assertNull(sendGateStateOf("FUTURE"))
    }
}
