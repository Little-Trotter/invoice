package de.littletrotter.xpdf.impl

import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.awt.geom.Point2D
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Measures a rendered PDF the way a reader sees it: text lines inside one horizontal band of the
 * page (a table column, the totals label column) and the thin horizontal rules (the row separators)
 * spanning exactly that band. All coordinates are top-down points, pages are 1-based.
 */
internal object PdfTableGeometry {

    /** Ascender/descender of the embedded Liberation Sans, as fractions of the font size. */
    private const val CAP_HEIGHT = 0.716f
    private const val DESCENT = 0.212f

    data class TextLine(val page: Int, val baseline: Float, val size: Float, val left: Float, val text: String) {
        val top: Float get() = baseline - CAP_HEIGHT * size
        val bottom: Float get() = baseline + DESCENT * size
    }

    data class Separator(val page: Int, val y: Float)

    /** A filled rectangle or a vertical stroked line (the free-text bar, the footer rule), top-down points. */
    data class Mark(val page: Int, val left: Float, val right: Float, val top: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /** Clearance of one table row: from its last text line down to its own separator and to the next row's first text. */
    data class RowClearance(val page: Int, val lines: Int, val lastLine: String, val toSeparator: Float, val toNextRowText: Float?)

    data class TableMeasurement(val rows: List<RowClearance>, val crossings: List<String>) {
        val wrappedRows: List<RowClearance> get() = rows.filter { it.lines >= 2 }
        val minToSeparator: Float get() = rows.minOf { it.toSeparator }
        val minToNextRowText: Float get() = rows.mapNotNull { it.toNextRowText }.min()

        fun summary(): String =
            "rows=${rows.size} wrapped=${wrappedRows.size} minToSep=${"%.2f".format(minToSeparator)} " +
                "minToNext=${"%.2f".format(minToNextRowText)} wrappedMinToSep=${"%.2f".format(wrappedRows.minOfOrNull { it.toSeparator } ?: Float.NaN)} " +
                "crossings=${crossings.size}"
    }

    private data class Glyph(val page: Int, val baseline: Float, val left: Float, val size: Float, val unicode: String)

    /** The text lines whose glyphs start inside [xMin, xMax), one per page and baseline, glyphs joined left to right. */
    fun textLines(pdf: ByteArray, xMin: Float, xMax: Float): List<TextLine> {
        val glyphs = mutableListOf<Glyph>()
        val stripper = object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) {
                super.processTextPosition(text)
                if (text.xDirAdj >= xMin && text.xDirAdj < xMax) {
                    glyphs += Glyph(currentPageNo, text.yDirAdj, text.xDirAdj, text.fontSizeInPt, text.unicode)
                }
            }
        }
        Loader.loadPDF(pdf).use { stripper.getText(it) }
        return glyphs.groupBy { it.page to (it.baseline * 2).roundToInt() }.values
            .map { group ->
                val ordered = group.sortedBy { it.left }
                TextLine(
                    page = ordered.first().page,
                    baseline = ordered.first().baseline,
                    size = ordered.maxOf { it.size },
                    left = ordered.first().left,
                    text = ordered.joinToString("") { it.unicode }.trim(),
                )
            }
            .filter { it.text.isNotEmpty() }
            .sortedWith(compareBy({ it.page }, { it.baseline }))
    }

    /** Horizontal rules (stroked lines or hairline filled rectangles) that span [xMin, xMax) within 2 pt on both ends. */
    fun separators(pdf: ByteArray, xMin: Float, xMax: Float): List<Separator> {
        val found = mutableListOf<Separator>()
        Loader.loadPDF(pdf).use { doc ->
            for (index in 0 until doc.numberOfPages) {
                val page = doc.getPage(index)
                val pageHeight = page.mediaBox.height
                val engine = RuleEngine(page, pageHeight, xMin, xMax)
                engine.processPage(page)
                found += engine.rules.map { Separator(index + 1, it) }
            }
        }
        return found.sortedWith(compareBy({ it.page }, { it.y }))
    }

    /** Every filled rectangle and every vertical stroked line of the document, in reading order. */
    fun marks(pdf: ByteArray): List<Mark> {
        val found = mutableListOf<Mark>()
        Loader.loadPDF(pdf).use { doc ->
            for (index in 0 until doc.numberOfPages) {
                val page = doc.getPage(index)
                val engine = MarkEngine(page, page.mediaBox.height, index + 1)
                engine.processPage(page)
                found += engine.marks
            }
        }
        return found.sortedWith(compareBy({ it.page }, { it.top }, { it.left }))
    }

    /**
     * Per table row of the column [xMin, xMax): how far the row's last text line stays clear of the row's own separator
     * and of the first text line of the row below. The header row (a line starting with [headerText]) is not a row.
     */
    fun tableClearance(pdf: ByteArray, xMin: Float, xMax: Float, headerText: String): TableMeasurement {
        val lines = textLines(pdf, xMin, xMax)
        val seps = separators(pdf, xMin, xMax)
        val rows = mutableListOf<RowClearance>()
        val crossings = mutableListOf<String>()
        for (page in lines.map { it.page }.distinct()) {
            val header = lines.firstOrNull { it.page == page && it.text.startsWith(headerText) } ?: continue
            val pageSeps = seps.filter { it.page == page && it.y > header.bottom }.map { it.y }.sorted()
            if (pageSeps.isEmpty()) continue
            val body = lines.filter { it.page == page && it.baseline > header.baseline && it.baseline < pageSeps.last() }
            val perRow = pageSeps.mapIndexed { i, sep ->
                val above = if (i == 0) header.baseline else pageSeps[i - 1]
                body.filter { it.baseline > above && it.baseline < sep }
            }
            perRow.forEachIndexed { i, rowLines ->
                if (rowLines.isEmpty()) return@forEachIndexed
                val last = rowLines.maxBy { it.bottom }
                val nextTop = perRow.drop(i + 1).firstOrNull { it.isNotEmpty() }?.minOf { it.top }
                rows += RowClearance(page, rowLines.size, last.text.take(30), pageSeps[i] - last.bottom, nextTop?.let { it - last.bottom })
            }
            for (sep in pageSeps) {
                body.filter { it.top < sep && it.bottom > sep }.forEach {
                    crossings += "p$page '${it.text.take(24)}' [${"%.1f".format(it.top)}..${"%.1f".format(it.bottom)}] crosses rule y=${"%.1f".format(sep)}"
                }
            }
        }
        return TableMeasurement(rows, crossings)
    }

    private class MarkEngine(page: PDPage, private val pageHeight: Float, private val pageNo: Int) :
        PDFGraphicsStreamEngine(page) {

        val marks = mutableListOf<Mark>()
        private var current: Point2D = Point2D.Float()
        private val lines = mutableListOf<Pair<Point2D, Point2D>>()
        private val rects = mutableListOf<Mark>()

        override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) {
            val points = listOf(p0, p1, p2, p3)
            rects += Mark(
                pageNo,
                points.minOf { it.x }.toFloat(), points.maxOf { it.x }.toFloat(),
                pageHeight - points.maxOf { it.y }.toFloat(), pageHeight - points.minOf { it.y }.toFloat(),
            )
        }

        override fun drawImage(pdImage: PDImage) = Unit
        override fun clip(windingRule: Int) = Unit
        override fun moveTo(x: Float, y: Float) {
            current = Point2D.Float(x, y)
        }

        override fun lineTo(x: Float, y: Float) {
            val end = Point2D.Float(x, y)
            lines += current to end
            current = end
        }

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            current = Point2D.Float(x3, y3)
        }

        override fun getCurrentPoint(): Point2D = current
        override fun closePath() = Unit
        override fun endPath() = clear()
        override fun shadingFill(shadingName: COSName) = Unit

        override fun strokePath() {
            lines.filter { (a, b) -> abs(a.x - b.x) < 0.01 }.forEach { (a, b) ->
                marks += Mark(
                    pageNo, a.x.toFloat(), a.x.toFloat(),
                    pageHeight - maxOf(a.y, b.y).toFloat(), pageHeight - minOf(a.y, b.y).toFloat(),
                )
            }
            clear()
        }

        override fun fillPath(windingRule: Int) {
            marks += rects
            clear()
        }

        override fun fillAndStrokePath(windingRule: Int) {
            marks += rects
            strokePath()
        }

        private fun clear() {
            lines.clear()
            rects.clear()
        }
    }

    private class RuleEngine(page: PDPage, private val pageHeight: Float, private val xMin: Float, private val xMax: Float) :
        PDFGraphicsStreamEngine(page) {

        val rules = mutableListOf<Float>()
        private var current: Point2D = Point2D.Float()
        private val lines = mutableListOf<Pair<Point2D, Point2D>>()
        private val rects = mutableListOf<FloatArray>()

        // The engine hands every path point over already multiplied by the current transformation matrix.
        private fun spans(x0: Double, x1: Double): Boolean =
            abs(minOf(x0, x1) - xMin) <= 2.0 && abs(maxOf(x0, x1) - xMax) <= 2.0

        override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) {
            val points = listOf(p0, p1, p2, p3)
            rects += floatArrayOf(
                points.minOf { it.x }.toFloat(), points.minOf { it.y }.toFloat(),
                points.maxOf { it.x }.toFloat(), points.maxOf { it.y }.toFloat(),
            )
        }

        override fun drawImage(pdImage: PDImage) = Unit
        override fun clip(windingRule: Int) = Unit
        override fun moveTo(x: Float, y: Float) {
            current = Point2D.Float(x, y)
        }

        override fun lineTo(x: Float, y: Float) {
            val end = Point2D.Float(x, y)
            lines += current to end
            current = end
        }

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            current = Point2D.Float(x3, y3)
        }

        override fun getCurrentPoint(): Point2D = current
        override fun closePath() = Unit
        override fun endPath() = clear()
        override fun shadingFill(shadingName: COSName) = Unit

        override fun strokePath() {
            lines.filter { (a, b) -> abs(a.y - b.y) < 0.01 && spans(a.x, b.x) }.forEach { (a, _) -> rules += pageHeight - a.y.toFloat() }
            clear()
        }

        override fun fillPath(windingRule: Int) {
            rects.filter { it[3] - it[1] < 1f && spans(it[0].toDouble(), it[2].toDouble()) }
                .forEach { rules += pageHeight - (it[1] + it[3]) / 2 }
            clear()
        }

        override fun fillAndStrokePath(windingRule: Int) {
            rects.filter { it[3] - it[1] < 1f && spans(it[0].toDouble(), it[2].toDouble()) }
                .forEach { rules += pageHeight - (it[1] + it[3]) / 2 }
            strokePath()
        }

        private fun clear() {
            lines.clear()
            rects.clear()
        }
    }
}
