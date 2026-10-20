package de.littletrotter.xpdf.impl

import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.KositOutcome
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [BasePdfStage] is the pulled-out seam Mustang and (once built)
 * a Factur-X caller share; see the class doc for why this exists. These tests exercise the class
 * on its own, independent of [PdfExportServiceImpl]/ZUGFeRD — proving the stage's API needs
 * nothing Mustang-specific to run, which is the whole point of the extraction.
 */
class BasePdfStageTest {

    private fun makeInvoice(): InvoiceDto = InvoiceDto(invoiceNumber = InvoiceNumberDto("INV-BASE-1"), items = emptyList())

    @Test
    fun `renderBase renders the template unchanged, with no engine-specific step`() {
        val template = byteArrayOf(1, 2, 3)

        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(template)

        val stage = BasePdfStage(renderer, PdfA3MinimalCheck(), ReceiptAnnexBuilder(mock()), ExportStageTimers(SimpleMeterRegistry()))

        val result = stage.renderBase(makeInvoice())

        assertArrayEquals(template, result)
    }

    @Test
    fun `renderBase appends the receipt pages behind the rendered invoice`() {
        val single = org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
            doc.addPage(org.apache.pdfbox.pdmodel.PDPage())
            java.io.ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(single)
        val store: de.littletrotter.shared.DocumentStore = mock()
        whenever(store.get("r/a.pdf")).thenReturn(single)
        val invoice = makeInvoice().copy(receipts = listOf(de.littletrotter.shared.ReceiptDto("a.pdf", "r/a.pdf")))

        val result = BasePdfStage(renderer, PdfA3MinimalCheck(), ReceiptAnnexBuilder(store), ExportStageTimers(SimpleMeterRegistry())).renderBase(invoice)

        org.apache.pdfbox.Loader.loadPDF(result).use { assertEquals(2, it.numberOfPages) }
    }

    @Test
    fun `renderBase without receipts times render only, annex counts as skipped`() {
        val registry = SimpleMeterRegistry()
        val timers = ExportStageTimers(registry)
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(byteArrayOf(1))
        val stage = BasePdfStage(renderer, PdfA3MinimalCheck(), ReceiptAnnexBuilder(mock()), timers)

        timers.job(makeInvoice()) { stage.renderBase(makeInvoice()) }

        assertEquals(1L, registry.find("xpdf.export.stage").tag("stage", "render").timer()?.count())
        assertEquals(null, registry.find("xpdf.export.stage").tag("stage", "annex").timer())
    }

    private fun plainPdf(): ByteArray = org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
        doc.addPage(org.apache.pdfbox.pdmodel.PDPage())
        java.io.ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    @Test
    fun `checkPdfA3 with skipValidation reports a skipped stage`() {
        val stage = BasePdfStage(mock(), PdfA3MinimalCheck(), ReceiptAnnexBuilder(mock()), ExportStageTimers(SimpleMeterRegistry()))

        val (result, label) = stage.checkPdfA3(byteArrayOf(1), "factur-x.xml", skipValidation = true)

        assertTrue(result.skipped)
        assertEquals(KositOutcome.VALID, result.outcome)
        assertEquals("skipValidation", result.skipReason)
        assertEquals("pdfa-minimal", label)
    }

    @Test
    fun `checkPdfA3 runs the minimal check and names it as the engine`() {
        val stage = BasePdfStage(mock(), PdfA3MinimalCheck(), ReceiptAnnexBuilder(mock()), ExportStageTimers(SimpleMeterRegistry()))

        // A parseable PDF without XMP and attachment: a document defect, not an engine failure.
        val (result, label) = stage.checkPdfA3(plainPdf(), "factur-x.xml", skipValidation = false)

        assertEquals("PDFA", result.stage)
        assertEquals(KositOutcome.INVALID, result.outcome)
        assertTrue(result.findings.isNotEmpty())
        assertEquals("pdfa-minimal", label)
    }

    @Test
    fun `checkPdfA3 reports bytes that are no PDF as an engine ERROR`() {
        val stage = BasePdfStage(mock(), PdfA3MinimalCheck(), ReceiptAnnexBuilder(mock()), ExportStageTimers(SimpleMeterRegistry()))

        val (result, _) = stage.checkPdfA3(byteArrayOf(1, 2, 3), "factur-x.xml", skipValidation = false)

        assertEquals(KositOutcome.ERROR, result.outcome)
    }

    @Test
    fun `checkPdfA3 times pdfa, but nothing when validation is skipped`() {
        val registry = SimpleMeterRegistry()
        val timers = ExportStageTimers(registry)
        val stage = BasePdfStage(mock(), PdfA3MinimalCheck(), ReceiptAnnexBuilder(mock()), timers)

        timers.job(makeInvoice()) {
            stage.checkPdfA3(byteArrayOf(1), "factur-x.xml", skipValidation = true)
            assertEquals(null, registry.find("xpdf.export.stage").timer())
            stage.checkPdfA3(byteArrayOf(1), "factur-x.xml", skipValidation = false)
        }

        assertEquals(1L, registry.find("xpdf.export.stage").tag("stage", "pdfa").timer()?.count())
        assertEquals(null, registry.find("xpdf.export.stage").tag("stage", "verapdf").timer())
    }

    private fun singlePagePdf(): ByteArray = org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
        doc.addPage(org.apache.pdfbox.pdmodel.PDPage())
        java.io.ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    private val brandStamp = PdfWatermarkStamper.markStamp(PdfWatermarkStamper.BRAND_MARK, "de")

    @Test
    fun `renderBase stamps invoice and receipt pages in the same pass and times annex and watermark`() {
        val registry = SimpleMeterRegistry()
        val timers = ExportStageTimers(registry)
        val single = singlePagePdf()
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(single)
        val store: de.littletrotter.shared.DocumentStore = mock()
        whenever(store.get("r/a.pdf")).thenReturn(single)
        val invoice = makeInvoice().copy(receipts = listOf(de.littletrotter.shared.ReceiptDto("a.pdf", "r/a.pdf")))
        val stage = BasePdfStage(renderer, PdfA3MinimalCheck(), ReceiptAnnexBuilder(store), timers)

        val result = timers.job(invoice) { stage.renderBase(invoice, brandStamp) }

        org.apache.pdfbox.Loader.loadPDF(result).use { assertEquals(2, it.numberOfPages) }
        assertEquals(2, WatermarkText.of(result).windowed("LittleTrotter".length).count { it == "LittleTrotter" })
        assertEquals(1L, registry.find("xpdf.export.stage").tag("stage", "annex").timer()?.count())
        assertEquals(1L, registry.find("xpdf.export.stage").tag("stage", "watermark").timer()?.count())
    }

    @Test
    fun `renderBase stamps an invoice without receipts and times only the watermark`() {
        val registry = SimpleMeterRegistry()
        val timers = ExportStageTimers(registry)
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(singlePagePdf())
        val stage = BasePdfStage(renderer, PdfA3MinimalCheck(), ReceiptAnnexBuilder(mock()), timers)

        val result = timers.job(makeInvoice()) { stage.renderBase(makeInvoice(), brandStamp) }

        assertTrue(WatermarkText.of(result).contains("LittleTrotter"))
        assertEquals(1L, registry.find("xpdf.export.stage").tag("stage", "watermark").timer()?.count())
        assertEquals(null, registry.find("xpdf.export.stage").tag("stage", "annex").timer())
    }
}
