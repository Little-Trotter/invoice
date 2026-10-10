package de.littletrotter.shared

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class InvoiceDtoTest {

    @Test
    fun `default values are sane`() {
        val m = Money.ZERO
        assertEquals(0, BigDecimal.ZERO.compareTo(m.amount))

        val inv = InvoiceDto()
        assertNotNull(inv.invoiceNumber)
        assertEquals(0, BigDecimal.ZERO.compareTo(inv.totals.netAmount.amount))
    }

    @Test
    fun `serialize and deserialize InvoiceDto`() {
        val mapper = jacksonObjectMapper()
        val item = InvoiceItemDto(
            positionId = "1",
            description = "Service",
            quantity = BigDecimal("2"),
            unitPriceNet = Money(BigDecimal("10.50")),
            netAmount = Money(BigDecimal("21.00")),
            vatAmount = Money(BigDecimal("3.99")),
            taxRatePercent = BigDecimal("19.00"),
        )
        val invoice = InvoiceDto(
            id = 7L,
            invoiceNumber = InvoiceNumberDto("INV-7"),
            meta = InvoiceMetaDto(freeText = "Bitte Projektnummer angeben."),
            items = listOf(item),
            totals = InvoiceTotalsDto(
                netAmount = Money(BigDecimal("21.00")),
                vatAmount = Money(BigDecimal("3.99")),
                grossAmount = Money(BigDecimal("24.99")),
            ),
        )

        val json = mapper.writeValueAsString(invoice)
        assertTrue(json.contains("INV-7"))
        assertTrue(json.contains("Bitte Projektnummer angeben."))

        val read: InvoiceDto = mapper.readValue(json)
        assertEquals(invoice.invoiceNumber.value, read.invoiceNumber.value)
        assertEquals(1, read.items.size)
        assertEquals(BigDecimal("24.99"), read.totals.grossAmount.amount)
        assertEquals("Bitte Projektnummer angeben.", read.meta.freeText)
    }

    @Test
    fun `freeText survives round trip and defaults to null`() {
        val mapper = jacksonObjectMapper()
        // Default: null
        assertNull(InvoiceDto().meta.freeText)
        // Round trip with value
        val withText = InvoiceDto(meta = InvoiceMetaDto(freeText = "Zeile 1\nZeile 2"))
        val json = mapper.writeValueAsString(withText)
        val read: InvoiceDto = mapper.readValue(json)
        assertEquals("Zeile 1\nZeile 2", read.meta.freeText)
        // Legacy payload without the field deserializes to null (Jackson ignores unknown fields)
        val legacy = """{"invoiceNumber":{"value":"INV-9"},"items":[]}"""
        val readLegacy: InvoiceDto = mapper.readValue(legacy)
        assertNull(readLegacy.meta.freeText)
    }

    @Test
    fun `data class copy and equality`() {
        val inv1 = InvoiceDto(id = 1L, invoiceNumber = InvoiceNumberDto("A"))
        val inv2 = inv1.copy(id = 2L)
        assertNotEquals(inv1, inv2)
        assertEquals("A", inv2.invoiceNumber.value)
    }
}
