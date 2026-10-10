package de.littletrotter.xpdf.impl

import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.VeraPdfResult
import de.littletrotter.shared.VeraPdfValidatorPort
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
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

        val stage = BasePdfStage(renderer, PdfA3MinimalCheck(), mock())

        val result = stage.renderBase(makeInvoice())

        assertArrayEquals(template, result)
    }

    @Test
    fun `checkPdfA3 with skipValidation never calls veraPDF and reports a skipped stage`() {
        val vera: VeraPdfValidatorPort = mock()
        val stage = BasePdfStage(mock(), PdfA3MinimalCheck(), vera)

        val (result, label) = stage.checkPdfA3(byteArrayOf(1), "factur-x.xml", skipValidation = true)

        assertTrue(result.skipped)
        assertEquals(KositOutcome.VALID, result.outcome)
        assertEquals("skipValidation", result.skipReason)
        assertEquals("pdfa-minimal", label)
        verify(vera, never()).validate(any())
    }

    @Test
    fun `checkPdfA3 uses veraPDF as the primary check when it succeeds`() {
        val vera: VeraPdfValidatorPort = mock()
        whenever(vera.validate(any())).thenReturn(
            VeraPdfResult(KositOutcome.VALID, "PDF/A-3B", "verapdf-cli", 0, 0, emptyList()),
        )
        val stage = BasePdfStage(mock(), PdfA3MinimalCheck(), vera)

        val (result, label) = stage.checkPdfA3(byteArrayOf(1), "factur-x.xml", skipValidation = false)

        assertEquals(KositOutcome.VALID, result.outcome)
        assertFalse(result.skipped)
        assertEquals("verapdf-cli", label)
    }

    @Test
    fun `checkPdfA3 falls back to PdfA3MinimalCheck, never silently, when veraPDF errors`() {
        val vera: VeraPdfValidatorPort = mock()
        whenever(vera.validate(any())).thenReturn(
            VeraPdfResult(KositOutcome.ERROR, "PDF/A-3B", "verapdf-cli", 0, 0, emptyList(), engineError = "cli missing"),
        )
        val stage = BasePdfStage(mock(), PdfA3MinimalCheck(), vera)

        // Bytes that aren't even a real PDF — PdfA3MinimalCheck's own engine-error path (ERROR).
        val (result, label) = stage.checkPdfA3(byteArrayOf(1, 2, 3), "factur-x.xml", skipValidation = false)

        assertEquals(KositOutcome.ERROR, result.outcome)
        assertTrue(label.contains("pdfa-minimal"))
        assertTrue(label.contains("cli missing"))
    }

    // ── the optional, VERAPDF_CHECK-gated accreditation layer ─────

    @Test
    fun `T9b-04 - optionalVeraPdfLayer is absent when the switch is off`() {
        val vera: VeraPdfValidatorPort = mock()
        whenever(vera.validate(any())).thenReturn(
            VeraPdfResult(KositOutcome.VALID, "PDF/A-3B", "veraPDF 1.30.2", 0, 0, emptyList()),
        )
        val stage = BasePdfStage(mock(), PdfA3MinimalCheck(), vera)
        val outcome = stage.checkPdfA3(byteArrayOf(1), "factur-x.xml", skipValidation = false)

        assertEquals(null, stage.optionalVeraPdfLayer(outcome, enabled = false))
    }

    @Test
    fun `optionalVeraPdfLayer is absent when nothing ran at all (skipValidation)`() {
        val vera: VeraPdfValidatorPort = mock()
        val stage = BasePdfStage(mock(), PdfA3MinimalCheck(), vera)
        val outcome = stage.checkPdfA3(byteArrayOf(1), "factur-x.xml", skipValidation = true)

        assertEquals(null, stage.optionalVeraPdfLayer(outcome, enabled = true))
        verify(vera, never()).validate(any())
    }

    @Test
    fun `optionalVeraPdfLayer reuses the already-computed veraPDF result, VALID case`() {
        val vera: VeraPdfValidatorPort = mock()
        whenever(vera.validate(any())).thenReturn(
            VeraPdfResult(KositOutcome.VALID, "PDF/A-3B", "veraPDF 1.30.2", 0, 0, emptyList()),
        )
        val stage = BasePdfStage(mock(), PdfA3MinimalCheck(), vera)
        val outcome = stage.checkPdfA3(byteArrayOf(1), "factur-x.xml", skipValidation = false)

        val layer = stage.optionalVeraPdfLayer(outcome, enabled = true)

        assertEquals("VERAPDF", layer?.stage)
        assertEquals(KositOutcome.VALID, layer?.outcome)
        verify(vera, org.mockito.kotlin.times(1)).validate(any()) // never invoked a second time
    }

    // T9-05 — CLI unreachable while the switch is on: the optional layer must show ERROR, never
    // the silent PdfA3MinimalCheck fallback [checkPdfA3]'s own mandatory "PDFA" stage uses.
    @Test
    fun `T9b-05 - optionalVeraPdfLayer is ERROR, not the minimal-check fallback, when veraPDF errors`() {
        val vera: VeraPdfValidatorPort = mock()
        whenever(vera.validate(any())).thenReturn(
            VeraPdfResult(KositOutcome.ERROR, "PDF/A-3B", "veraPDF 1.30.2", 0, 0, emptyList(), engineError = "cli missing"),
        )
        val stage = BasePdfStage(mock(), PdfA3MinimalCheck(), vera)
        val outcome = stage.checkPdfA3(byteArrayOf(1, 2, 3), "factur-x.xml", skipValidation = false)

        val layer = stage.optionalVeraPdfLayer(outcome, enabled = true)

        assertEquals("VERAPDF", layer?.stage)
        assertEquals(KositOutcome.ERROR, layer?.outcome)
        assertTrue(layer?.findings.isNullOrEmpty())
    }

    // veraPDF engine-error visibility — the human-readable reason must reach the stage result,
    // not just the worker log.
    @Test
    fun `optionalVeraPdfLayer carries the engineError reason when veraPDF errors`() {
        val vera: VeraPdfValidatorPort = mock()
        whenever(vera.validate(any())).thenReturn(
            VeraPdfResult(KositOutcome.ERROR, "PDF/A-3B", "veraPDF 1.30.2", 0, 0, emptyList(), engineError = "cli missing"),
        )
        val stage = BasePdfStage(mock(), PdfA3MinimalCheck(), vera)
        val outcome = stage.checkPdfA3(byteArrayOf(1, 2, 3), "factur-x.xml", skipValidation = false)

        val layer = stage.optionalVeraPdfLayer(outcome, enabled = true)

        assertEquals("cli missing", layer?.engineError)
    }

    @Test
    fun `optionalVeraPdfLayer carries no engineError for a VALID or INVALID result`() {
        val vera: VeraPdfValidatorPort = mock()
        whenever(vera.validate(any())).thenReturn(
            VeraPdfResult(KositOutcome.VALID, "PDF/A-3B", "veraPDF 1.30.2", 0, 0, emptyList()),
        )
        val stage = BasePdfStage(mock(), PdfA3MinimalCheck(), vera)
        val outcome = stage.checkPdfA3(byteArrayOf(1), "factur-x.xml", skipValidation = false)

        val layer = stage.optionalVeraPdfLayer(outcome, enabled = true)

        assertEquals(null, layer?.engineError)
    }

    // The veraPDF failure is the VERAPDF layer's story; it must not leak
    // onto a PDFA stage that itself didn't error out via the PdfA3MinimalCheck fallback.
    @Test
    fun `checkPdfA3 PDFA stage carries no engineError when the fallback check does not error`() {
        val vera: VeraPdfValidatorPort = mock()
        whenever(vera.validate(any())).thenReturn(
            VeraPdfResult(KositOutcome.ERROR, "PDF/A-3B", "verapdf-cli", 0, 0, emptyList(), engineError = "cli missing"),
        )
        val stage = BasePdfStage(mock(), PdfA3MinimalCheck(), vera)

        // A real, parseable PDF (missing XMP/attachment) — PdfA3MinimalCheck.check returns
        // INVALID here, not ERROR, exercising the "outcome != ERROR" branch of the rule.
        val plainPdf = org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
            doc.addPage(org.apache.pdfbox.pdmodel.PDPage())
            val os = java.io.ByteArrayOutputStream()
            doc.save(os)
            os.toByteArray()
        }
        val (result, _) = stage.checkPdfA3(plainPdf, "factur-x.xml", skipValidation = false)

        assertEquals(KositOutcome.INVALID, result.outcome)
        assertEquals(null, result.engineError)
    }

    @Test
    fun `checkPdfA3 PDFA stage carries the veraPDF reason when the fallback check also errors`() {
        val vera: VeraPdfValidatorPort = mock()
        whenever(vera.validate(any())).thenReturn(
            VeraPdfResult(KositOutcome.ERROR, "PDF/A-3B", "verapdf-cli", 0, 0, emptyList(), engineError = "cli missing"),
        )
        val stage = BasePdfStage(mock(), PdfA3MinimalCheck(), vera)

        // Bytes that aren't a real PDF — PdfA3MinimalCheck's own engine-error path (ERROR).
        val (result, _) = stage.checkPdfA3(byteArrayOf(1, 2, 3), "factur-x.xml", skipValidation = false)

        assertEquals(KositOutcome.ERROR, result.outcome)
        assertEquals("cli missing", result.engineError)
    }
}
