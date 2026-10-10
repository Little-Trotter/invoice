package de.littletrotter.xpdf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Branch coverage tests for PdfLayoutInspector.InspectionReport.toLlmText()
 * Tests various combinations of report data to exercise all branches.
 */
class InspectionReportTest {

    @Test
    fun `toLlmText includes page count and dimensions`() {
        val report = PdfLayoutInspector.InspectionReport(
            pageCount = 3,
            pages = emptyList(),
            violations = emptyList(),
            pageWidthMm = 210f,
            pageHeightMm = 297f,
            itemTableHeightMm = null,
            itemTableRowHeightMm = null
        )

        val text = report.toLlmText()

        assertAll(
            { assertTrue(text.contains("Seiten: 3"), "Should include page count") },
            { assertTrue(text.contains("210"), "Should include page width") },
            { assertTrue(text.contains("297"), "Should include page height") }
        )
    }

    @Test
    fun `toLlmText handles present table and row height`() {
        val report = PdfLayoutInspector.InspectionReport(
            pageCount = 1,
            pages = emptyList(),
            violations = emptyList(),
            pageWidthMm = 210f,
            pageHeightMm = 297f,
            itemTableHeightMm = 45.0f,
            itemTableRowHeightMm = 12.0f
        )

        val text = report.toLlmText()

        assertAll(
            { assertTrue(text.contains("Positionstabellenhöhe:"), "Should include table height label") },
            { assertTrue(text.contains("Zeilenhöhe:"), "Should include row height label") },
            { assertFalse(text.contains("nicht gemessen"), "Should not show 'not measured' when values are present") }
        )
    }

    @Test
    fun `toLlmText shows null table height as 'nicht gemessen'`() {
        val report = PdfLayoutInspector.InspectionReport(
            pageCount = 1,
            pages = emptyList(),
            violations = emptyList(),
            pageWidthMm = 210f,
            pageHeightMm = 297f,
            itemTableHeightMm = null,
            itemTableRowHeightMm = null
        )

        val text = report.toLlmText()

        assertAll(
            { assertTrue(text.contains("nicht gemessen"), "Should indicate table height not measured") }
        )
    }

    @Test
    fun `toLlmText includes multiple pages with footer columns`() {
        val rect1 = PdfLayoutInspector.RectF(x = 20f, y = 700f, width = 50f, height = 20f)
        val rect2 = PdfLayoutInspector.RectF(x = 100f, y = 700f, width = 50f, height = 20f)

        val page1 = PdfLayoutInspector.PageLayout(
            pageIndex = 0,
            pageWidthPt = 595f,
            pageHeightPt = 842f,
            textBlocks = emptyList(),
            footerColumnRects = listOf(rect1, rect2),
            itemTableRect = null,
            pageNumberRect = null,
            addressBlockRect = null
        )

        val page2 = PdfLayoutInspector.PageLayout(
            pageIndex = 1,
            pageWidthPt = 595f,
            pageHeightPt = 842f,
            textBlocks = emptyList(),
            footerColumnRects = listOf(rect1),
            itemTableRect = null,
            pageNumberRect = null,
            addressBlockRect = null
        )

        val report = PdfLayoutInspector.InspectionReport(
            pageCount = 2,
            pages = listOf(page1, page2),
            violations = emptyList(),
            pageWidthMm = 210f,
            pageHeightMm = 297f,
            itemTableHeightMm = null,
            itemTableRowHeightMm = null
        )

        val text = report.toLlmText()

        assertAll(
            { assertTrue(text.contains("Seite 1:"), "Should include first page") },
            { assertTrue(text.contains("Seite 2:"), "Should include second page") },
            { assertTrue(text.contains("Footer-Spalten: 2"), "Should count footer columns on page 1") }
        )
    }

    @Test
    fun `toLlmText marks pages without footer columns`() {
        val page = PdfLayoutInspector.PageLayout(
            pageIndex = 0,
            pageWidthPt = 595f,
            pageHeightPt = 842f,
            textBlocks = emptyList(),
            footerColumnRects = emptyList(),
            itemTableRect = null,
            pageNumberRect = null,
            addressBlockRect = null
        )

        val report = PdfLayoutInspector.InspectionReport(
            pageCount = 1,
            pages = listOf(page),
            violations = emptyList(),
            pageWidthMm = 210f,
            pageHeightMm = 297f,
            itemTableHeightMm = null,
            itemTableRowHeightMm = null
        )

        val text = report.toLlmText()

        assertTrue(text.contains("Footer-Spalten: 0"), "Should indicate zero footer columns")
    }

    @Test
    fun `toLlmText shows item table presence on page`() {
        val tableRect = PdfLayoutInspector.RectF(x = 100f, y = 100f, width = 400f, height = 300f)

        val page = PdfLayoutInspector.PageLayout(
            pageIndex = 0,
            pageWidthPt = 595f,
            pageHeightPt = 842f,
            textBlocks = emptyList(),
            footerColumnRects = emptyList(),
            itemTableRect = tableRect,
            pageNumberRect = null,
            addressBlockRect = null
        )

        val report = PdfLayoutInspector.InspectionReport(
            pageCount = 1,
            pages = listOf(page),
            violations = emptyList(),
            pageWidthMm = 210f,
            pageHeightMm = 297f,
            itemTableHeightMm = null,
            itemTableRowHeightMm = null
        )

        val text = report.toLlmText()

        assertTrue(text.contains("Positionstabelle: ja"), "Should indicate table is present")
    }

    @Test
    fun `toLlmText shows item table absence`() {
        val page = PdfLayoutInspector.PageLayout(
            pageIndex = 0,
            pageWidthPt = 595f,
            pageHeightPt = 842f,
            textBlocks = emptyList(),
            footerColumnRects = emptyList(),
            itemTableRect = null,
            pageNumberRect = null,
            addressBlockRect = null
        )

        val report = PdfLayoutInspector.InspectionReport(
            pageCount = 1,
            pages = listOf(page),
            violations = emptyList(),
            pageWidthMm = 210f,
            pageHeightMm = 297f,
            itemTableHeightMm = null,
            itemTableRowHeightMm = null
        )

        val text = report.toLlmText()

        assertTrue(text.contains("Positionstabelle: nein"), "Should indicate table is absent")
    }

    @Test
    fun `toLlmText shows page number presence and absence`() {
        val pageNoRect = PdfLayoutInspector.RectF(x = 500f, y = 750f, width = 70f, height = 20f)

        val pageWithNumber = PdfLayoutInspector.PageLayout(
            pageIndex = 0,
            pageWidthPt = 595f,
            pageHeightPt = 842f,
            textBlocks = emptyList(),
            footerColumnRects = emptyList(),
            itemTableRect = null,
            pageNumberRect = pageNoRect,
            addressBlockRect = null
        )

        val pageWithoutNumber = PdfLayoutInspector.PageLayout(
            pageIndex = 1,
            pageWidthPt = 595f,
            pageHeightPt = 842f,
            textBlocks = emptyList(),
            footerColumnRects = emptyList(),
            itemTableRect = null,
            pageNumberRect = null,
            addressBlockRect = null
        )

        val report = PdfLayoutInspector.InspectionReport(
            pageCount = 2,
            pages = listOf(pageWithNumber, pageWithoutNumber),
            violations = emptyList(),
            pageWidthMm = 210f,
            pageHeightMm = 297f,
            itemTableHeightMm = null,
            itemTableRowHeightMm = null
        )

        val text = report.toLlmText()

        assertAll(
            { assertTrue(text.contains("Seitenzahl: true"), "Page 1 should have page number") },
            { assertTrue(text.contains("Seitenzahl: false"), "Page 2 should not have page number") }
        )
    }

    @Test
    fun `toLlmText shows address block only on first page`() {
        val addressRect = PdfLayoutInspector.RectF(x = 50f, y = 100f, width = 100f, height = 40f)

        val page1 = PdfLayoutInspector.PageLayout(
            pageIndex = 0,
            pageWidthPt = 595f,
            pageHeightPt = 842f,
            textBlocks = emptyList(),
            footerColumnRects = emptyList(),
            itemTableRect = null,
            pageNumberRect = null,
            addressBlockRect = addressRect
        )

        val page2 = PdfLayoutInspector.PageLayout(
            pageIndex = 1,
            pageWidthPt = 595f,
            pageHeightPt = 842f,
            textBlocks = emptyList(),
            footerColumnRects = emptyList(),
            itemTableRect = null,
            pageNumberRect = null,
            addressBlockRect = null
        )

        val report = PdfLayoutInspector.InspectionReport(
            pageCount = 2,
            pages = listOf(page1, page2),
            violations = emptyList(),
            pageWidthMm = 210f,
            pageHeightMm = 297f,
            itemTableHeightMm = null,
            itemTableRowHeightMm = null
        )

        val text = report.toLlmText()

        assertAll(
            { assertTrue(text.contains("Adressfenster: ja"), "Page 1 should show address block presence") },
            { assertFalse(text.split("Seite 2:")[1].contains("Adressfenster:"), "Page 2 should not mention address block") }
        )
    }

    @Test
    fun `toLlmText lists violations when present`() {
        val violation1 = PdfLayoutInspector.Violation(
            severity = PdfLayoutInspector.Severity.HIGH,
            rule = "TEST_RULE_1",
            description = "Test violation 1",
            page = 1
        )

        val violation2 = PdfLayoutInspector.Violation(
            severity = PdfLayoutInspector.Severity.MEDIUM,
            rule = "TEST_RULE_2",
            description = "Test violation 2",
            page = 2
        )

        val report = PdfLayoutInspector.InspectionReport(
            pageCount = 2,
            pages = emptyList(),
            violations = listOf(violation1, violation2),
            pageWidthMm = 210f,
            pageHeightMm = 297f,
            itemTableHeightMm = null,
            itemTableRowHeightMm = null
        )

        val text = report.toLlmText()

        assertAll(
            { assertTrue(text.contains("Verletzungen (2):"), "Should list count of violations") },
            { assertTrue(text.contains("TEST_RULE_1"), "Should include rule ID") },
            { assertTrue(text.contains("Test violation 1"), "Should include violation description") }
        )
    }

    @Test
    fun `toLlmText shows no violations message when empty`() {
        val report = PdfLayoutInspector.InspectionReport(
            pageCount = 1,
            pages = emptyList(),
            violations = emptyList(),
            pageWidthMm = 210f,
            pageHeightMm = 297f,
            itemTableHeightMm = null,
            itemTableRowHeightMm = null
        )

        val text = report.toLlmText()

        assertTrue(text.contains("Verletzungen: keine"), "Should indicate no violations")
    }
}
