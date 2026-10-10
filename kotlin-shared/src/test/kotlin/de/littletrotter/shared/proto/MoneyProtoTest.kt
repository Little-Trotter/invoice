package de.littletrotter.shared.proto

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class MoneyProtoTest {
    @Test
    fun `money builder roundtrip`() {
        val m = Money.newBuilder().setAmount("99.99").build()
        val bytes = m.toByteArray()
        val parsed = Money.parseFrom(bytes)

        assertThat(parsed.amount).isEqualTo("99.99")
        assertThat(parsed).isEqualTo(m)
        assertThat(m.amountBytes.toStringUtf8()).isEqualTo("99.99")
    }
}
