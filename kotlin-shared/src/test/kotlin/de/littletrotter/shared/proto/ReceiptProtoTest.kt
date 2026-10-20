package de.littletrotter.shared.proto

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class ReceiptProtoTest {

    @Test
    fun `equals hashCode and serialization`() {
        val receipt = Receipt.newBuilder()
            .setFileName("receipt.pdf")
            .setObjectKey("bucket/key/receipt.pdf")
            .build()

        val parsed = Receipt.parseFrom(receipt.toByteArray())

        assertThat(parsed).isEqualTo(receipt)
        assertThat(parsed.hashCode()).isEqualTo(receipt.hashCode())
        assertThat(parsed.fileName).isEqualTo("receipt.pdf")
        assertThat(parsed.objectKey).isEqualTo("bucket/key/receipt.pdf")
    }
}
