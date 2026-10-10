package de.littletrotter.infrastructure.export.workbook

import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.util.DefaultTempFileCreationStrategy
import org.apache.poi.util.TempFile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class WorkbookWriterTest {

    /** Points POI's temp-file machinery at a directory this test owns for the duration of
     *  [block], so "is the temp directory clean" can be asserted deterministically instead of
     *  guessing at shared-JVM `java.io.tmpdir` state. */
    private fun <R> withPoiTempDir(dir: File, block: () -> R): R =
        TempFile.withStrategy(DefaultTempFileCreationStrategy(dir)) { block() }

    @Test
    fun `write produces a workbook that XSSFWorkbook and OPCPackage open without repair`() {
        val bytes = writeWorkbook { writer ->
            val sheet = writer.sheet("Sheet")
            sheet.header(listOf("A"), listOf(8))
            sheet.row().text("value")
        }

        readWorkbook(bytes).use { wb ->
            assertThat(wb.numberOfSheets).isEqualTo(1)
            assertThat(wb.getSheetAt(0).getRow(1).getCell(0).stringCellValue).isEqualTo("value")
        }

        val pkg = OPCPackage.open(ByteArrayInputStream(bytes))
        try {
            assertThat(pkg.parts).isNotEmpty()
        } finally {
            pkg.revert()
        }
    }

    @Test
    fun `write forces formula recalculation so Excel, LibreOffice and Sheets all compute on open`() {
        val bytes = writeWorkbook { writer ->
            val sheet = writer.sheet("Sheet")
            sheet.header(listOf("A"), listOf(8))
            sheet.row().text("value")
        }
        readWorkbook(bytes).use { wb -> assertThat(wb.forceFormulaRecalculation).isTrue() }
    }

    @Test
    fun `moveToFront reorders a sheet written after others to the first tab position`() {
        val bytes = writeWorkbook { writer ->
            writer.sheet("Positionen").header(listOf("A"), listOf(8))
            writer.sheet("Rechnungen").header(listOf("A"), listOf(8))
            val export = writer.sheet("Export")
            export.header(listOf("A"), listOf(8))
            writer.moveToFront(export)
        }
        readWorkbook(bytes).use { wb ->
            assertThat(wb.getSheetName(0)).isEqualTo("Export")
            assertThat(wb.numberOfSheets).isEqualTo(3)
        }
    }

    @Test
    fun `temp files are disposed after a normal write`(@TempDir tempDir: File) {
        withPoiTempDir(tempDir) {
            val output = ByteArrayOutputStream()
            WorkbookWriter().use { writer ->
                val sheet = writer.sheet("Sheet")
                sheet.header(listOf("A"), listOf(8))
                repeat(ROWS_BEYOND_WINDOW) { sheet.row().text("row") }
                writer.write(output)
            }
        }
        assertThat(listPoiTempFiles(tempDir)).isEmpty()
    }

    @Test
    fun `temp files are disposed when an exception is thrown mid-write`(@TempDir tempDir: File) {
        assertThatThrownBy {
            withPoiTempDir(tempDir) {
                WorkbookWriter().use { writer ->
                    val sheet = writer.sheet("Sheet")
                    sheet.header(listOf("A"), listOf(8))
                    repeat(ROWS_BEYOND_WINDOW) { sheet.row().text("row") }
                    error("boom")
                }
            }
        }.isInstanceOf(IllegalStateException::class.java).hasMessage("boom")
        assertThat(listPoiTempFiles(tempDir)).isEmpty()
    }

    @Test
    fun `70000 rows write without exceeding the style limit and without leaving temp files`(
        @TempDir tempDir: File,
    ) {
        val output = ByteArrayOutputStream()
        withPoiTempDir(tempDir) {
            WorkbookWriter().use { writer ->
                val sheet = writer.sheet("Sheet")
                sheet.header(listOf("A", "B"), listOf(8, 8))
                repeat(MANY_ROWS) { sheet.row().text("x").integer(it.toLong()) }
                writer.write(output)
            }
        }
        assertThat(listPoiTempFiles(tempDir)).isEmpty()

        readWorkbook(output.toByteArray()).use { wb ->
            assertThat(wb.getSheetAt(0).lastRowNum).isEqualTo(MANY_ROWS)
            // header + text + integer styles only — nowhere near POI's 64 000-style ceiling,
            // proving styles were reused rather than created per row.
            assertThat(wb.numCellStyles).isLessThan(STYLE_COUNT_CEILING)
        }
    }

    // ── OOXML core/extended properties ─────────────────────────────────────────────────

    @Test
    fun `setCoreProperties writes title, creator and application, and the file still opens without repair`() {
        val output = ByteArrayOutputStream()
        WorkbookWriter().use { writer ->
            writer.setCoreProperties(
                title = "Little Trotter Haflinger (0.7.0-SNAPSHOT) — This invoice: RE-2026-001",
                creator = "Little Trotter Haflinger (0.7.0-SNAPSHOT)",
                application = "Little Trotter Haflinger (0.7.0-SNAPSHOT)",
            )
            val sheet = writer.sheet("Sheet")
            sheet.header(listOf("A"), listOf(8))
            sheet.row().text("value")
            writer.write(output)
        }
        val bytes = output.toByteArray()

        readWorkbook(bytes).use { wb ->
            val properties = wb.properties
            assertThat(properties.coreProperties.title).isEqualTo("Little Trotter Haflinger (0.7.0-SNAPSHOT) — This invoice: RE-2026-001")
            assertThat(properties.coreProperties.creator).isEqualTo("Little Trotter Haflinger (0.7.0-SNAPSHOT)")
            assertThat(properties.extendedProperties.application).isEqualTo("Little Trotter Haflinger (0.7.0-SNAPSHOT)")
        }

        val pkg = OPCPackage.open(ByteArrayInputStream(bytes))
        try {
            assertThat(pkg.parts).isNotEmpty()
        } finally {
            pkg.revert()
        }
    }

    @Test
    fun `omitting setCoreProperties still produces a workbook that opens without repair`() {
        val bytes = writeWorkbook { writer ->
            val sheet = writer.sheet("Sheet")
            sheet.header(listOf("A"), listOf(8))
            sheet.row().text("value")
        }
        readWorkbook(bytes).use { wb -> assertThat(wb.numberOfSheets).isEqualTo(1) }
    }

    private fun listPoiTempFiles(dir: File): List<File> = dir.walkTopDown().filter { it.isFile }.toList()

    private companion object {
        const val ROWS_BEYOND_WINDOW = 150
        const val MANY_ROWS = 70_000
        const val STYLE_COUNT_CEILING = 100
    }
}
