package de.littletrotter.xpdf.impl

import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceMetaDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.xpdf.testsupport.OutputIntentTestKeys
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.util.Optional

/** The stamp choice (cancellation, draft, grant, brand, demo text), rendered on a one-page PDF to read the result. */
class ExportWatermarkSelectorTest {

    private val time = ClockTimeSource.fixedAt(Instant.parse("2026-01-15T10:30:00Z"))

    private fun invoice(draft: Boolean = false, cancellation: Boolean = false) = InvoiceDto(
        invoiceNumber = InvoiceNumberDto("INV-QA10-2"),
        items = emptyList(),
        meta = InvoiceMetaDto(documentLanguage = "de"),
        senderProfile = OutputIntentTestKeys.SELLER,
        draft = draft,
        cancellation = cancellation,
    )

    private fun selector(demo: String? = null) =
        ExportWatermarkSelector(OutputIntentTestKeys.verifier, time, Optional.ofNullable(demo))

    private fun grant(notAfter: LocalDate? = null) = OutputIntentTestKeys.grantFor(invoice(), notAfter)

    /** The diagonal text a customer would see when [stamp] is drawn on a blank page. */
    private fun stamped(stamp: PdfWatermarkStamper.Stamp?): String {
        val bytes = PDDocument().use { doc ->
            doc.addPage(PDPage())
            if (stamp != null) PdfWatermarkStamper.stamp(doc, stamp)
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        return WatermarkText.of(bytes)
    }

    @Test
    fun `cancellation wins over draft`() {
        val text = stamped(selector().select(invoice(draft = true, cancellation = true), grant()))

        assertTrue(text.startsWith("STORNO"), text)
    }

    @Test
    fun `a draft is marked even where a grant covers the seller`() {
        val text = stamped(selector().select(invoice(draft = true), grant()))

        assertTrue(text.startsWith("ENTWURF"), text)
    }

    @Test
    fun `a covering grant leaves the document unmarked`() {
        assertNull(selector().select(invoice(), grant()))
    }

    @Test
    fun `no grant yields the brand mark`() {
        assertTrue(stamped(selector().select(invoice(), null)).startsWith("LittleTrotter"))
    }

    @Test
    fun `an expired grant falls back to the brand mark`() {
        val expired = grant(LocalDate.of(2026, 1, 14))

        assertTrue(stamped(selector().select(invoice(), expired)).startsWith("LittleTrotter"))
    }

    @Test
    fun `a configured demo text is stamped alone where a grant covers the seller`() {
        val text = stamped(selector("Little Trotter · Demo").select(invoice(), grant()))

        assertEquals("LittleTrotter·Demo", text)
    }

    @Test
    fun `a configured demo text joins the brand mark`() {
        val text = stamped(selector("Little Trotter · Demo").select(invoice(), null))

        assertTrue(text.startsWith("LittleTrotterLittleTrotter·Demo"), text)
    }

    @Test
    fun `without a configured demo text the brand mark stands alone`() {
        val text = stamped(ExportWatermarkSelector(OutputIntentTestKeys.verifier, time).select(invoice(), null))

        assertTrue(text.startsWith("LittleTrotter"), text)
        assertFalse(text.contains("Demo"), text)
    }

    @Test
    fun `without a configured demo text a covering grant leaves the document unmarked`() {
        assertNull(ExportWatermarkSelector(OutputIntentTestKeys.verifier, time).select(invoice(), grant()))
    }
}
