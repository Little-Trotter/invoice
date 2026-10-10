package de.littletrotter.infrastructure.export.workbook

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Writes [build]'s content through a fresh [WorkbookWriter] and returns the raw `.xlsx` bytes —
 *  the writer is always disposed, even if [build] throws. */
fun writeWorkbook(build: (WorkbookWriter) -> Unit): ByteArray {
    val output = ByteArrayOutputStream()
    WorkbookWriter().use { writer ->
        build(writer)
        writer.write(output)
    }
    return output.toByteArray()
}

/** Reads bytes produced by [writeWorkbook] back with a real POI reader — the whole point of these
 *  tests is that the file Excel would open, not that a setter was called. */
fun readWorkbook(bytes: ByteArray): XSSFWorkbook = XSSFWorkbook(ByteArrayInputStream(bytes))
