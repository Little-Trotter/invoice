package de.littletrotter.infrastructure.export.workbook

import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.OutputStream

/**
 * Owns one streaming OOXML workbook for the lifetime of a single export. SXSSF spills rows to
 * temp files once its in-memory window is exceeded, so every use must go through [close] (or,
 * idiomatically, Kotlin's [use]) — a missed [close] leaks those temp files for the life of the
 * process, including when an exception is thrown while rows are still being built, before
 * [write] is ever reached. [close] is native `by workbook` delegation, not an override: POI's own
 * [SXSSFWorkbook.close] already calls the (deprecated, POI 5.5.1) `dispose()` before closing the
 * underlying workbook, so delegating straight to it gets both.
 *
 * ```
 * WorkbookWriter().use { writer ->
 *     val sheet = writer.sheet("Data")
 *     sheet.header(listOf("Column A"), listOf(16))
 *     sheet.row().text("value")
 *     writer.write(outputStream)
 * }
 * ```
 */
class WorkbookWriter(
    private val workbook: SXSSFWorkbook = SXSSFWorkbook(ROW_ACCESS_WINDOW_SIZE).apply { isCompressTempFiles = true },
) : AutoCloseable by workbook {

    private val styles = CellStyles(workbook)
    private val sheetWriters = mutableListOf<SheetWriter>()

    /** Creates a new sheet. [name] is sanitised, never trusted as already Excel-safe — see
     *  [SheetNames]. */
    fun sheet(name: String): SheetWriter {
        val poiSheet = workbook.createSheet(SheetNames.sanitize(name))
        val writer = SheetWriter(poiSheet, styles)
        sheetWriters += writer
        return writer
    }

    /** Moves [sheet] to the first tab position. Used for the "Export" sheet (R7): its per-sheet
     *  record counts depend on every other sheet already being written, so it is written last but
     *  has to appear first. */
    fun moveToFront(sheet: SheetWriter) {
        workbook.setSheetOrder(sheet.name, 0)
    }

    /**
     * The workbook's own OOXML core/extended properties: some file managers (and
     * Excel's own "Info" pane) show these before a reader opens a single sheet. [title] names the
     * export's own product line and selection, [creator]/[application] both name the product line
     * alone (there is no per-user "creator" identity here — the acting user is the "Export"
     * sheet's own field, not this file-level metadata).
     */
    fun setCoreProperties(title: String, creator: String, application: String) {
        val properties = workbook.xssfWorkbook.properties
        properties.coreProperties.title = title
        properties.coreProperties.creator = creator
        properties.extendedProperties.application = application
    }

    /** Finalises every sheet (freeze pane, autofilter, column widths — see [SheetWriter.finish])
     *  and streams the workbook to [output]. Does not close [output] and does not dispose temp
     *  files; that is [close]'s job, so this can safely run inside a [use] block.
     *
     *  POI writes every formula (`SUMIF`/`COUNTIF`/`ROUND`) as a string and never
     *  evaluates it itself — the cached value that would otherwise sit in the file is simply
     *  absent. [org.apache.poi.ss.usermodel.Workbook.setForceFormulaRecalculation] marks the
     *  workbook dirty so Excel, LibreOffice and Sheets all recompute every formula on open instead
     *  of showing an empty control column until the reader manually forces a recalculation. */
    fun write(output: OutputStream) {
        sheetWriters.forEach { it.finish() }
        workbook.forceFormulaRecalculation = true
        workbook.write(output)
    }

    private companion object {
        /** Rows kept in memory before SXSSF flushes to a temp file. A larger window defeats the
         *  purpose of streaming, so this is not caller-configurable. */
        const val ROW_ACCESS_WINDOW_SIZE = 100
    }
}
