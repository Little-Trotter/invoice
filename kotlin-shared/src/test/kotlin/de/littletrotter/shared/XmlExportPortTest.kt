package de.littletrotter.shared

import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.assertj.core.api.Assertions.assertThat

class XmlExportPortTest {
    @Test
    fun `generate and resolveProfile mocked`() {
        val port = mock<XmlExportPort>()
        val invoice = InvoiceDto(id = 42L, invoiceNumber = InvoiceNumberDto("INV-42"), dates = InvoiceDatesDto(paymentTermsDays = 42))
        whenever(port.generate(invoice)).thenReturn("<xml/>".toByteArray())
        whenever(port.resolveProfile(invoice)).thenReturn("urn:profile")

        val xml = port.generate(invoice)
        val profile = port.resolveProfile(invoice)
        assertThat(String(xml)).isEqualTo("<xml/>")
        assertThat(profile).isEqualTo("urn:profile")
    }
}
