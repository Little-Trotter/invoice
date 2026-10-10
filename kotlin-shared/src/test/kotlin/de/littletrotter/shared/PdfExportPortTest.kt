package de.littletrotter.shared

import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.assertj.core.api.Assertions.assertThat

class PdfExportPortTest {
    @Test
    fun `export result and port mock`() {
        val port = mock<PdfExportPort>()
        val invoice = InvoiceDto(id = 1L, invoiceNumber = InvoiceNumberDto("INV-1"), dates = InvoiceDatesDto(paymentTermsDays = 1))
        val res = ExportResult("obj-1", "obj-1.xml", true, "report", "sha")
        whenever(port.performExport(invoice, "job-1")).thenReturn(res)

        val got = port.performExport(invoice, "job-1")
        assertThat(got.objectKey).isEqualTo("obj-1")
        assertThat(got.kositSuccess).isTrue
    }
}
