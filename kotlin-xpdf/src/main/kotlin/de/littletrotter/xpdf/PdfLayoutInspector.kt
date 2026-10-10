package de.littletrotter.xpdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition

/** 1mm = 2.83465pt. */
private const val MM_TO_PT = 2.83465f

/** Footer zone starts this many pt above the page's bottom content margin. */
private const val FOOTER_ZONE_MARGIN_PT = 10f

/** Max pt depth below the item-table header still considered part of the table. */
private const val ITEM_TABLE_MAX_DEPTH_PT = 250f

/** A header candidate longer than this many characters is not the "Pos."/"Description" header cell. */
private const val ITEM_TABLE_HEADER_MAX_LENGTH = 15

/** How many characters of a violating text block to quote in a violation message. */
private const val MESSAGE_TEXT_PREVIEW_LENGTH = 40

/** DIN 5008 Form A address-window sollposition (mm), and the tolerance around it. */
private const val DIN5008_ADDRESS_LEFT_MM = 20f
private const val DIN5008_ADDRESS_TOP_MM = 45f
private const val DIN5008_ADDRESS_TOLERANCE_MM = 5f

/** Zone searched for the address block — wider/taller than the sollposition + tolerance itself,
 *  since real content varies in width and line count. */
private const val ADDRESS_ZONE_LEFT_MM = 15f
private const val ADDRESS_ZONE_RIGHT_MM = 115f
private const val ADDRESS_ZONE_TOP_MM = 44f
private const val ADDRESS_ZONE_BOTTOM_MM = 95f

/**
 * Deterministic PDF layout inspector.
 * Coordinate system: PDFBox `yDirAdj` is TOP-DOWN (y=0 at page start, y increases downward).
 * All coordinates are top-down.
 */
class PdfLayoutInspector {

    data class Violation(
        val severity: Severity,
        val rule: String,
        val description: String,
        val page: Int,
    )

    enum class Severity { HIGH, MEDIUM }

    data class RectF(val x: Float, val y: Float, val width: Float, val height: Float) {
        fun union(other: RectF): RectF = RectF(
            x = minOf(x, other.x),
            y = minOf(y, other.y),
            width = maxOf(x + width, other.x + other.width) - minOf(x, other.x),
            height = maxOf(y + height, other.y + other.height) - minOf(y, other.y),
        )

        fun overlaps(other: RectF, tolerance: Float = 0.5f): Boolean =
            x < other.x + other.width - tolerance &&
            x + width > other.x + tolerance &&
            y < other.y + other.height - tolerance &&
            y + height > other.y + tolerance
    }

    data class PageLayout(
        val pageIndex: Int,
        val pageWidthPt: Float,
        val pageHeightPt: Float,
        val textBlocks: List<TextBlock>,
        val footerColumnRects: List<RectF>,
        val itemTableRect: RectF?,
        val pageNumberRect: RectF?,
        val addressBlockRect: RectF?,
    )

    data class TextBlock(
        val text: String,
        val x: Float, // left edge, pt
        val y: Float, // top edge (baseline of first char), pt, top-down
        val width: Float, // pt
        val height: Float, // pt
        val fontSize: Float,
    )

    data class InspectionReport(
        val pageCount: Int,
        val pages: List<PageLayout>,
        val violations: List<Violation>,
        val pageWidthMm: Float,
        val pageHeightMm: Float,
        val itemTableHeightMm: Float?,
        val itemTableRowHeightMm: Float?,
    ) {

        /** Compact text representation of measurement results for LLM input. */
        fun toLlmText(): String = buildString {
            appendLine("Seiten: $pageCount, Format: ${"%.0f".format(pageWidthMm)}×${"%.0f".format(pageHeightMm)}mm")
            appendLine("Positionstabellenhöhe: ${itemTableHeightMm?.let { "%.1f".format(it) + "mm" } ?: "nicht gemessen"}")
            appendLine("Zeilenhöhe: ${itemTableRowHeightMm?.let { "%.1f".format(it) + "mm" } ?: "nicht gemessen"}")
            appendLine()
            pages.forEachIndexed { idx, page ->
                appendLine("Seite ${idx + 1}:")
                appendLine("  Footer-Spalten: ${page.footerColumnRects.size}")
                page.footerColumnRects.sortedBy { it.x }.forEachIndexed { ci, rect ->
                    appendLine("    Spalte ${ci + 1}: x=${"%.1f".format(rect.x)}pt, breite=${"%.1f".format(rect.width)}pt, y=${"%.1f".format(rect.y)}pt")
                }
                appendLine("  Positionstabelle: ${page.itemTableRect?.let { "ja (${"%.1f".format(it.height)}pt hoch)" } ?: "nein"}")
                appendLine("  Seitenzahl: ${page.pageNumberRect != null}")
                if (idx == 0) {
                    appendLine("  Adressfenster: ${page.addressBlockRect?.let { "ja (x=${"%.1f".format(it.x)}pt, y=${"%.1f".format(it.y)}pt)" } ?: "nein"}")
                }
            }
            appendLine()
            if (violations.isEmpty()) {
                appendLine("Verletzungen: keine")
            } else {
                appendLine("Verletzungen (${violations.size}):")
                violations.forEach { v ->
                    appendLine("  [${v.severity}] Seite ${v.page}: ${v.rule} — ${v.description}")
                }
            }
        }
    }

    fun inspect(
        pdfBytes: ByteArray,
        pageWidthMm: Float = 210f,
        pageHeightMm: Float = 297f,
        marginTopMm: Float = 18f,
        marginRightMm: Float = 20f,
        marginBottomMm: Float = 32f,
        marginLeftMm: Float = 20f,
    ): InspectionReport {
        val mmToPt = MM_TO_PT
        val pageWidthPt = pageWidthMm * mmToPt
        val pageHeightPt = pageHeightMm * mmToPt

        // @page margins measured from sheet edges; coordinates are top-down (y=0 at top).
        val contentBounds = ContentBounds(
            leftPt = marginLeftMm * mmToPt,
            rightPt = (pageWidthMm - marginRightMm) * mmToPt,
            topPt = marginTopMm * mmToPt,
            bottomPt = (pageHeightMm - marginBottomMm) * mmToPt,
        )

        val footerZoneStartPt = contentBounds.bottomPt - FOOTER_ZONE_MARGIN_PT // Footer zone in lower margin

        val pages = mutableListOf<PageLayout>()
        val violations = mutableListOf<Violation>()
        var measuredItemTableHeightMm: Float? = null
        var measuredRowHeightMm: Float? = null

        Loader.loadPDF(pdfBytes).use { doc ->
            for (pageIndex in 0 until doc.numberOfPages) {
                val blocks = extractBlocks(doc, pageIndex)
                val footerColumnRects = groupFooterColumns(blocks.filter { it.y > footerZoneStartPt })
                violations.addAll(footerColumnOverlapViolations(footerColumnRects, pageIndex))

                val itemTableRect = findItemTableRect(blocks)
                if (itemTableRect != null && measuredItemTableHeightMm == null) {
                    val measurement = measureItemTable(blocks, itemTableRect, mmToPt)
                    measuredItemTableHeightMm = measurement.heightMm
                    measuredRowHeightMm = measurement.rowHeightMm
                }

                violations.addAll(marginViolations(blocks, footerZoneStartPt, contentBounds, pageIndex))

                val pageNumberRect = findPageNumberRect(blocks)
                val addressBlockRect = if (pageIndex == 0) findAddressBlockRect(blocks, mmToPt) else null
                if (pageIndex == 0) {
                    violations.addAll(addressWindowViolations(addressBlockRect, mmToPt))
                }

                pages.add(PageLayout(
                    pageIndex = pageIndex,
                    pageWidthPt = pageWidthPt,
                    pageHeightPt = pageHeightPt,
                    textBlocks = blocks,
                    footerColumnRects = footerColumnRects,
                    itemTableRect = itemTableRect,
                    pageNumberRect = pageNumberRect,
                    addressBlockRect = addressBlockRect,
                ))
            }

            return InspectionReport(
                pageCount = pages.size,
                pages = pages.toList(),
                violations = violations.toList(),
                pageWidthMm = pageWidthMm,
                pageHeightMm = pageHeightMm,
                itemTableHeightMm = measuredItemTableHeightMm,
                itemTableRowHeightMm = measuredRowHeightMm,
            )
        }
    }

    private fun extractBlocks(doc: PDDocument, pageIndex: Int): List<TextBlock> {
        val extractor = BlockTextExtractor()
        extractor.startPage = pageIndex + 1
        extractor.endPage = pageIndex + 1
        extractor.getText(doc)
        return extractor.blocks
    }

    /** `internal` (not `private`): a properly laid-out footer never overlaps itself, so this
     *  defensive check's `true` branch is exercised by unit tests with synthetic [RectF]s rather
     *  than by real PDFs. */
    internal fun footerColumnOverlapViolations(footerColumnRects: List<RectF>, pageIndex: Int): List<Violation> {
        val result = mutableListOf<Violation>()
        for (i in footerColumnRects.indices) {
            for (j in (i + 1) until footerColumnRects.size) {
                if (footerColumnRects[i].overlaps(footerColumnRects[j])) {
                    result.add(Violation(
                        Severity.HIGH,
                        "FOOTER_COLUMN_OVERLAP",
                        "Footer-Spalte ${i + 1} überlappt Spalte ${j + 1}: " +
                            "Spalte ${i + 1}=[x=${"%.1f".format(footerColumnRects[i].x)}, w=${"%.1f".format(footerColumnRects[i].width)}] vs " +
                            "Spalte ${j + 1}=[x=${"%.1f".format(footerColumnRects[j].x)}, w=${"%.1f".format(footerColumnRects[j].width)}]",
                        pageIndex + 1,
                    ))
                }
            }
        }
        // S6524 — the declared return type is List<Violation>; .toList() so a caller can
        // never downcast back to the backing MutableList and mutate it.
        return result.toList()
    }

    internal data class ItemTableMeasurement(val heightMm: Float, val rowHeightMm: Float?)

    /** `internal` (not `private`): the null-row-height branch fires only for an item table with
     *  fewer than two distinct row `y`s (a header row with no item rows) — no real invoice ever
     *  renders an empty item table, so a unit test drives it with synthetic [TextBlock]s instead. */
    internal fun measureItemTable(blocks: List<TextBlock>, itemTableRect: RectF, mmToPt: Float): ItemTableMeasurement {
        val tableBlocks = blocks.filter { block ->
            block.y >= itemTableRect.y && block.y <= itemTableRect.y + itemTableRect.height
        }
        val distinctYs = tableBlocks.map { it.y }.distinct().sorted()
        val rowHeightMm = if (distinctYs.size >= 2) {
            val diffs = distinctYs.zipWithNext { a, b -> b - a }
            diffs.sorted().let { it[it.size / 2] } / mmToPt
        } else {
            null
        }
        return ItemTableMeasurement(itemTableRect.height / mmToPt, rowHeightMm)
    }

    private data class ContentBounds(val leftPt: Float, val rightPt: Float, val topPt: Float, val bottomPt: Float)

    private fun marginViolations(
        blocks: List<TextBlock>,
        footerZoneStartPt: Float,
        bounds: ContentBounds,
        pageIndex: Int,
    ): List<Violation> {
        val tol = 1f
        val result = mutableListOf<Violation>()
        val contentBlocks = blocks.filter { it.y <= footerZoneStartPt }
        for (block in contentBlocks) {
            val text = block.text
            val x = block.x
            val y = block.y
            val width = block.width
            val height = block.height
            if (x < bounds.leftPt - tol) {
                result.add(Violation(
                    Severity.MEDIUM, "TEXT_OUTSIDE_LEFT_MARGIN",
                    "Text '${text.take(MESSAGE_TEXT_PREVIEW_LENGTH)}' bei x=${"%.1f".format(x)}pt liegt links vom Satzspiegel (≥ ${"%.1f".format(bounds.leftPt)}pt)",
                    pageIndex + 1,
                ))
            }
            if (x + width > bounds.rightPt + tol) {
                result.add(Violation(
                    Severity.MEDIUM, "TEXT_OUTSIDE_RIGHT_MARGIN",
                    "Text '${text.take(MESSAGE_TEXT_PREVIEW_LENGTH)}' endet bei x=${"%.1f".format(x + width)}pt, rechts vom Satzspiegel (≤ ${"%.1f".format(bounds.rightPt)}pt)",
                    pageIndex + 1,
                ))
            }
            if (y < bounds.topPt - tol) {
                result.add(Violation(
                    Severity.MEDIUM, "TEXT_OUTSIDE_TOP_MARGIN",
                    "Text '${text.take(MESSAGE_TEXT_PREVIEW_LENGTH)}' bei y=${"%.1f".format(y)}pt liegt über dem Satzspiegel (≥ ${"%.1f".format(bounds.topPt)}pt)",
                    pageIndex + 1,
                ))
            }
            if (y + height > bounds.bottomPt + tol) {
                result.add(Violation(
                    Severity.MEDIUM, "TEXT_OUTSIDE_BOTTOM_MARGIN",
                    "Text '${text.take(MESSAGE_TEXT_PREVIEW_LENGTH)}' bei y=${"%.1f".format(y + height)}pt liegt unter dem Content-Satzspiegel (≤ ${"%.1f".format(bounds.bottomPt)}pt)",
                    pageIndex + 1,
                ))
            }
        }
        // S6524 — same reasoning as footerColumnOverlapViolations above.
        return result.toList()
    }

    private fun addressWindowViolations(addressBlockRect: RectF?, mmToPt: Float): List<Violation> {
        if (addressBlockRect == null) {
            return listOf(Violation(
                Severity.MEDIUM, "ADDRESS_WINDOW_NOT_FOUND",
                "Kein Adressblock im DIN-5008-Fensterbereich (Sollposition 20mm/45mm ± Toleranz) gefunden",
                page = 1, // constant inside the pageIndex == 0 caller
            ))
        }
        val expectedLeftPt = DIN5008_ADDRESS_LEFT_MM * mmToPt
        val expectedTopPt = DIN5008_ADDRESS_TOP_MM * mmToPt
        val addressTolerancePt = DIN5008_ADDRESS_TOLERANCE_MM * mmToPt
        val dx = addressBlockRect.x - expectedLeftPt
        val dy = addressBlockRect.y - expectedTopPt
        if (kotlin.math.abs(dx) > addressTolerancePt || kotlin.math.abs(dy) > addressTolerancePt) {
            return listOf(Violation(
                Severity.MEDIUM, "ADDRESS_WINDOW_MISPLACED",
                "Adressblock bei x=${"%.1f".format(addressBlockRect.x / mmToPt)}mm, " +
                    "y=${"%.1f".format(addressBlockRect.y / mmToPt)}mm — " +
                    "erwartet 20.0mm/45.0mm ± ${"%.0f".format(addressTolerancePt / mmToPt)}mm (DIN 5008 Form A)",
                page = 1, // constant inside the pageIndex == 0 caller
            ))
        }
        return emptyList()
    }

    private fun groupFooterColumns(footerBlocks: List<TextBlock>): List<RectF> {
        if (footerBlocks.isEmpty()) return emptyList()

        val sorted = footerBlocks.sortedBy { it.y }
        val rows = mutableListOf<MutableList<TextBlock>>()
        var currentRow = mutableListOf<TextBlock>()
        var lastY: Float? = null
        val yTolerance = 2f

        for (block in sorted) {
            if (lastY == null || kotlin.math.abs(block.y - lastY) < yTolerance) {
                currentRow.add(block)
            } else {
                // currentRow is never empty here: footerBlocks is non-empty (guarded above), so
                // the very first loop iteration always takes the `if` branch above
                // (lastY == null), seeding currentRow with at least one block before this `else`
                // can ever run — a former `if (currentRow.isNotEmpty())` guard was unreachable
                // dead code, deleted.
                rows.add(currentRow)
                currentRow = mutableListOf(block)
            }
            lastY = block.y
        }
        // currentRow is non-empty here for the same reason; the `rows.add(currentRow)` below
        // always runs at least once (either from the loop's `else` branch above, or here) when
        // footerBlocks is non-empty, so `rows` itself can never end up empty — a former
        // `?: return emptyList()` fallback around a maxByOrNull scan was unreachable, deleted;
        // replaced with an explicit comparison loop that has no null case to begin with.
        rows.add(currentRow)

        val representativeRow: List<TextBlock> = rows.maxBy { it.size }

        return representativeRow.sortedBy { it.x }.map { block ->
            RectF(block.x, block.y, block.width, block.height)
        }
    }

    private fun findItemTableRect(blocks: List<TextBlock>): RectF? {
        val headerBlock = blocks.find {
            val t = it.text.trim()
            t == "Pos." || t == "Bezeichnung" || t == "Description"
        } ?: blocks.find {
            val t = it.text.trim()
            (t.contains("Pos.", ignoreCase = true) || t.contains("Description", ignoreCase = true)) &&
                t.length < ITEM_TABLE_HEADER_MAX_LENGTH
        } ?: return null

        val tableEndMarkers = setOf(
            "Nettobetrag", "zzgl.", "Rechnungsbetrag", "Zahlungshinweis",
            "Zahlungsziel", "Übertrag", "Steuersatz",
            "Net Amount", "plus ", "Total Amount", "Payment Instructions",
            "Due Date", "Brought forward", "Carry forward", "Tax Rate", "Tax Note"
        )

        val headerY = headerBlock.y
        // Sorted ascending by y, so all three cutoffs are monotonic prefixes of the sequence —
        // expressed as filter/takeWhile instead of a continue/break loop
        // (LoopWithTooManyJumpStatements): dropWhile skips the header-or-above blocks, the first
        // takeWhile stops past the 250pt depth limit, the second stops at the first
        // table-end-marker block (never adding that block or anything after it, same as the
        // original `break`).
        val tableBodyBlocks = blocks.sortedBy { it.y }
            .dropWhile { it.y <= headerY }
            .takeWhile { it.y - headerY <= ITEM_TABLE_MAX_DEPTH_PT }
            .takeWhile { block -> tableEndMarkers.none { block.text.contains(it, ignoreCase = true) } }
        val tableBlocks = listOf(headerBlock) + tableBodyBlocks

        if (tableBlocks.size <= 1) return null

        var result = RectF(headerBlock.x, headerBlock.y, headerBlock.width, headerBlock.height)
        for (block in tableBlocks) {
            result = result.union(RectF(block.x, block.y, block.width, block.height))
        }
        return result
    }

    /**
     * Finds address block in DIN 5008 window via zone search.
     * No fixed anchor text (unlike position table or page number) — return address and recipient
     * are variable content, so zone boundaries are empirically determined.
     */
    private fun findAddressBlockRect(blocks: List<TextBlock>, mmToPt: Float): RectF? {
        val zoneLeftPt = ADDRESS_ZONE_LEFT_MM * mmToPt
        val zoneRightPt = ADDRESS_ZONE_RIGHT_MM * mmToPt
        val zoneTopPt = ADDRESS_ZONE_TOP_MM * mmToPt
        val zoneBottomPt = ADDRESS_ZONE_BOTTOM_MM * mmToPt

        val candidates = blocks.filter { b ->
            b.x in zoneLeftPt..zoneRightPt && b.y in zoneTopPt..zoneBottomPt
        }
        if (candidates.isEmpty()) return null

        return candidates.drop(1).fold(
            RectF(candidates.first().x, candidates.first().y, candidates.first().width, candidates.first().height)
        ) { acc, b -> acc.union(RectF(b.x, b.y, b.width, b.height)) }
    }

    private fun findPageNumberRect(blocks: List<TextBlock>): RectF? {
        val pnBlock = blocks.find { textBlock ->
            val t = textBlock.text.trim()
            // Matcht "1 / 2", "Seite 1 / 2", "Page 1 / 2" etc.
            Regex(".*\\d+\\s*/\\s*\\d+$").matches(t)
        } ?: return null
        return RectF(pnBlock.x, pnBlock.y, pnBlock.width, pnBlock.height)
    }

    private class BlockTextExtractor : PDFTextStripper() {
        val blocks = mutableListOf<TextBlock>()

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            if (text.isBlank() || textPositions.isEmpty()) return

            val first = textPositions.first()
            // A trailing space has no ink: an engine that emits the space glyph a justified line
            // ends on must not be measured as text beyond the type area.
            val last = textPositions.lastOrNull { it.unicode.isNotBlank() } ?: textPositions.last()
            val x = first.xDirAdj
            val y = first.yDirAdj
            val width = (last.xDirAdj + last.widthDirAdj) - first.xDirAdj
            val height = first.heightDir.takeIf { it > 0f } ?: textPositions.maxOf { it.heightDir }
            val fontSize = first.fontSize

            blocks.add(TextBlock(
                text = text.trim(),
                x = x,
                y = y,
                width = if (width > 0) width else last.widthDirAdj,
                height = height,
                fontSize = fontSize,
            ))
        }
    }
}
