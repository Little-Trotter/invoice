package de.littletrotter.shared

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class InvoiceDtoEdgeCasesTest {

    @Test
    fun `serialize invoice with null parties`() {
        val mapper = jacksonObjectMapper()
        val invoice = InvoiceDto(
            id = 100L,
            senderProfile = null,
            recipient = null,
            items = emptyList()
        )

        val json = mapper.writeValueAsString(invoice)
        assertTrue(json.contains("\"id\":100"))

        val read: InvoiceDto = mapper.readValue(json)
        assertNull(read.senderProfile)
        assertNull(read.recipient)
        assertTrue(read.items.isEmpty())
    }

    @Test
    fun `money scale and equality checks`() {
        val m1 = Money(BigDecimal("10.0"))
        val m2 = Money(BigDecimal("10.00"))
        // Unlike a raw BigDecimal (whose equals is scale-sensitive), Money.equals compares by
        // value — 10.0 and 10.00 mean the same amount, so they must be equal here even
        // though the underlying BigDecimal scales differ.
        assertEquals(m1, m2)
        assertEquals(m1.hashCode(), m2.hashCode())

        assertEquals(Money(BigDecimal("10")), Money(BigDecimal("10")))
    }

    @Test
    fun `export result equality and hashCode stable`() {
        val a = ExportResult(objectKey = "o1", zugferdXmlKey = "o1.xml", kositSuccess = true, kositReport = "", sha256 = "abc")
        val b = ExportResult(objectKey = "o1", zugferdXmlKey = "o1.xml", kositSuccess = true, kositReport = "", sha256 = "abc")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
