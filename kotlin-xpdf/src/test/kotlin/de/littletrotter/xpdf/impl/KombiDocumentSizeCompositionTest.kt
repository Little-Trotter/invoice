package de.littletrotter.xpdf.impl

import de.littletrotter.shared.receipt.orderedDistinctReceipts
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.ReceiptDto
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.shared.receipt.ReceiptPdfInspector
import de.littletrotter.shared.receipt.ReceiptSize
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.xpdf.b2bInvoiceDto
import de.littletrotter.xpdf.testsupport.FixtureBaseRenderer
import de.littletrotter.xpdf.testsupport.OutputIntentTestKeys
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * Size composition of a combined document with the maximum receipt count, for several receipt
 * shapes: the synthetic stress shape (uncompressed grey raster), a scanned single-page JPEG PDF,
 * a vector-text PDF with a full embedded font, a multi-page text document and a multi-page scan.
 *
 * Every shape is sized at the per-receipt target. For each one the test prints the components of
 * the exported PDF (invoice base, receipt pages, embedded XML with the Base64 receipts, rest) and
 * asserts that the budget estimate, fed with the profile the upload would record, covers the real
 * size of the exported document and exceeds it by no more than the fixed reserve.
 */
class KombiDocumentSizeCompositionTest {

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

    /** What the export measured: the exported PDF split into the parts that make it up. */
    private data class Composition(
        val shape: String,
        val receiptBytes: Long,
        val baseBytes: Long,
        val annexBytes: Long,
        val embeddedXmlStoredBytes: Long,
        val embeddedXmlDecodedBytes: Long,
        val finalBytes: Long,
        val estimateBytes: Long,
        val profile: String,
        val measuredAnnexBytes: Long,
    ) {
        val restBytes get() = finalBytes - baseBytes - annexBytes - embeddedXmlStoredBytes

        fun line(): String =
            "KOMBI-SIZE shape=$shape receipts=$receiptBytes base=$baseBytes annexPages=$annexBytes " +
                "profile=$profile measuredAnnex=$measuredAnnexBytes xmlStored=$embeddedXmlStoredBytes xmlDecoded=$embeddedXmlDecodedBytes rest=$restBytes " +
                "final=$finalBytes estimate=$estimateBytes limit=${ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE_BYTES}"
    }

    private fun save(doc: PDDocument): ByteArray = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()

    /** The stress generator's receipt: one A4 page showing a 640 x 704 grey raster stored raw. */
    private fun rawGreyReceipt(seed: Int): ByteArray {
        val width = 640
        val height = 704
        val pixels = ByteArray(width * height).also { Random(seed.toLong()).nextBytes(it) }
        return PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val image = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, greyImage(width, height, pixels))
            PDPageContentStream(doc, page).use { it.drawImage(image, 0f, 0f, page.mediaBox.width, page.mediaBox.height) }
            save(doc)
        }
    }

    private fun greyImage(width: Int, height: Int, pixels: ByteArray): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        img.raster.setDataElements(0, 0, width, height, pixels)
        return img
    }

    /** [pages] scan pages at 150 dpi A4, each a JPEG, total close to [targetBytes]. */
    private fun scannedReceipt(targetBytes: Long, pages: Int, seed: Int, wide: Boolean = false): ByteArray {
        val random = Random(seed.toLong())
        // Reduced to the print raster of the displayed page (1061 x 1500, imported unchanged), or a
        // full-bleed A4 scan at 150 dpi (1240 x 1754), which is above the displayed resolution.
        val width = if (wide) 1240 else 1061
        val height = if (wide) 1754 else 1500
        fun image(amplitude: Int): BufferedImage {
            val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val v = (200 + x * 40 / width + random.nextInt(amplitude * 2 + 1) - amplitude).coerceIn(0, 255)
                    img.setRGB(x, y, (v shl 16) or (v shl 8) or v)
                }
            }
            return img
        }
        fun build(amplitude: Int): ByteArray = PDDocument().use { doc ->
            repeat(pages) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                val xobject = JPEGFactory.createFromImage(doc, image(amplitude), 0.75f)
                PDPageContentStream(doc, page).use { it.drawImage(xobject, 0f, 0f, page.mediaBox.width, page.mediaBox.height) }
            }
            save(doc)
        }
        var low = 0
        var high = 120
        var best = build(0)
        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = build(mid)
            if (candidate.size <= targetBytes) { best = candidate; low = mid + 1 } else high = mid - 1
        }
        return best
    }

    /** [pages] text pages in a fully embedded (not subset) font, like a PDF printed from an office suite. */
    private fun textReceipt(pages: Int, seed: Int): ByteArray = PDDocument().use { doc ->
        val font = javaClass.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")!!.use { PDType0Font.load(doc, it, false) }
        repeat(pages) { pageIndex ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(font, 9f)
                cs.setLeading(11f)
                cs.newLineAtOffset(40f, 800f)
                repeat(70) { line -> cs.showText("Beleg $seed Seite ${pageIndex + 1} Zeile $line  Position 12,50 EUR  MwSt 19 %  Summe ${line * 7},99"); cs.newLine() }
                cs.endText()
            }
        }
        save(doc)
    }

    private fun measure(shape: String, receiptPdfs: List<ByteArray>): Composition {
        val keys = receiptPdfs.indices.map { "receipt-pool/$shape-${it + 1}.pdf" }
        val store = SeededDocumentStore(keys.zip(receiptPdfs).toMap())
        val inspector = ReceiptPdfInspector()
        val profiles = receiptPdfs.map { inspector.inspect(it) }
        val receipts = keys.indices.map { i ->
            ReceiptDto(
                fileName = "$shape-${i + 1}.pdf",
                objectKey = keys[i],
                rasterKind = profiles[i].rasterKind.name,
                pageCount = profiles[i].pageCount,
                a4Factor = profiles[i].a4Factor,
                annexBytes = profiles[i].annexBytes,
            )
        }
        val invoice = b2bInvoiceDto().copy(receipts = receipts)
        val renderer = FixtureBaseRenderer()
        val base = renderer.render(invoice)
        val withAnnex = ReceiptAnnexBuilder(store).append(base, invoice.orderedDistinctReceipts())

        val service = PdfExportServiceImplTestFactory.create(
            XmlExportServiceImpl(store), renderer, store, mock<KositValidatorPort>(), outputIntentVerifier = OutputIntentTestKeys.verifier,
        )
        var storedXml = 0L
        var decodedXml = 0L
        // The backstop throws before the PDF is stored; its exception still carries the real size,
        // and the embedded XML is stored uncompressed, so its length is the XML object's length.
        val finalSize = try {
            service.performExport(invoice, "job-size-$shape", skipValidation = true, outputIntent = OutputIntentTestKeys.grantFor(invoice))
            val pdfKey = store.contentTypes.entries.first { it.value == "application/pdf" }.key
            val finalPdf = store.written.getValue(pdfKey)
            Loader.loadPDF(finalPdf).use { document ->
                document.documentCatalog.names.embeddedFiles.names.values.forEach { spec ->
                    val file = spec.embeddedFile
                    storedXml += file.cosObject.getLong("Length")
                    decodedXml += file.toByteArray().size
                }
            }
            finalPdf.size.toLong()
        } catch (tooLarge: InvoiceDocumentTooLargeException) {
            storedXml = store.contentTypes.entries.filter { it.value.contains("xml") }.sumOf { store.written.getValue(it.key).size.toLong() }
            decodedXml = storedXml
            tooLarge.actual.bytes
        }
        val estimate = InvoiceDocumentBudget.calculate(
            keys.indices.map { ReceiptSize(keys[it], DocumentSize.ofBytes(receiptPdfs[it].size.toLong()), profiles[it]) },
        ).estimatedDocumentSize.bytes
        return Composition(
            shape = shape,
            receiptBytes = receiptPdfs.sumOf { it.size.toLong() },
            baseBytes = base.size.toLong(),
            annexBytes = withAnnex.size.toLong() - base.size,
            embeddedXmlStoredBytes = storedXml,
            embeddedXmlDecodedBytes = decodedXml,
            finalBytes = finalSize,
            estimateBytes = estimate,
            measuredAnnexBytes = profiles.sumOf { it.annexBytes },
            profile = profiles.first().let { "${it.rasterKind}/${it.pageCount}p/a4=${"%.2f".format(java.util.Locale.ROOT, it.a4Factor)}" },
        ).also { println(it.line()) }
    }

    private fun assertEstimateCovers(c: Composition) {
        val reserve = ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE_BYTES
        assertTrue(
            kotlin.math.abs(c.annexBytes - c.measuredAnnexBytes) <= annexHeaderToleranceBytes,
            "annex pages ${c.annexBytes} B differ from the bytes measured at upload ${c.measuredAnnexBytes} B: ${c.line()}",
        )
        assertTrue(
            c.estimateBytes - c.finalBytes <= reserve,
            "estimate ${c.estimateBytes} B exceeds the exported document by ${c.estimateBytes - c.finalBytes} B, " +
                "more than the reserve of $reserve B: ${c.line()}",
        )
        assertTrue(
            c.finalBytes <= c.estimateBytes,
            "estimate ${c.estimateBytes} B undercuts the exported document by ${c.finalBytes - c.estimateBytes} B " +
                "(reserve ${ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE_BYTES} B, already part of the estimate): ${c.line()}",
        )
    }

    private val annexHeaderToleranceBytes = 128L * 1024

    private val count = ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE
    private val target = ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES

    @Test
    fun `stress shape - raw grey raster pages - estimate covers the exported document`() =
        assertEstimateCovers(measure("rawgrey", (1..count).map { rawGreyReceipt(it) }))

    @Test
    fun `print-raster scanned single-page JPEG receipts - estimate covers the exported document`() =
        assertEstimateCovers(measure("scan1", (1..count).map { scannedReceipt(target, pages = 1, seed = it) }))

    @Test
    fun `full-bleed 150 dpi A4 print-raster scanned single-page JPEG receipts - estimate covers the exported document`() =
        assertEstimateCovers(measure("scan1wide", (1..count).map { scannedReceipt(target, pages = 1, seed = it, wide = true) }))

    @Test
    fun `scanned three-page receipts - estimate covers the exported document`() =
        assertEstimateCovers(measure("scan3", (1..count).map { scannedReceipt(target, pages = 3, seed = it) }))

    @Test
    fun `vector text receipts with a full embedded font - estimate covers the exported document`() =
        assertEstimateCovers(measure("text1", (1..count).map { textReceipt(pages = 1, seed = it) }))

    @Test
    fun `multi-page vector text receipts - estimate covers the exported document`() =
        assertEstimateCovers(measure("text5", (1..count).map { textReceipt(pages = 5, seed = it) }))
}
