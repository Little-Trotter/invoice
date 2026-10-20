package de.littletrotter.shared.proto

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class InvoiceItemProtoTest {
    @Test
    fun `invoice item nested and repeated fields`() {
        val unit = Money.newBuilder().setAmount("10.00").build()
        val rec = Receipt.newBuilder().setFileName("r.pdf").setObjectKey("obj-1").build()
        val item = InvoiceItem.newBuilder()
            .setPositionId("p1")
            .setDescription("desc")
            .setQuantity("2")
            .setUnitPriceNet(unit)
            .setTaxRate("19")
            .addReceipts(rec)
            .build()

        val bytes = item.toByteArray()
        val parsed = InvoiceItem.parseFrom(bytes)

        assertThat(parsed.positionId).isEqualTo("p1")
        assertThat(parsed.unitPriceNet.amount).isEqualTo("10.00")
        assertThat(parsed.receiptsCount).isEqualTo(1)
        assertThat(parsed.getReceipts(0).fileName).isEqualTo("r.pdf")
        assertThat(parsed.hasUnitPriceNet()).isTrue
    }
}
