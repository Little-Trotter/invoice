package de.littletrotter.xpdf.impl

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.documentjob.QuoteDocumentMark
import de.littletrotter.shared.documentjob.QuoteDocumentPayload
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.xpdf.QuotePdfResult
import de.littletrotter.xpdf.testsupport.TypstTestResource
import java.io.File
import java.time.Instant
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The offer PDF end to end through the real engines: the pinned Typst CLI renders
 * `standard-quote.typ` in a container, the stage chain stamps and stores it, and veraPDF — also
 * containerized — judges the stored bytes. German and English must both be PDF/A-3b valid and
 * carry the watermark of their copy; samples land in `target/quote-samples` for a visual check.
 * Synthetic data only.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuotePdfPipelineIT {

    @Container
    private val typst = TypstTestResource()

    @Container
    private val veraPdf = VeraPdfTestResource()

    private val creation: Instant = Instant.ofEpochSecond(1_790_000_000L)

    private val store = RecordingStore()

    private fun pipeline(): QuotePdfPipeline {
        val compiler: TypstCompiler = mock()
        whenever(compiler.compile(any())).thenAnswer { invocation ->
            val request = invocation.getArgument<TypstCompileRequest>(0)
            val outcome = typst.compile(request.templateSource, request.dataJson, request.creationTimestamp, request.files)
            if (outcome.exitCode == 0) {
                TypstCompileResult.Success(checkNotNull(outcome.pdfBytes))
            } else {
                TypstCompileResult.Failure("typst failed", outcome.stderr)
            }
        }
        val time = ClockTimeSource.fixedAt(creation)
        val vera = veraPdf.client()
        return QuotePdfPipeline(QuoteTypstRenderer(compiler, time), store, vera, PdfA3MinimalCheck(), EmbedAttachmentStage(), ReceiptAnnexBuilder(store), time)
    }

    private fun text(pdf: ByteArray): String = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it).replace('\u00A0', ' ') }

    private fun export(payload: QuoteDocumentPayload): Pair<QuotePdfResult, ByteArray> {
        val result = pipeline().export("job-1", payload)
        val pdf = store.objects.getValue(result.objectKey)
        val dir = File("target/quote-samples").apply { mkdirs() }
        File(dir, "quote-${payload.language}-${payload.mark.name.lowercase()}.pdf").writeBytes(pdf)
        return result to pdf
    }

    @ParameterizedTest(name = "{0} {1} is PDF/A-3b valid and stamped as expected")
    @CsvSource(
        "de, DRAFT, ENTWURF",
        "de, REVIEW, ENTWURF",
        "de, DISPATCH, ",
        "en, DRAFT, DRAFT",
        "en, REVIEW, DRAFT",
        "en, DISPATCH, ",
    )
    fun `offer PDFs are PDF-A-3b valid in both languages and carry the mark of their copy`(
        language: String,
        mark: QuoteDocumentMark,
        watermark: String?,
    ) {
        val (result, pdf) = export(QuoteFixtures.payload(mark, language))

        val report = checkNotNull(KositReportPayload.decode(result.validationReportJson))
        assertEquals(KositOutcome.VALID.name, report.status, report.details.joinToString { it.message })
        assertTrue(result.objectKey.startsWith("quotes/"), result.objectKey)
        val stamped = WatermarkText.of(pdf)
        if (watermark == null) {
            assertEquals("", stamped, "dispatch copy is unmarked")
        } else {
            assertTrue(stamped.contains(watermark), "watermark $watermark in: $stamped")
        }
    }

    @Test
    fun `offer content reaches the page and carries no payment block`() {
        val (_, pdf) = export(QuoteFixtures.payload())
        val printed = text(pdf)

        assertTrue(printed.contains("Angebot Nr. AN-2026-00007 – Sanierung Badezimmer"))
        assertTrue(printed.contains("Gültig bis"))
        assertTrue(printed.contains("LV-4711 · Ref. KD-991"))
        assertTrue(printed.contains("Bodenfliese 30x60 anthrazit"))
        assertTrue(printed.contains("1.310,79 €"))
        assertFalse(printed.contains("IBAN") && printed.contains("Zahlungsziel"), "an offer has no payment terms")
    }

    /** Baselines and left edges measured with [PdfTableGeometry] on the rendered page; they are the
     *  DIN 5008 points the invoice template uses, so an offer sits in the same address window. */
    @Test
    fun `letterhead address window subject and footer sit on the DIN 5008 points`() {
        val (_, pdf) = export(QuoteFixtures.payload())
        val lines = PdfTableGeometry.textLines(pdf, 0f, 600f).filter { it.page == 1 }
        fun line(prefix: String) = lines.first { it.text.startsWith(prefix) }

        assertEquals(85.48f, line("Musterwerk Handwerk GmbH").baseline, TOLERANCE)
        assertEquals(134.79f, line("Musterwerk Handwerk GmbH ·").baseline, TOLERANCE)
        assertEquals(160.05f, line("Beispiel Bau AG").baseline, TOLERANCE)
        assertEquals(176.05f, line("Hauptstraße 1").baseline, TOLERANCE)
        assertEquals(192.05f, line("50667 Köln").baseline, TOLERANCE)
        assertEquals(57f, line("Beispiel Bau AG").left, TOLERANCE)
        assertEquals(328f, line("Angebots-Nr.").left, TOLERANCE)
        assertEquals(291.67f, line("Angebot Nr.").baseline, TOLERANCE)
        assertEquals(784.79f, line("Sitz:").baseline, TOLERANCE)
    }

    @Test
    fun `identical offers render byte-identical PDFs`() {
        val first = pipeline().export("job-1", QuoteFixtures.payload())
        val second = pipeline().export("job-1", QuoteFixtures.payload())
        assertEquals(first.sha256, second.sha256)
    }

    private companion object {
        const val TOLERANCE = 0.5f
    }

    private class RecordingStore : DocumentStore {
        val objects = mutableMapOf<String, ByteArray>()
        override fun put(objectKey: String, data: ByteArray, contentType: String) {
            objects[objectKey] = data
        }
        override fun get(objectKey: String): ByteArray = objects.getValue(objectKey)
        override fun exists(objectKey: String): Boolean = objectKey in objects
        override fun enableImmutability(objectKey: String) = Unit
        override fun delete(objectKey: String) {
            objects.remove(objectKey)
        }
    }
}
