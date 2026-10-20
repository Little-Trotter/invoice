package de.littletrotter.xpdf.impl

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** PO-reported job 13 follow-up — [PdfA3ColorSpaceNormalizer] unit tests: it adds an sRGB output
 *  intent to a document that lacks one (the Factur-X engine's own defect) and leaves a document
 *  that already carries one untouched. The actual ISO 19005-3 clause 6.2.4.3 conformance proof
 *  (real veraPDF CLI) lives in [VeraPdfConformanceIT]. */
class PdfA3ColorSpaceNormalizerTest {

    /** A document with DeviceRGB content and no output intent — the shape veraPDF flags under
     *  clause 6.2.4.3, mirroring the engine's own reported defect. */
    private fun pdfWithoutOutputIntent(): ByteArray = PDDocument().use { doc ->
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.setNonStrokingColor(Color(128, 128, 128))
            cs.addRect(10f, 10f, 100f, 100f)
            cs.fill()
        }
        val out = ByteArrayOutputStream()
        doc.save(out)
        out.toByteArray()
    }

    private fun pdfWithOutputIntent(): ByteArray = PDDocument().use { doc ->
        doc.addPage(PDPage())
        val profile = ICC_Profile.getInstance(ColorSpace.CS_sRGB)
        val outputIntent = PDOutputIntent(doc, ByteArrayInputStream(profile.data))
        outputIntent.info = "sRGB IEC61966-2.1"
        outputIntent.outputCondition = "sRGB IEC61966-2.1"
        outputIntent.outputConditionIdentifier = "sRGB IEC61966-2.1"
        outputIntent.registryName = "http://www.color.org"
        doc.documentCatalog.addOutputIntent(outputIntent)
        val out = ByteArrayOutputStream()
        doc.save(out)
        out.toByteArray()
    }

    @Test
    fun `ensureSrgbOutputIntent adds an sRGB output intent when the document has none`() {
        val original = pdfWithoutOutputIntent()
        Loader.loadPDF(original).use { doc ->
            assertTrue(doc.documentCatalog.outputIntents.isEmpty(), "fixture must start without an output intent")
        }

        val normalized = PdfA3ColorSpaceNormalizer.ensureSrgbOutputIntent(original)

        Loader.loadPDF(normalized).use { doc ->
            val intents = doc.documentCatalog.outputIntents
            assertEquals(1, intents.size)
            assertEquals("sRGB IEC61966-2.1", intents.first().info)
            assertEquals(1, doc.numberOfPages, "normalization must not add or remove pages")
        }
    }

    @Test
    fun `ensureSrgbOutputIntent returns the same bytes unchanged when an output intent already exists`() {
        val original = pdfWithOutputIntent()

        val result = PdfA3ColorSpaceNormalizer.ensureSrgbOutputIntent(original)

        assertSame(original, result, "an existing output intent must make this a true no-op, not a re-save")
    }
}
