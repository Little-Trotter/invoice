package de.littletrotter.shared.proto

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class ProtoEdgeCasesTest {

    @Test
    fun `mergeFrom preserves fields and allows override`() {
        val item = InvoiceItem.newBuilder().setPositionId("p1").setDescription("d1").build()
        val inv1 = Invoice.newBuilder().setId(1L).addItems(item).setInvoiceNumber(InvoiceNumber.newBuilder().setValue("INV-1").build()).build()

        val inv2 = Invoice.newBuilder().mergeFrom(inv1).setId(2L).build()

        // merged values present, overridden id kept
        assertThat(inv2.id).isEqualTo(2L)
        assertThat(inv2.itemsCount).isEqualTo(1)
        assertThat(inv2.invoiceNumber.value).isEqualTo("INV-1")
    }

    @Test
    fun `jobresult map collision and merge behavior`() {
        val b = JobResult.newBuilder()
            .putResultRefs("k", "v1")
            .putResultRefs("k", "v2")
            .build()

        // last put wins
        assertThat(b.resultRefsMap["k"]).isEqualTo("v2")

        val b1 = JobResult.newBuilder().putAllResultRefs(mapOf("a" to "1")).build()
        val b2 = JobResult.newBuilder().mergeFrom(b1).putResultRefs("b", "2").build()

        assertThat(b2.resultRefsMap).containsEntry("a", "1")
        assertThat(b2.resultRefsMap).containsEntry("b", "2")

        val removed = b2.toBuilder().removeResultRefs("a").build()
        assertThat(removed.resultRefsMap).doesNotContainKey("a")
    }

    @Test
    fun `clear and has semantics for nested messages`() {
        val itemBuilder = InvoiceItem.newBuilder()
            .setPositionId("pX")
            .setUnitPriceNet(Money.newBuilder().setAmount("5.00").build())
            .setNetAmount(Money.newBuilder().setAmount("5.00").build())

        val item = itemBuilder.build()
        assertThat(item.hasUnitPriceNet()).isTrue

        val cleared = item.toBuilder().clearUnitPriceNet().build()
        assertThat(cleared.hasUnitPriceNet()).isFalse
    }

    @Test
    fun `toBuilder modifications preserve other fields`() {
        val r = Receipt.newBuilder().setFileName("a.pdf").setObjectKey("k1").build()
        val modified = r.toBuilder().setFileName("b.pdf").build()
        assertThat(modified.objectKey).isEqualTo("k1")
        assertThat(modified.fileName).isEqualTo("b.pdf")
    }
}
