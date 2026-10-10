package de.littletrotter.xpdf.impl

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.ReceiptDto
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.xpdf.b2bInvoiceDto
import de.littletrotter.xpdf.testsupport.FixtureBaseRenderer
import de.littletrotter.xpdf.testsupport.OutputIntentTestKeys
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.io.ByteArrayOutputStream

/**
 * IR-1's PDF-side half. `KositValidationIT`'s own "the 19-receipt budget…" test proves the XML
 * side (19 deduplicated BG-24 entries, still KoSIT-VALID); this class proves what the identical
 * 19-receipts-at-768-KiB budget does to the exported PDF **container**, through the real Mustang
 * route ([PdfExportServiceImpl.performExport]): the final container stays inside
 * [ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE], and — since a beleg is carried exactly once, as
 * Base64 inside the embedded XML (see [BasePdfStage]'s own class doc) — the PDF itself still
 * carries exactly one embedded file, never one per beleg.
 *
 * No Testcontainers: like `KositValidationIT`, this stays entirely in-JVM —
 * [FixtureBaseRenderer] stands in for the base-PDF layout engine (this class's subject is the
 * export pipeline around that step, not the layout engine itself — see that fixture's own KDoc),
 * and KoSIT validation is skipped (`skipValidation = true`; already proven on the identical XML
 * shape by `KositValidationIT`'s own test, which this class does not repeat).
 */
class ReceiptContainerBudgetIT {

    private class SeededDocumentStore(private val seeded: Map<String, ByteArray>) : DocumentStore {
        val written = linkedMapOf<String, ByteArray>()
        val contentTypes = linkedMapOf<String, String>()
        override fun put(objectKey: String, data: ByteArray, contentType: String) {
            written[objectKey] = data
            contentTypes[objectKey] = contentType
        }
        override fun get(objectKey: String): ByteArray = written[objectKey] ?: seeded[objectKey] ?: error("not found: $objectKey")
        override fun exists(objectKey: String): Boolean = written.containsKey(objectKey) || seeded.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) { written.remove(objectKey) }
    }

    /** A synthetic, valid single-page PDF padded (via an unreferenced custom stream — harmless,
     *  ignored by parsers) to exactly [targetBytes] — the 768 KiB shape the belege budget actually
     *  looks like on disk, not an arbitrary byte array. [marker] only keeps the 19 fixtures from
     *  being byte-identical. Same technique as `KositValidationIT.paddedReceiptPdf` — kept local
     *  since each *IT in this module is a self-contained, single-file scenario. */
    private fun paddedReceiptPdf(targetBytes: Long, marker: Int): ByteArray {
        fun build(padBytes: Int): ByteArray = PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            val padStream = doc.document.createCOSStream()
            padStream.createOutputStream().use { it.write(ByteArray(padBytes) { marker.toByte() }) }
            page.cosObject.setItem(COSName.getPDFName("LTPad"), padStream)
            val os = ByteArrayOutputStream()
            doc.save(os)
            os.toByteArray()
        }

        var pad = 0
        var bytes = build(pad)
        repeat(5) {
            val delta = targetBytes - bytes.size
            if (delta == 0L) return bytes
            pad = (pad + delta).toInt().coerceAtLeast(0)
            bytes = build(pad)
        }
        error("test fixture did not converge to the exact target size, got ${bytes.size}, wanted $targetBytes")
    }

    @Test
    fun `the 19-receipt, 768 KiB budget stays inside the 20 MiB container and carries exactly one embedded file`() {
        val receiptCount = ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE
        val receiptSizeBytes = 768L * 1024
        val seeded = (1..receiptCount).associate { i ->
            "receipt-pool/beleg-$i.pdf" to paddedReceiptPdf(receiptSizeBytes, marker = i)
        }
        val store = SeededDocumentStore(seeded)
        val receipts = (1..receiptCount).map { i -> ReceiptDto(fileName = "beleg-$i.pdf", objectKey = "receipt-pool/beleg-$i.pdf") }
        val invoice = b2bInvoiceDto().copy(receipts = receipts)
        val grant = OutputIntentTestKeys.grantFor(invoice)

        val renderer = FixtureBaseRenderer()
        val kositValidator: KositValidatorPort = mock()
        val service = PdfExportServiceImplTestFactory.create(
            XmlExportServiceImpl(store), renderer, store, kositValidator, outputIntentVerifier = OutputIntentTestKeys.verifier,
        )

        val result = service.performExport(
            invoice, "job-receipt-container-budget", skipValidation = true, outputIntent = grant,
        )

        assertEquals(64, result.sha256.length, "the export must complete and store a document")
        val pdfKey = store.contentTypes.entries.first { it.value == "application/pdf" }.key
        val pdfBytes = store.written.getValue(pdfKey)

        assertTrue(
            pdfBytes.size.toLong() <= ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE_BYTES,
            "container is ${pdfBytes.size} bytes, exceeding the ${ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE_BYTES}-byte budget",
        )

        Loader.loadPDF(pdfBytes).use { document ->
            val embeddedFiles = document.documentCatalog.names?.embeddedFiles?.names
            assertNotNull(embeddedFiles, "the ZUGFeRD XML must be embedded")
            assertEquals(
                1, embeddedFiles!!.size,
                "exactly one embedded file — none of the 19 belege may appear a second time (vorhanden: ${embeddedFiles.keys})",
            )
        }
    }
}
