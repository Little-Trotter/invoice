package de.littletrotter.xpdf.impl

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.receipt.OrderedReceipt
import de.littletrotter.shared.receipt.ReceiptDisplaySize
import de.littletrotter.shared.receipt.ReceiptPageRasterizer
import de.littletrotter.shared.receipt.ReceiptPdfInspector
import de.littletrotter.shared.receipt.ReceiptRaster
import de.littletrotter.shared.receipt.ReceiptRasterKind
import de.littletrotter.shared.receipt.ReceiptUnavailableException
import de.littletrotter.shared.receipt.atLeastReadable
import de.littletrotter.shared.receipt.readReceipt
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.PDFRenderer
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.awt.Color
import java.io.InputStream

/**
 * Appends every receipt assigned to an invoice behind the invoice pages, so the PDF alone shows
 * what the XML attachments carry. Receipt pages are rasterised: a foreign PDF brings its own fonts
 * and colour spaces, which would break the PDF/A-3 conformance of the whole document. The header
 * band stays a real text layer in an embedded font (extractable, searchable) on top of the image.
 *
 * A receipt that cannot be loaded or rendered fails the export; a document that silently lacks an
 * assigned receipt would disagree with its own XML.
 */
@ApplicationScoped
class ReceiptAnnexBuilder @Inject constructor(
    private val documentStore: DocumentStore,
    // `@param:` keeps the config annotation on the constructor parameter only, not on the field.
    @param:ConfigProperty(name = "xpdf.receipt-annex.dpi", defaultValue = "150")
    dpi: Float,
    @param:ConfigProperty(name = "xpdf.receipt-annex.jpeg-quality", defaultValue = "0.75")
    jpegQuality: Float,
    // Silent upper bound for one page bitmap, a safety net should the dpi setting be raised far
    // beyond the A4 target: the resolution is lowered to stay within it, the page is never refused.
    @param:ConfigProperty(name = "xpdf.receipt-annex.max-pixels", defaultValue = "$DEFAULT_MAX_PIXELS")
    maxPixels: Long,
    private val workingSet: PdfBoxWorkingSet,
) {

    constructor(documentStore: DocumentStore) :
        this(documentStore, DEFAULT_DPI, DEFAULT_JPEG_QUALITY, DEFAULT_MAX_PIXELS, PdfBoxWorkingSet())

    // The raster is the same value object upload reduction uses, so a receipt reduced at upload
    // already has the resolution the print renders at and is copied instead of rendered. It is
    // never weaker than the readability floor.
    private val raster = ReceiptRaster(dpi, jpegQuality, maxPixels).atLeastReadable()
    private val inspector = ReceiptPdfInspector(raster)
    private val rasterizer = ReceiptPageRasterizer(raster)

    companion object {
        private const val DEFAULT_DPI = 150f
        private const val DEFAULT_JPEG_QUALITY = 0.75f
        const val DEFAULT_MAX_PIXELS = ReceiptRaster.DEFAULT_MAX_PIXELS
        private const val MARGIN = ReceiptDisplaySize.MARGIN
        private const val HEADER_BAND_HEIGHT = ReceiptDisplaySize.HEADER_BAND_HEIGHT
        private const val TITLE_FONT_SIZE_PT = 11f
        private const val TITLE_OFFSET_PT = 10f
        private const val POSITION_FONT_SIZE_PT = 9f
        private const val POSITION_OFFSET_PT = 24f
        private const val GRAY = 0x7F
        private const val FONT_UNITS_PER_EM = 1000f
        private const val ELLIPSIS = "…"
    }

    /** Which attachment a page belongs to: the receipt, its 1-based [number] and the [total]. */
    private data class Slot(val receipt: OrderedReceipt, val number: Int, val total: Int, val wording: HeaderWording)

    /** The two header band text sets; the customer's document language decides, German is the default. */
    private enum class HeaderWording(val attachment: String, val page: String, val positions: String) {
        GERMAN("Anlage", "Seite", "Pos."),
        ENGLISH("Attachment", "page", "Item"),
        ;

        companion object {
            fun of(language: String) = if (language.equals("en", ignoreCase = true)) ENGLISH else GERMAN
        }
    }

    /** The page of an attachment a header band is drawn on. */
    private data class HeaderInfo(val slot: Slot, val pageNumber: Int, val pageCount: Int)

    /** Where and how one line of header text is drawn. */
    private data class Line(val value: String, val fontSize: Float, val color: Color, val y: Float)

    private data class Canvas(val cs: PDPageContentStream, val font: PDType0Font)

    /** Returns [basePdfBytes] unchanged when [receipts] is empty; [language] picks the header band wording. */
    fun append(basePdfBytes: ByteArray, receipts: List<OrderedReceipt>, language: String = "de"): ByteArray {
        if (receipts.isEmpty()) return basePdfBytes
        return workingSet.load(basePdfBytes).use { output ->
            appendTo(output, receipts, language)
            workingSet.save(output)
        }
    }

    /** Appends the pages of [receipts] to the already opened [output]; the caller saves it. */
    fun appendTo(output: PDDocument, receipts: List<OrderedReceipt>, language: String = "de") {
        if (receipts.isEmpty()) return
        val font = loadFont(output)
        val wording = HeaderWording.of(language)
        receipts.forEachIndexed { index, receipt ->
            appendReceipt(output, font, Slot(receipt, index + 1, receipts.size, wording))
        }
    }

    private fun appendReceipt(output: PDDocument, font: PDType0Font, slot: Slot) {
        val receipt = slot.receipt
        val bytes = documentStore.readReceipt(receipt.fileName, receipt.objectKey)
        receipt.sha256?.let { expected ->
            if (!sha256Hex(bytes).equals(expected, ignoreCase = true)) {
                throw ReceiptUnavailableException(receipt.fileName, detail = "Prüfsumme weicht ab")
            }
        }
        val source = try {
            workingSet.load(bytes)
        } catch (ex: java.io.IOException) {
            throw ReceiptUnavailableException(receipt.fileName, ex, "keine lesbare PDF-Datei")
        }
        source.use { doc ->
            val renderer = PDFRenderer(doc)
            val pageCount = doc.numberOfPages
            val importable = receipt.rasterKind == ReceiptRasterKind.IMAGE_PDF
            // One page at a time: only the current page's bitmap is ever held in memory.
            for (pageIndex in 0 until pageCount) {
                val sourcePage = doc.getPage(pageIndex)
                val display = displaySize(sourcePage, receipt.fileName, pageIndex)
                val pdImage = copiedImage(output, if (importable) inspector.importableImage(sourcePage) else null)
                    ?: renderedImage(output, renderer, PageRef(pageIndex, receipt.fileName, display, sourcePage))
                val (pageWidth, pageHeight) = ReceiptDisplaySize.pageSize(display.landscape)
                val size = PDRectangle(pageWidth, pageHeight)
                val page = PDPage(size)
                output.addPage(page)
                PDPageContentStream(output, page).use { cs ->
                    drawImage(cs, pdImage, display, size)
                    drawHeader(cs, font, size, HeaderInfo(slot, pageIndex + 1, pageCount))
                }
            }
        }
    }

    /** A page of the receipt: its place in the file, the receipt it belongs to (for error messages) and how it is shown. */
    private data class PageRef(val index: Int, val fileName: String, val display: ReceiptDisplaySize, val source: PDPage)

    /** The page's JPEG taken over unchanged — no rendering, no re-encoding. */
    private fun copiedImage(output: PDDocument, image: PDImageXObject?): PDImageXObject? {
        if (image == null) return null
        val jpeg = image.stream.createInputStream(listOf(COSName.DCT_DECODE.name)).use { it.readAllBytes() }
        return JPEGFactory.createFromByteArray(output, jpeg)
    }

    private fun renderedImage(output: PDDocument, renderer: PDFRenderer, page: PageRef): PDImageXObject {
        val image = try {
            rasterizer.render(renderer, page.index, page.display, page.source)
        } catch (ex: java.io.IOException) {
            throw ReceiptUnavailableException(page.fileName, ex, "Seite ${page.index + 1} nicht darstellbar")
        }
        return rasterizer.encode(output, image)
    }

    /** Visible page size with /Rotate applied, as the renderer shows it. */
    private fun displaySize(sourcePage: PDPage, fileName: String, pageIndex: Int): ReceiptDisplaySize {
        val box = sourcePage.cropBox
        return try {
            ReceiptDisplaySize.ofRotated(box.width, box.height, sourcePage.rotation)
        } catch (ex: IllegalArgumentException) {
            throw ReceiptUnavailableException(fileName, ex, "Seite ${pageIndex + 1} hat keine gültige Größe")
        }
    }

    private fun drawHeader(cs: PDPageContentStream, font: PDType0Font, size: PDRectangle, info: HeaderInfo) {
        val canvas = Canvas(cs, font)
        val receipt = info.slot.receipt
        val maxWidth = size.width - 2 * MARGIN
        val wording = info.slot.wording
        val pageSuffix = if (info.pageCount > 1) " (${wording.page} ${info.pageNumber}/${info.pageCount})" else ""
        val title = "${wording.attachment} ${info.slot.number}/${info.slot.total} – ${receipt.fileName}$pageSuffix"
        draw(canvas, Line(fit(font, title, TITLE_FONT_SIZE_PT, maxWidth), TITLE_FONT_SIZE_PT, Color.BLACK, size.height - MARGIN - TITLE_OFFSET_PT))
        if (receipt.positions.isNotEmpty()) {
            val positions = fit(font, "${wording.positions} ${receipt.positions.joinToString(", ")}", POSITION_FONT_SIZE_PT, maxWidth)
            draw(canvas, Line(positions, POSITION_FONT_SIZE_PT, Color(GRAY, GRAY, GRAY), size.height - MARGIN - POSITION_OFFSET_PT))
        }
    }

    private fun draw(canvas: Canvas, line: Line) {
        val cs = canvas.cs
        cs.setNonStrokingColor(line.color)
        cs.beginText()
        cs.setFont(canvas.font, line.fontSize)
        cs.newLineAtOffset(MARGIN, line.y)
        cs.showText(line.value)
        cs.endText()
    }

    /** Replaces glyphs the font lacks and shortens the line to [maxWidth], so no file name can
     *  make the export fail on drawing. */
    private fun fit(font: PDType0Font, raw: String, fontSize: Float, maxWidth: Float): String {
        val printable = raw.codePoints().toArray().joinToString("") { cp ->
            val s = String(Character.toChars(cp))
            if (encodable(font, s)) s else "?"
        }
        fun width(s: String) = font.getStringWidth(s) / FONT_UNITS_PER_EM * fontSize
        if (width(printable) <= maxWidth) return printable
        var cut = printable
        while (cut.isNotEmpty() && width(cut + ELLIPSIS) > maxWidth) cut = cut.dropLast(1)
        return cut + ELLIPSIS
    }

    private fun encodable(font: PDType0Font, s: String): Boolean =
        try {
            font.encode(s)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun drawImage(cs: PDPageContentStream, image: PDImageXObject, display: ReceiptDisplaySize, size: PDRectangle) {
        val areaWidth = size.width - 2 * MARGIN
        val top = size.height - MARGIN - HEADER_BAND_HEIGHT
        cs.drawImage(image, MARGIN + (areaWidth - display.width) / 2f, top - display.height, display.width, display.height)
    }

    /** `internal` with an injectable lookup so a test can exercise the missing-resource branch. */
    internal fun loadFont(
        document: PDDocument,
        resourceLookup: (String) -> InputStream? = { path -> javaClass.getResourceAsStream(path) },
    ): PDType0Font {
        val stream = resourceLookup("/fonts/LiberationSans-Regular.ttf")
            ?: error("Font resource /fonts/LiberationSans-Regular.ttf not found")
        return stream.use { PDType0Font.load(document, it) }
    }
}
