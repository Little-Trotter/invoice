package de.littletrotter.xpdf

import de.littletrotter.xpdf.PdfLayoutInspector.RectF
import de.littletrotter.xpdf.PdfLayoutInspector.Severity
import de.littletrotter.xpdf.PdfLayoutInspector.TextBlock
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.IOException

class PdfLayoutInspectorTest {

    private val mm = 2.83465f

    private fun <T : Any> notNull(value: T?): T = checkNotNull(value) { "expected a value" }

    /** One run of text: left edge and baseline in mm from the top-left page corner. */
    private data class Run(val text: String, val xMm: Float, val yMm: Float, val size: Float = 10f)

    private fun pdf(vararg pages: List<Run>, widthMm: Float = 210f, heightMm: Float = 297f): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for (runs in pages) {
                val page = PDPage(PDRectangle(widthMm * mm, heightMm * mm))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    for (run in runs) {
                        cs.beginText()
                        cs.setFont(font, run.size)
                        cs.newLineAtOffset(run.xMm * mm, (heightMm - run.yMm) * mm)
                        cs.showText(run.text)
                        cs.endText()
                    }
                }
            }
            doc.save(out)
        }
        return out.toByteArray()
    }

    private val address = listOf(Run("Max Mustermann", 20f, 48f), Run("Musterstrasse 1", 20f, 53f))

    @Test
    fun `a page without an address block is reported as missing the DIN window`() {
        val report = PdfLayoutInspector().inspect(pdf(listOf(Run("Hello", 25f, 150f))))

        assertEquals(1, report.pageCount)
        assertNull(report.pages.single().addressBlockRect)
        val violation = report.violations.single()
        assertEquals("ADDRESS_WINDOW_NOT_FOUND", violation.rule)
        assertEquals(Severity.MEDIUM, violation.severity)
        assertEquals(1, violation.page)
        assertTrue(violation.description.contains("DIN-5008"))
    }

    @Test
    fun `an address block at the DIN 5008 position is clean and measured`() {
        val report = PdfLayoutInspector().inspect(pdf(address))

        assertTrue(report.violations.isEmpty(), report.violations.toString())
        val rect = notNull(report.pages.single().addressBlockRect)
        assertEquals(20f * mm, rect.x, 1f)
        assertTrue(rect.height > 0f)
    }

    @Test
    fun `an address block inside the search zone but outside the tolerance is misplaced`() {
        val report = PdfLayoutInspector().inspect(pdf(listOf(Run("Max Mustermann", 90f, 80f))))

        val violation = report.violations.single()
        assertEquals("ADDRESS_WINDOW_MISPLACED", violation.rule)
        assertTrue(violation.description.contains("DIN 5008 Form A"))
    }

    @Test
    fun `only the first page is checked for an address window`() {
        val report = PdfLayoutInspector().inspect(pdf(address, listOf(Run("Seite zwei", 25f, 150f))))

        assertEquals(2, report.pageCount)
        assertTrue(report.violations.isEmpty(), report.violations.toString())
        assertNull(report.pages[1].addressBlockRect)
        assertEquals(1, report.pages[1].pageIndex)
    }

    @Test
    fun `page dimensions of the report follow the requested format`() {
        val report = PdfLayoutInspector().inspect(pdf(address, widthMm = 148f, heightMm = 210f), pageWidthMm = 148f, pageHeightMm = 210f)

        assertEquals(148f, report.pageWidthMm)
        assertEquals(210f, report.pageHeightMm)
        assertEquals(148f * mm, report.pages.single().pageWidthPt, 0.01f)
        assertEquals(210f * mm, report.pages.single().pageHeightPt, 0.01f)
    }

    @Test
    fun `text beyond each margin is reported with its own rule`() {
        val report = PdfLayoutInspector().inspect(
            pdf(
                address + listOf(
                    Run("links", 5f, 120f),
                    Run("rechts rechts rechts rechts rechts rechts", 150f, 130f, 14f),
                    Run("oben", 25f, 10f),
                    Run("unten", 25f, 258f, 60f),
                ),
            ),
        )

        val rules = report.violations.map { it.rule }.toSet()
        assertTrue(
            rules.containsAll(
                setOf("TEXT_OUTSIDE_LEFT_MARGIN", "TEXT_OUTSIDE_RIGHT_MARGIN", "TEXT_OUTSIDE_TOP_MARGIN", "TEXT_OUTSIDE_BOTTOM_MARGIN"),
            ),
            rules.toString(),
        )
        assertTrue(report.violations.filter { it.rule.startsWith("TEXT_OUTSIDE") }.all { it.severity == Severity.MEDIUM })
    }

    @Test
    fun `a long violating text is quoted only up to its preview length`() {
        val long = "x".repeat(60)
        val report = PdfLayoutInspector().inspect(pdf(address + Run(long, 5f, 120f, 6f)))

        val left = report.violations.single { it.rule == "TEXT_OUTSIDE_LEFT_MARGIN" }
        assertTrue(left.description.contains("x".repeat(40)))
        assertTrue(!left.description.contains("x".repeat(41)))
    }

    @Test
    fun `an item table is measured from its header to the last row before the totals`() {
        val report = PdfLayoutInspector().inspect(
            pdf(
                address + listOf(
                    Run("Pos.", 20f, 100f),
                    Run("Bezeichnung", 40f, 100f),
                    Run("1", 20f, 108f),
                    Run("Beratung", 40f, 108f),
                    Run("2", 20f, 116f),
                    Run("Entwicklung", 40f, 116f),
                    Run("Nettobetrag", 20f, 140f),
                    Run("999", 20f, 148f),
                ),
            ),
        )

        val table = notNull(report.pages.single().itemTableRect)
        assertEquals(100f * mm, table.y, 1f)
        assertEquals(16f, report.itemTableHeightMm!!, 3f)
        assertEquals(8f, report.itemTableRowHeightMm!!, 0.2f)
        assertTrue(table.y + table.height < 140f * mm)
    }

    @Test
    fun `a table whose header is only a fuzzy match is still found`() {
        val report = PdfLayoutInspector().inspect(pdf(address + listOf(Run("Pos. Nr", 20f, 100f), Run("1", 20f, 108f))))

        notNull(report.pages.single().itemTableRect)
    }

    @Test
    fun `an English header is recognised and a long header sentence is not`() {
        val english = PdfLayoutInspector().inspect(pdf(address + listOf(Run("Description", 20f, 100f), Run("Work", 20f, 108f))))
        val sentence = PdfLayoutInspector().inspect(
            pdf(address + listOf(Run("Description of all services rendered", 20f, 100f), Run("Work", 20f, 108f))),
        )

        notNull(english.pages.single().itemTableRect)
        assertNull(sentence.pages.single().itemTableRect)
    }

    @Test
    fun `a header without any row below is no table`() {
        val report = PdfLayoutInspector().inspect(pdf(address + Run("Bezeichnung", 20f, 100f)))

        assertNull(report.pages.single().itemTableRect)
        assertNull(report.itemTableHeightMm)
        assertNull(report.itemTableRowHeightMm)
    }

    @Test
    fun `rows deeper than the table depth limit do not extend the table`() {
        val report = PdfLayoutInspector().inspect(
            pdf(address + listOf(Run("Pos.", 20f, 100f), Run("1", 20f, 108f), Run("weit unten", 20f, 220f))),
        )

        val table = notNull(report.pages.single().itemTableRect)
        assertTrue(table.y + table.height < 120f * mm)
    }

    @Test
    fun `a page number of the form n slash m is located`() {
        val report = PdfLayoutInspector().inspect(pdf(address + Run("Seite 1 / 2", 100f, 200f)))

        notNull(report.pages.single().pageNumberRect)
        assertNull(PdfLayoutInspector().inspect(pdf(address)).pages.single().pageNumberRect)
    }

    @Test
    fun `footer columns come from the fullest footer row and are ordered left to right`() {
        val report = PdfLayoutInspector().inspect(
            pdf(
                address + listOf(
                    Run("Sitz", 140f, 275f, 8f),
                    Run("Bank", 20f, 275f, 8f),
                    Run("Kontakt", 80f, 275f, 8f),
                    Run("Zeile zwei", 20f, 280f, 8f),
                ),
            ),
        )

        val columns = report.pages.single().footerColumnRects
        assertTrue(columns.size >= 2, columns.toString())
        assertTrue(columns.all { it.y < 277.5f * mm }, columns.toString())
        assertEquals(columns.sortedBy { it.x }, columns)
        assertTrue(report.violations.isEmpty(), report.violations.toString())
    }

    @Test
    fun `a page without footer text has no footer columns`() {
        assertTrue(PdfLayoutInspector().inspect(pdf(address)).pages.single().footerColumnRects.isEmpty())
    }

    @Test
    fun `blank text is not a block`() {
        val report = PdfLayoutInspector().inspect(pdf(address + Run("   ", 100f, 120f)))

        assertTrue(report.pages.single().textBlocks.none { it.text.isBlank() })
    }

    @Test
    fun `bytes that are no pdf are rejected`() {
        assertThrows<IOException> { PdfLayoutInspector().inspect(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun `overlapping footer columns are reported with both column numbers`() {
        val rects = listOf(RectF(10f, 700f, 100f, 10f), RectF(50f, 700f, 100f, 10f), RectF(300f, 700f, 50f, 10f))

        val violations = PdfLayoutInspector().footerColumnOverlapViolations(rects, pageIndex = 2)

        val violation = violations.single()
        assertEquals("FOOTER_COLUMN_OVERLAP", violation.rule)
        assertEquals(Severity.HIGH, violation.severity)
        assertEquals(3, violation.page)
        assertTrue(violation.description.contains("Footer-Spalte 1 überlappt Spalte 2"))
    }

    @Test
    fun `separated footer columns raise nothing`() {
        val rects = listOf(RectF(10f, 700f, 40f, 10f), RectF(60f, 700f, 40f, 10f))

        assertTrue(PdfLayoutInspector().footerColumnOverlapViolations(rects, 0).isEmpty())
    }

    @Test
    fun `an item table with a single row y has no row height`() {
        val blocks = listOf(TextBlock("Pos.", 20f, 100f, 20f, 8f, 10f), TextBlock("Bezeichnung", 60f, 100f, 40f, 8f, 10f))

        val measurement = PdfLayoutInspector().measureItemTable(blocks, RectF(20f, 100f, 80f, 8f), mm)

        assertNull(measurement.rowHeightMm)
        assertEquals(8f / mm, measurement.heightMm, 0.001f)
    }

    @Test
    fun `the row height is the median spacing between distinct rows`() {
        val blocks = listOf(100f, 110f, 120f, 150f).map { TextBlock("r", 20f, it, 5f, 8f, 10f) }

        val measurement = PdfLayoutInspector().measureItemTable(blocks, RectF(20f, 100f, 5f, 58f), mm)

        assertEquals(10f / mm, measurement.rowHeightMm!!, 0.001f)
    }
}
