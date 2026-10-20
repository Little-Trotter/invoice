package de.littletrotter.xpdf.impl

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class PdfBoxWorkingSetTest {

    private fun pdf(pages: Int): ByteArray = PDDocument().use { doc ->
        repeat(pages) { doc.addPage(PDPage()) }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    @Test
    fun `a document loaded with a tiny threshold saves and reloads with all pages`() {
        val workingSet = PdfBoxWorkingSet(1)

        val saved = workingSet.load(pdf(3)).use { workingSet.save(it) }

        org.apache.pdfbox.Loader.loadPDF(saved).use { assertEquals(3, it.numberOfPages) }
    }

    @Test
    fun `collect returns exactly what was written, in memory or spilled`() {
        val data = ByteArray(5_000) { (it % 251).toByte() }

        assertArrayEquals(data, PdfBoxWorkingSet(1).collect { it.write(data) })
        assertArrayEquals(data, PdfBoxWorkingSet().collect { it.write(data) })
    }
}
