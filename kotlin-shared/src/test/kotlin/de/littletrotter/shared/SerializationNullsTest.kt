package de.littletrotter.shared

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SerializationNullsTest {

    @Test
    fun `missing optional fields do not break deserialization`() {
        val json = "{ \"id\": 5, \"invoiceNumber\": {\"value\": \"X\"} }"
        val mapper = jacksonObjectMapper()
        val inv: InvoiceDto = mapper.readValue(json)
        assertEquals(5L, inv.id)
        assertEquals("X", inv.invoiceNumber.value)
        // other fields should be defaults — Money.ZERO carries scale 2, so compare by value
        // (BigDecimal.equals is scale-sensitive; Money.sameValueAs / compareTo are not).
        assertEquals(0, BigDecimal.ZERO.compareTo(inv.totals.netAmount.amount))
    }

    @Test
    fun `invoice item with zero quantity keeps unit price`() {
        val item = InvoiceItemDto(quantity = BigDecimal.ZERO, unitPriceNet = Money(BigDecimal("12.34")), taxRatePercent = BigDecimal.ZERO)
        assertEquals(BigDecimal.ZERO, item.quantity)
        assertEquals(BigDecimal("12.34"), item.unitPriceNet.amount)
    }
}
