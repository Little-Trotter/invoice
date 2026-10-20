package de.littletrotter.xpdf.impl

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.documentjob.QuoteDocumentAttachmentRef
import de.littletrotter.shared.documentjob.QuoteDocumentMark
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.xpdf.testsupport.TypstTestResource
import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

/**
 * An offer PDF with a referenced data file, through the real engines: Typst renders, the stage
 * chain loads the file from the store by key, verifies its checksum and embeds it, and veraPDF
 * judges the stored bytes. The PDF stays PDF/A-3b valid, the embedded bytes equal the input, and
 * the document claims no hybrid-invoice or order standard. Synthetic data only.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuoteXmlEmbeddingIT {

    @Container
    private val typst = TypstTestResource()

    @Container
    private val veraPdf = VeraPdfTestResource()

    private val creation: Instant = Instant.ofEpochSecond(1_790_000_000L)
    private val store = MapStore()
    private val xml = (
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<Quotation xmlns=\"urn:example:synthetic\"><ID>AN-2026-00007</ID><Note>Größe & Maß</Note></Quotation>"
        ).toByteArray(Charsets.UTF_8)

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

    @ParameterizedTest(name = "{0} copy with XML attachment is PDF/A-3b valid and carries the exact bytes")
    @EnumSource(QuoteDocumentMark::class)
    fun `the attachment keeps the PDF valid and its bytes equal the input`(mark: QuoteDocumentMark) {
        val key = "quotes/8f6c0f2e-0000-4000-8000-000000000001/quotation.ubl.xml"
        store.objects[key] = xml
        val ref = QuoteDocumentAttachmentRef(key, sha256Hex(xml), "quotation.ubl.xml", "text/xml", "Machine-readable offer (UBL 2.1 Quotation)")

        val result = pipeline().export("job-xml", QuoteFixtures.payload(mark).copy(attachment = ref))

        val report = checkNotNull(KositReportPayload.decode(result.validationReportJson))
        assertEquals(KositOutcome.VALID.name, report.status, report.details.joinToString { it.message })
        val pdf = store.objects.getValue(result.objectKey)
        Loader.loadPDF(pdf).use { doc ->
            val spec = doc.documentCatalog.names.embeddedFiles.names.getValue("quotation.ubl.xml")
            assertEquals("text/xml", spec.embeddedFile.subtype)
            assertEquals("Data", spec.cosObject.getNameAsString("AFRelationship"))
            assertTrue(spec.embeddedFile.toByteArray().contentEquals(xml), "embedded bytes differ from the input")
            val xmp = String(doc.documentCatalog.metadata.toByteArray(), Charsets.UTF_8)
            assertTrue(xmp.contains("UBL 2.1 Quotation"), "dc:description missing in the XMP packet")
            assertFalse(xmp.contains("factur-x", ignoreCase = true) || xmp.contains("order-x", ignoreCase = true), xmp)
        }
    }

    private class MapStore : DocumentStore {
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
