package de.littletrotter.shared.proto

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import com.google.protobuf.InvalidProtocolBufferException

class InvoiceBuilderProtoTest {
    @Test
    fun `exhaustive invoice builder usage`() {
        val item = InvoiceItem.newBuilder()
            .setPositionId("p1")
            .setDescription("Service")
            .setQuantity("3")
            .setUnitPriceNet(Money.newBuilder().setAmount("33.33").build())
            .setTaxRate("19")
            .setNetAmount(Money.newBuilder().setAmount("99.99").build())
            .setVatAmount(Money.newBuilder().setAmount("18.99").build())
            .build()

        val sender = Party.newBuilder()
            .setName("Sender AG")
            .setStreet("Senderstr 1")
            .setPostalCode("10000")
            .setCity("Munich")
            .setCountry("DE")
            .setVatId("DE123456")
            .setIban("DEIBAN")
            .setBic("BIC")
            .setTaxNumber("TAX123")
            .build()

        val invoice = Invoice.newBuilder()
            .setId(777L)
            .setInvoiceNumber(InvoiceNumber.newBuilder().setValue("INV-777").build())
            .setInvoiceDate("2026-04-03")
            .setServicePeriodFrom("2026-03-01")
            .setServicePeriodTo("2026-03-31")
            .setDueDate("2026-04-30")
            .setPaymentTermsDays(30)
            .setSenderProfile(sender)
            .addItems(item)
            .setNetAmount(Money.newBuilder().setAmount("99.99").build())
            .setVatAmount(Money.newBuilder().setAmount("18.99").build())
            .setGrossAmount(Money.newBuilder().setAmount("118.98").build())
            .build()

        val bytes = invoice.toByteArray()
        val parsed = Invoice.parseFrom(bytes)

        assertThat(parsed.id).isEqualTo(777L)
        assertThat(parsed.itemsCount).isEqualTo(1)
        assertThat(parsed.senderProfile.name).isEqualTo("Sender AG")
        assertThat(parsed.netAmount.amount).isEqualTo("99.99")
    }

    @Test
    fun `parse invalid bytes throws`() {
        try {
            Invoice.parseFrom(byteArrayOf(1, 2, 3))
            throw AssertionError("Expected InvalidProtocolBufferException")
        } catch (ex: InvalidProtocolBufferException) {
            // expected
        }
    }
}
