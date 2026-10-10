package de.littletrotter.xpdf.impl

import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.ReceiptDto
import de.littletrotter.xpdf.validCiiXml
import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal

/**
 * [MustangPdfRenderer] on its own, collaborators mocked (unit layer). Proves the
 * Mustang-route-only PDF finishing step (ZUGFeRD embedding, nothing else) needs
 * nothing beyond [BasePdfStage] and [XmlExportServiceImpl] — and that a beleg linked to the
 * invoice never adds a second embedded file to the PDF, since it is already carried once, as
 * Base64 inside the embedded XML itself (BG-24).
 */
class MustangPdfRendererTest {

    private fun invoice(): InvoiceDto = InvoiceDto(invoiceNumber = InvoiceNumberDto("INV-QA10-4"), items = emptyList())

    // ZUGFeRDExporterFromA3 walks the page's own Resources dictionary while embedding — a bare
    // PDPage() has none at all (null), so it needs an explicit (even empty) one, unlike the
    // plain-PDF fixtures BasePdfStageTest/ExportCompletionStageTest use for PDFBox-only checks.
    private fun minimalPdfBytes(): ByteArray = org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
        doc.addPage(org.apache.pdfbox.pdmodel.PDPage().apply { resources = org.apache.pdfbox.pdmodel.PDResources() })
        val os = java.io.ByteArrayOutputStream()
        doc.save(os)
        os.toByteArray()
    }

    private fun sut(basePdfStage: BasePdfStage, xmlExportService: XmlExportServiceImpl = mock()): MustangPdfRenderer {
        whenever(xmlExportService.resolveProfile(any())).thenReturn("EN16931")
        return MustangPdfRenderer(basePdfStage, xmlExportService)
    }

    @Test
    fun `renderPdfWithZugferd embeds the XML into the shared base document`() {
        val base = minimalPdfBytes()
        val basePdfStage: BasePdfStage = mock()
        whenever(basePdfStage.renderBase(any())).thenReturn(base)

        val result = sut(basePdfStage).renderPdfWithZugferd(invoice(), validCiiXml().toByteArray())

        assertTrue(result.isNotEmpty())
        assertNotEquals(base.size, result.size) // ZUGFeRD embedding changed the byte stream
    }

    @Test
    fun `renderPdfWithZugferd embeds exactly one file, the ZUGFeRD XML, even when the invoice carries receipts`() {
        val base = minimalPdfBytes()
        val basePdfStage: BasePdfStage = mock()
        whenever(basePdfStage.renderBase(any())).thenReturn(base)
        // A beleg linked to a position must never turn into a second embedded file here —
        // it is already carried once, as Base64 inside the XML this method embeds.
        val invoiceWithReceipt = invoice().copy(
            items = listOf(
                InvoiceItemDto(
                    description = "Work",
                    quantity = BigDecimal.ONE,
                    taxRatePercent = BigDecimal("19"),
                    unitPriceNet = Money(BigDecimal("100")),
                    receipts = listOf(ReceiptDto(fileName = "beleg.pdf", objectKey = "receipt-pool/beleg.pdf")),
                ),
            ),
        )
        val sut = sut(basePdfStage)

        val result = sut.renderPdfWithZugferd(invoiceWithReceipt, validCiiXml().toByteArray())

        Loader.loadPDF(result).use { document ->
            val embeddedFiles = document.documentCatalog.names?.embeddedFiles?.names
            assertNotNull(embeddedFiles, "the ZUGFeRD XML must be embedded")
            assertEquals(1, embeddedFiles!!.size, "exactly one embedded file — the beleg must not appear a second time")
            assertTrue(
                embeddedFiles.containsKey(sut.expectedZugferdAttachmentFilename("EN16931")),
                "the one embedded file must be the ZUGFeRD XML (vorhanden: ${embeddedFiles.keys})",
            )
        }
    }

    @Test
    fun `renderPdfWithZugferd propagates a failure from the base-PDF stage unchanged`() {
        val basePdfStage: BasePdfStage = mock()
        whenever(basePdfStage.renderBase(any())).thenThrow(RuntimeException("base render failed"))

        assertThrows(RuntimeException::class.java) {
            sut(basePdfStage).renderPdfWithZugferd(invoice(), byteArrayOf(1))
        }
    }

    @Test
    fun `expectedZugferdAttachmentFilename is xrechnung xml for the XRechnung profile`() {
        val sut = sut(mock())
        assertEquals("xrechnung.xml", sut.expectedZugferdAttachmentFilename("XRechnung"))
    }

    @Test
    fun `expectedZugferdAttachmentFilename is factur-x xml for every other profile`() {
        val sut = sut(mock())
        assertEquals("factur-x.xml", sut.expectedZugferdAttachmentFilename("EN16931"))
    }

    @Test
    fun `expectedZugferdAttachmentFilename is factur-x xml for the EXTENDED profile`() {
        val sut = sut(mock())
        assertEquals("factur-x.xml", sut.expectedZugferdAttachmentFilename("EXTENDED"))
    }
}
