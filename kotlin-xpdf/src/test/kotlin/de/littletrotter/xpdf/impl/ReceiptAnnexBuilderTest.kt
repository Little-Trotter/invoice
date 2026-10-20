package de.littletrotter.xpdf.impl

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.ReceiptDto
import de.littletrotter.shared.receipt.OrderedReceipt
import de.littletrotter.shared.receipt.ReceiptUnavailableException
import de.littletrotter.shared.receipt.orderedDistinctReceipts
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.awt.geom.Point2D
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

class ReceiptAnnexBuilderTest {

    private fun pdfBytes(pages: Int = 1, landscape: Boolean = false): ByteArray =
        PDDocument().use { doc ->
            repeat(pages) {
                doc.addPage(if (landscape) PDPage(PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)) else PDPage())
            }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

    private fun item(vararg receipts: ReceiptDto, positionId: String = ""): InvoiceItemDto =
        InvoiceItemDto(
            positionId = positionId,
            description = "Position",
            quantity = BigDecimal.ONE,
            unitPriceNet = Money(BigDecimal("10")),
            taxRatePercent = BigDecimal("19"),
            receipts = receipts.toList(),
        )

    private fun invoice(receipts: List<ReceiptDto> = emptyList(), vararg items: InvoiceItemDto) =
        InvoiceDto(invoiceNumber = InvoiceNumberDto("INV-1"), receipts = receipts, items = items.toList())
            .orderedDistinctReceipts()

    private fun textOf(pdf: ByteArray, page: Int): String =
        Loader.loadPDF(pdf).use { doc ->
            PDFTextStripper().apply { startPage = page; endPage = page }.getText(doc)
        }

    private fun storeOf(vararg entries: Pair<String, ByteArray>): DocumentStore {
        val store: DocumentStore = mock()
        entries.forEach { (key, bytes) -> whenever(store.get(key)).thenReturn(bytes) }
        return store
    }

    @Test
    fun `an invoice without receipts is returned unchanged`() {
        val base = pdfBytes(2)
        assertArrayEquals(base, ReceiptAnnexBuilder(mock<DocumentStore>()).append(base, invoice()))
    }

    @Test
    fun `receipt pages follow the invoice pages and carry an extractable header band`() {
        val store = storeOf("r/a.pdf" to pdfBytes(1), "r/b.pdf" to pdfBytes(2, landscape = true))
        val inv = invoice(
            listOf(ReceiptDto("a.pdf", "r/a.pdf")),
            item(ReceiptDto("b.pdf", "r/b.pdf"), ReceiptDto("a.pdf", "r/a.pdf"), positionId = "3"),
        )

        val out = ReceiptAnnexBuilder(store).append(pdfBytes(2), inv)

        Loader.loadPDF(out).use { doc ->
            assertEquals(2 + 1 + 2, doc.numberOfPages)
            assertTrue(doc.getPage(4).mediaBox.width > doc.getPage(4).mediaBox.height, "landscape receipt page stays landscape")
        }
        assertEquals("", textOf(out, 1).trim())
        val first = textOf(out, 3)
        assertTrue(first.contains("Anlage 1/2 – a.pdf"), first)
        assertTrue(first.contains("Pos. 3"), "a receipt on both levels names its position: $first")
        val second = textOf(out, 4)
        assertTrue(second.contains("Anlage 2/2 – b.pdf (Seite 1/2)"), second)
        assertTrue(textOf(out, 5).contains("(Seite 2/2)"))
    }

    @Test
    fun `an invoice level receipt has no position line and a description does not change the band`() {
        val store = storeOf("r/a.pdf" to pdfBytes())
        val out = ReceiptAnnexBuilder(store).append(
            pdfBytes(), invoice(listOf(ReceiptDto("a.pdf", "r/a.pdf", description = "Taxi"))),
        )
        val text = textOf(out, 2)
        assertTrue(text.contains("Anlage 1/1 – a.pdf"), text)
        assertTrue(!text.contains("Pos."), text)
    }

    @Test
    fun `unencodable characters and overlong names cannot break the header`() {
        val name = "中" + "x".repeat(400) + ".pdf"
        val store = storeOf("r/a.pdf" to pdfBytes())
        val out = ReceiptAnnexBuilder(store).append(pdfBytes(), invoice(listOf(ReceiptDto(name, "r/a.pdf"))))
        val text = textOf(out, 2)
        assertTrue(text.contains("Anlage 1/1 – ?xxx"), text)
        assertTrue(text.contains("…"), text)
    }

    @Test
    fun `a receipt without a storage key fails the export`() {
        val ex = assertThrows(ReceiptUnavailableException::class.java) {
            ReceiptAnnexBuilder(mock<DocumentStore>()).append(pdfBytes(), invoice(listOf(ReceiptDto("a.pdf", null))))
        }
        assertTrue(ex.userMessage.contains("a.pdf"), ex.userMessage)
    }

    @Test
    fun `a receipt the store cannot deliver fails the export`() {
        val store: DocumentStore = mock()
        whenever(store.get("r/a.pdf")).thenThrow(IllegalStateException("gone"))
        val ex = assertThrows(ReceiptUnavailableException::class.java) {
            ReceiptAnnexBuilder(store).append(pdfBytes(), invoice(listOf(ReceiptDto("a.pdf", "r/a.pdf"))))
        }
        assertTrue(ex.userMessage.contains("Dokumentenspeicher"), ex.userMessage)
    }

    @Test
    fun `a receipt that is not a PDF fails the export`() {
        val store = storeOf("r/a.pdf" to "not a pdf".toByteArray())
        val ex = assertThrows(ReceiptUnavailableException::class.java) {
            ReceiptAnnexBuilder(store).append(pdfBytes(), invoice(listOf(ReceiptDto("a.pdf", "r/a.pdf"))))
        }
        assertTrue(ex.userMessage.contains("PDF"), ex.userMessage)
    }

    private fun pdfWithPageSize(width: Float, height: Float): ByteArray =
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle(width, height)))
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

    @Test
    fun `a receipt page of zero size fails the export`() {
        val store = storeOf("r/a.pdf" to pdfWithPageSize(0f, 0f))
        val ex = assertThrows(ReceiptUnavailableException::class.java) {
            ReceiptAnnexBuilder(store).append(pdfBytes(), invoice(listOf(ReceiptDto("a.pdf", "r/a.pdf"))))
        }
        assertTrue(ex.userMessage.contains("a.pdf"), ex.userMessage)
    }

    @Test
    fun `the draft mark stamped afterwards also covers the receipt pages`() {
        val store = storeOf("r/a.pdf" to pdfBytes(2))
        val combined = ReceiptAnnexBuilder(store).append(pdfBytes(), invoice(listOf(ReceiptDto("a.pdf", "r/a.pdf"))))

        val stamped = PdfWatermarkStamper.stampMark(combined, PdfWatermarkStamper.DRAFT_MARK, "en")

        for (page in 1..3) {
            assertTrue(textOf(stamped, page).replace(Regex("\\s+"), "").contains("DRAFT"), "page $page must carry the mark")
        }
    }

    @Test
    fun `a missing font resource is reported`() {
        PDDocument().use { doc ->
            assertThrows(IllegalStateException::class.java) {
                ReceiptAnnexBuilder(mock<DocumentStore>()).loadFont(doc) { null }
            }
        }
    }

    /** A drawn image: its pixel size and its placement on the page in points. */
    private data class Drawn(val pixelWidth: Int, val pixelHeight: Int, val x: Float, val y: Float, val width: Float, val height: Float)

    private fun drawnImage(pdf: ByteArray, pageIndex: Int): Drawn {
        Loader.loadPDF(pdf).use { doc ->
            val page = doc.getPage(pageIndex)
            var drawn: Drawn? = null
            val engine = object : PDFGraphicsStreamEngine(page) {
                override fun drawImage(pdImage: PDImage) {
                    val m = graphicsState.currentTransformationMatrix
                    drawn = Drawn(pdImage.width, pdImage.height, m.translateX, m.translateY, m.scalingFactorX, m.scalingFactorY)
                }
                override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) = Unit
                override fun clip(windingRule: Int) = Unit
                override fun moveTo(x: Float, y: Float) = Unit
                override fun lineTo(x: Float, y: Float) = Unit
                override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Unit
                override fun getCurrentPoint(): Point2D = Point2D.Float(0f, 0f)
                override fun closePath() = Unit
                override fun endPath() = Unit
                override fun strokePath() = Unit
                override fun fillPath(windingRule: Int) = Unit
                override fun fillAndStrokePath(windingRule: Int) = Unit
                override fun shadingFill(shadingName: COSName) = Unit
            }
            engine.processPage(page)
            return requireNotNull(drawn) { "no image on page" }
        }
    }

    private fun sizedPdf(width: Float, height: Float): ByteArray =
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle(width, height)))
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

    @Test
    fun `an 80 mm receipt is drawn at 80 mm directly under the header band`() {
        val mm80 = 80f / 25.4f * 72f
        val store = storeOf("r/a.pdf" to sizedPdf(mm80, 500f))

        val out = ReceiptAnnexBuilder(store).append(pdfBytes(1), invoice(listOf(ReceiptDto("a.pdf", "r/a.pdf"))))

        val drawn = drawnImage(out, 1)
        val area = PDRectangle.A4
        assertEquals(mm80, drawn.width, 0.5f)
        assertEquals(500f, drawn.height, 0.5f)
        assertEquals(area.height - 36f - 50f, drawn.y + drawn.height, 0.5f)
        assertEquals((area.width - drawn.width) / 2f, drawn.x, 0.5f)
    }

    @Test
    fun `a huge photo page is fitted into the print area with a bounded bitmap`() {
        val store = storeOf("r/a.pdf" to sizedPdf(4000f, 3000f))

        val out = ReceiptAnnexBuilder(store).append(pdfBytes(1), invoice(listOf(ReceiptDto("a.pdf", "r/a.pdf"))))

        Loader.loadPDF(out).use { doc ->
            assertEquals(2, doc.numberOfPages)
            assertTrue(doc.getPage(1).mediaBox.width > doc.getPage(1).mediaBox.height)
        }
        val drawn = drawnImage(out, 1)
        assertTrue(drawn.pixelWidth <= 1754 && drawn.pixelHeight <= 1240, "${drawn.pixelWidth}x${drawn.pixelHeight}")
        assertTrue(drawn.width <= 841.89f - 72f + 0.5f && drawn.height <= 595.28f - 72f - 50f + 0.5f)
    }

    @Test
    fun `a rotated source page is shown in its visible orientation`() {
        val rotated = PDDocument().use { doc ->
            val page = PDPage(PDRectangle(300f, 500f))
            page.rotation = 90
            doc.addPage(page)
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        val store = storeOf("r/a.pdf" to rotated)

        val out = ReceiptAnnexBuilder(store).append(pdfBytes(1), invoice(listOf(ReceiptDto("a.pdf", "r/a.pdf"))))

        val drawn = drawnImage(out, 1)
        assertEquals(500f, drawn.width, 0.5f)
        assertEquals(300f, drawn.height, 0.5f)
    }

    private fun gradientImagePdf(pixelsWide: Int): Pair<ByteArray, ByteArray> {
        val pixelsHigh = pixelsWide * 1414 / 1000
        val bitmap = java.awt.image.BufferedImage(pixelsWide, pixelsHigh, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until pixelsHigh) for (x in 0 until pixelsWide) bitmap.setRGB(x, y, ((x * 7) % 256) shl 16 or (((y * 3) % 256) shl 8) or ((x + y) % 256))
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val image = org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(doc, bitmap, 0.75f)
            org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page).use { it.drawImage(image, 0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height) }
            val jpeg = image.stream.createInputStream(listOf(COSName.DCT_DECODE.name)).use { it.readAllBytes() }
            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray() to jpeg
        }
    }

    private fun embeddedJpegs(pdf: ByteArray, pageIndex: Int): List<ByteArray> =
        Loader.loadPDF(pdf).use { doc ->
            val resources = doc.getPage(pageIndex).resources
            resources.xObjectNames.map { name ->
                val image = resources.getXObject(name) as org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
                image.stream.createInputStream(listOf(COSName.DCT_DECODE.name)).use { it.readAllBytes() }
            }
        }

    @Test
    fun `an image receipt is copied as its own JPEG without being rendered again`() {
        val (receiptPdf, sourceJpeg) = gradientImagePdf(1060)
        val store = storeOf("r/img.pdf" to receiptPdf)
        val receipt = ReceiptDto("img.pdf", "r/img.pdf", rasterKind = "IMAGE_PDF")

        val out = ReceiptAnnexBuilder(store).append(pdfBytes(), invoice(listOf(receipt)))

        val copied = embeddedJpegs(out, 1)
        assertEquals(1, copied.size)
        assertArrayEquals(sourceJpeg, copied.single(), "the JPEG stream is taken over byte for byte")
        assertTrue(textOf(out, 2).contains("Anlage 1/1 – img.pdf"))
    }

    @Test
    fun `the same file without the image receipt marker is rendered and re-encoded`() {
        val (receiptPdf, sourceJpeg) = gradientImagePdf(1060)
        val store = storeOf("r/img.pdf" to receiptPdf)
        val receipt = ReceiptDto("img.pdf", "r/img.pdf")

        val out = ReceiptAnnexBuilder(store).append(pdfBytes(), invoice(listOf(receipt)))

        val rendered = embeddedJpegs(out, 1).single()
        assertTrue(!sourceJpeg.contentEquals(rendered), "without the marker the page goes through rendering")
    }

    @Test
    fun `a receipt marked as image receipt whose structure does not hold is rendered instead`() {
        // 2400 px across A4 is above the print resolution: copying it would put an oversized image into the PDF.
        val (receiptPdf, sourceJpeg) = gradientImagePdf(2400)
        val store = storeOf("r/img.pdf" to receiptPdf)
        val receipt = ReceiptDto("img.pdf", "r/img.pdf", rasterKind = "IMAGE_PDF")

        val out = ReceiptAnnexBuilder(store).append(pdfBytes(), invoice(listOf(receipt)))

        assertTrue(!sourceJpeg.contentEquals(embeddedJpegs(out, 1).single()))
    }

    @Test
    fun `the pixel limit lowers the resolution instead of refusing the page`() {
        val store = storeOf("r/a.pdf" to pdfBytes(1))
        val limit = 100_000L

        val out = ReceiptAnnexBuilder(store, 150f, 0.75f, limit, PdfBoxWorkingSet())
            .append(pdfBytes(1), invoice(listOf(ReceiptDto("a.pdf", "r/a.pdf"))))

        val drawn = drawnImage(out, 1)
        assertTrue(drawn.pixelWidth.toLong() * drawn.pixelHeight <= limit * 21 / 20, "${drawn.pixelWidth}x${drawn.pixelHeight}")
        assertTrue(drawn.pixelWidth > 100, "${drawn.pixelWidth}x${drawn.pixelHeight}")
    }

    @Test
    fun `a page within the pixel limit keeps the target resolution`() {
        val store = storeOf("r/a.pdf" to pdfBytes(1))

        val out = ReceiptAnnexBuilder(store).append(pdfBytes(1), invoice(listOf(ReceiptDto("a.pdf", "r/a.pdf"))))

        val drawn = drawnImage(out, 1)
        assertTrue(drawn.pixelWidth in 1000..1240, "${drawn.pixelWidth}")
    }

    private fun quoteReceipt(sha256: String?) = OrderedReceipt("a.pdf", "r/a.pdf", null, emptyList(), true, null, sha256)

    @Test
    fun `an English offer gets English header wording`() {
        val store = storeOf("r/a.pdf" to pdfBytes(2), "r/b.pdf" to pdfBytes())
        val receipts = listOf(quoteReceipt(null), OrderedReceipt("b.pdf", "r/b.pdf", null, listOf("1", "2"), false))

        val out = ReceiptAnnexBuilder(store).append(pdfBytes(), receipts, "en")

        assertTrue(textOf(out, 2).contains("Attachment 1/2 – a.pdf (page 1/2)"), textOf(out, 2))
        val last = textOf(out, 4)
        assertTrue(last.contains("Item 1, 2"), last)
        assertTrue(!last.contains("Anlage"), last)
    }

    @Test
    fun `a checksum mismatch fails the export before the file is parsed`() {
        val store = storeOf("r/a.pdf" to pdfBytes())

        val failure = assertThrows(ReceiptUnavailableException::class.java) {
            ReceiptAnnexBuilder(store).append(pdfBytes(), listOf(quoteReceipt("0".repeat(64))))
        }

        assertTrue(failure.message!!.contains("Prüfsumme weicht ab"), failure.message)
    }

    @Test
    fun `a matching checksum passes`() {
        val bytes = pdfBytes()
        val store = storeOf("r/a.pdf" to bytes)

        val out = ReceiptAnnexBuilder(store).append(pdfBytes(), listOf(quoteReceipt(sha256Hex(bytes).uppercase())))

        Loader.loadPDF(out).use { assertEquals(2, it.numberOfPages) }
    }
}
