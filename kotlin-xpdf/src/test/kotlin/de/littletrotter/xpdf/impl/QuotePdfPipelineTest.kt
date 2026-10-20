package de.littletrotter.xpdf.impl

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.VeraPdfResult
import de.littletrotter.shared.VeraPdfValidatorPort
import de.littletrotter.shared.documentjob.QuoteDocumentAttachmentRef
import de.littletrotter.shared.documentjob.QuoteDocumentMark
import de.littletrotter.shared.documentjob.QuoteDocumentReceiptRef
import de.littletrotter.shared.receipt.OrderedReceipt
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.shared.time.ClockTimeSource
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.time.Instant

/** The stage chain around a stubbed render: mark per copy, PDF/A verdict, storage and report. */
class QuotePdfPipelineTest {

    private val renderer: QuoteTypstRenderer = mock()
    private val store: DocumentStore = mock()
    private val vera: VeraPdfValidatorPort = mock()
    private val annex: ReceiptAnnexBuilder = mock()
    private val time = ClockTimeSource.fixedAt(Instant.ofEpochSecond(1_790_000_000L))

    private fun onePagePdf(): ByteArray = PDDocument().use { doc ->
        doc.addPage(PDPage())
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    private fun veraResult(outcome: KositOutcome, engineError: String? = null, findings: List<ValidationMessage> = emptyList()) =
        VeraPdfResult(outcome, "3b", "veraPDF 1.30", 0, 0, findings, engineError)

    private fun pipeline(demoText: String? = null): QuotePdfPipeline {
        whenever(renderer.render(any())).thenReturn(onePagePdf())
        return QuotePdfPipeline(
            renderer, store, vera, PdfA3MinimalCheck(), EmbedAttachmentStage(), annex, time, java.util.Optional.ofNullable(demoText),
        )
    }

    private fun stored(): ByteArray {
        val bytes = argumentCaptor<ByteArray>()
        verify(store).put(any(), bytes.capture(), eq("application/pdf"))
        return bytes.firstValue
    }

    @Test
    fun `a dispatch copy is stored under quotes unmarked and reported valid by veraPDF`() {
        whenever(vera.validate(any())).thenReturn(veraResult(KositOutcome.VALID))

        val result = pipeline().export("job-9", QuoteFixtures.payload(QuoteDocumentMark.DISPATCH))

        assertEquals("quotes/8f6c0f2e-0000-4000-8000-000000000001/dispatch-job-9.pdf", result.objectKey)
        assertEquals(sha256Hex(stored()), result.sha256)
        assertEquals("", WatermarkText.of(stored()))
        val report = checkNotNull(KositReportPayload.decode(result.validationReportJson))
        assertEquals("VALID", report.status)
        assertEquals("veraPDF 1.30", report.validationRun?.validatorVersions?.get("pdfa"))
        assertEquals(listOf("PDFA"), report.validationRun?.stages?.map { it.stage })
    }

    @ParameterizedTest(name = "{1} copy in {0}")
    @CsvSource("de, DRAFT, ENTWURF", "de, REVIEW, ENTWURF", "en, DRAFT, DRAFT", "en, REVIEW, DRAFT")
    fun `draft and review copies carry the invoice draft mark in the document language`(language: String, mark: QuoteDocumentMark, word: String) {
        whenever(vera.validate(any())).thenReturn(veraResult(KositOutcome.VALID))

        pipeline().export("j", QuoteFixtures.payload(mark, language))

        assertTrue(WatermarkText.of(stored()).contains(word), WatermarkText.of(stored()))
    }

    @ParameterizedTest(name = "{0} copy")
    @CsvSource("DISPATCH, LittleTrotter\u00B7Demo", "DRAFT, ENTWURFLittleTrotter\u00B7Demo")
    fun `a configured demo text marks every copy, the otherwise unmarked dispatch copy included`(mark: QuoteDocumentMark, expectedPrefix: String) {
        whenever(vera.validate(any())).thenReturn(veraResult(KositOutcome.VALID))

        pipeline(demoText = "Little Trotter \u00B7 Demo").export("j", QuoteFixtures.payload(mark))

        assertTrue(WatermarkText.of(stored()).startsWith(expectedPrefix), WatermarkText.of(stored()))
    }

    @Test
    fun `veraPDF findings make the verdict invalid but still store the document`() {
        val finding = ValidationMessage(severity = "ERROR", message = "6.2.4.3", stage = "PDFA", validator = "verapdf")
        whenever(vera.validate(any())).thenReturn(veraResult(KositOutcome.INVALID, findings = listOf(finding)))

        val result = pipeline().export("j", QuoteFixtures.payload())

        val report = checkNotNull(KositReportPayload.decode(result.validationReportJson))
        assertEquals("INVALID", report.status)
        assertEquals("6.2.4.3", report.validationRun?.stages?.single()?.findings?.single()?.message)
    }

    @Test
    fun `an unavailable veraPDF falls back to the minimal XMP check without demanding an attachment`() {
        whenever(vera.validate(any())).thenReturn(veraResult(KositOutcome.ERROR, engineError = "no CLI"))

        val result = pipeline().export("j", QuoteFixtures.payload())

        val report = checkNotNull(KositReportPayload.decode(result.validationReportJson))
        val label = report.validationRun?.validatorVersions?.get("pdfa").orEmpty()
        assertTrue(label.startsWith("pdfa-minimal"), label)
        assertTrue(label.contains("no CLI"))
        // the blank test page has no PDF/A identification: the only finding is the XMP one, never a missing attachment
        val messages = report.validationRun?.stages?.single()?.findings?.map { it.message }.orEmpty()
        assertTrue(messages.isNotEmpty() && messages.none { it.contains("embedded") }, messages.toString())
        assertFalse(report.status == "VALID")
    }

    @Test
    fun `an unreadable PDF under an unavailable veraPDF reports the engine error`() {
        whenever(vera.validate(any())).thenReturn(veraResult(KositOutcome.ERROR, engineError = "no CLI"))
        whenever(renderer.render(any())).thenReturn(onePagePdf())
        val pipeline = QuotePdfPipeline(renderer, store, vera, mock<PdfA3MinimalCheck>().also {
            whenever(it.check(any(), org.mockito.kotlin.anyOrNull())).thenReturn(PdfA3CheckResult(KositOutcome.ERROR, emptyList()))
        }, EmbedAttachmentStage(), annex, time)

        val result = pipeline.export("j", QuoteFixtures.payload())

        val stage = checkNotNull(KositReportPayload.decode(result.validationReportJson)).validationRun?.stages?.single()
        assertEquals("no CLI", stage?.engineError)
        assertEquals(KositOutcome.ERROR, stage?.outcome)
    }

    private val xml = "<Quotation>synthetic</Quotation>".toByteArray()

    private fun attachmentRef(sha256: String = sha256Hex(xml)) =
        QuoteDocumentAttachmentRef("quotes/q/quotation.ubl.xml", sha256, "quotation.ubl.xml", "text/xml", "Offer data")

    @Test
    fun `a referenced attachment is loaded by key, verified and embedded as associated data`() {
        whenever(vera.validate(any())).thenReturn(veraResult(KositOutcome.VALID))
        whenever(store.get("quotes/q/quotation.ubl.xml")).thenReturn(xml)

        pipeline().export("j", QuoteFixtures.payload().copy(attachment = attachmentRef()))

        Loader.loadPDF(stored()).use { doc ->
            val spec = doc.documentCatalog.names.embeddedFiles.names.getValue("quotation.ubl.xml")
            assertEquals("text/xml", spec.embeddedFile.subtype)
            assertEquals("Data", spec.cosObject.getNameAsString("AFRelationship"))
            assertTrue(spec.embeddedFile.toByteArray().contentEquals(xml))
        }
    }

    @Test
    fun `an attachment whose bytes do not match the checksum aborts before anything is stored`() {
        whenever(store.get("quotes/q/quotation.ubl.xml")).thenReturn(xml)

        val failure = assertThrows(IllegalStateException::class.java) {
            pipeline().export("j", QuoteFixtures.payload().copy(attachment = attachmentRef("0".repeat(64))))
        }

        assertTrue(failure.message!!.contains("checksum"), failure.message)
        verify(store, org.mockito.kotlin.never()).put(any(), any(), any())
    }

    @Test
    fun `without an attachment reference the store is only written, never read`() {
        whenever(vera.validate(any())).thenReturn(veraResult(KositOutcome.VALID))

        pipeline().export("j", QuoteFixtures.payload())

        verify(store, org.mockito.kotlin.never()).get(any())
    }

    private fun twoPagePdf(): ByteArray = PDDocument().use { doc ->
        doc.addPage(PDPage())
        doc.addPage(PDPage())
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    @Test
    fun `customer files are appended as receipt pages in the offer language before storing`() {
        whenever(vera.validate(any())).thenReturn(veraResult(KositOutcome.VALID))
        whenever(annex.append(any(), any(), any())).thenReturn(twoPagePdf())
        val ref = QuoteDocumentReceiptRef("quotes/q/a.pdf", "ab".repeat(32), "a.pdf", "application/pdf", "Plan")

        pipeline().export("j", QuoteFixtures.payload(language = "en").copy(receipts = listOf(ref)))

        val receipts = argumentCaptor<List<OrderedReceipt>>()
        verify(annex).append(any(), receipts.capture(), eq("en"))
        val receipt = receipts.firstValue.single()
        assertEquals(OrderedReceipt("a.pdf", "quotes/q/a.pdf", "Plan", emptyList(), true, null, "ab".repeat(32)), receipt)
        Loader.loadPDF(stored()).use { assertEquals(2, it.numberOfPages) }
    }

    @Test
    fun `without customer files the annex builder is not called`() {
        whenever(vera.validate(any())).thenReturn(veraResult(KositOutcome.VALID))

        pipeline().export("j", QuoteFixtures.payload())

        verify(annex, org.mockito.kotlin.never()).append(any(), any(), any())
    }

    @Test
    fun `an offer document above the size limit fails with a readable message and is not stored`() {
        val limit = ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE
        val big = ByteArray(limit.bytes.toInt() + 1).also { java.util.Random(7).nextBytes(it) }
        whenever(renderer.render(any())).thenReturn(onePagePdf())
        whenever(store.get("quotes/q/big.bin")).thenReturn(big)
        val ref = QuoteDocumentAttachmentRef("quotes/q/big.bin", sha256Hex(big), "big.bin", "application/octet-stream", "Big")
        val pipeline = QuotePdfPipeline(renderer, store, vera, PdfA3MinimalCheck(), EmbedAttachmentStage(), annex, time)

        val failure = assertThrows(QuoteDocumentTooLargeException::class.java) {
            pipeline.export("j", QuoteFixtures.payload().copy(attachment = ref))
        }

        assertTrue(failure.message!!.startsWith("Offer document is "), failure.message)
        assertTrue(failure.message!!.contains("exceeding the maximum of"), failure.message)
        verify(store, org.mockito.kotlin.never()).put(any(), any(), any())
    }
}
