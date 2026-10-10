package de.littletrotter.shared.proto

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class InvoiceProtoTest {

    @Test
    fun `build Invoice serialize and validate`() {
        val item = InvoiceItem.newBuilder()
            .setPositionId("p1")
            .setDescription("Service")
            .setQuantity("1")
            .setUnitPriceNet(Money.newBuilder().setAmount("100.00").build())
            .setTaxRate("19")
            .setNetAmount(Money.newBuilder().setAmount("100.00").build())
            .setVatAmount(Money.newBuilder().setAmount("19.00").build())
            .build()

        val recipient = Party.newBuilder()
            .setName("Recipient GmbH")
            .setStreet("Mainstr 1")
            .setPostalCode("12345")
            .setCity("Berlin")
            .setCountry("DE")
            .build()

        val invoice = Invoice.newBuilder()
            .setId(42L)
            .setInvoiceNumber(InvoiceNumber.newBuilder().setValue("INV-42").build())
            .addItems(item)
            .setNetAmount(Money.newBuilder().setAmount("100.00").build())
            .setVatAmount(Money.newBuilder().setAmount("19.00").build())
            .setGrossAmount(Money.newBuilder().setAmount("119.00").build())
            .setRecipient(recipient)
            .build()

        val parsed = Invoice.parseFrom(invoice.toByteArray())

        assertThat(parsed.itemsCount).isGreaterThan(0)
        assertThat(parsed.netAmount.amount).isEqualTo("100.00")
        assertThat(parsed.vatAmount.amount).isEqualTo("19.00")
        assertThat(parsed.grossAmount.amount).isEqualTo("119.00")
        assertThat(parsed.recipient.name).isEqualTo("Recipient GmbH")
    }
}
