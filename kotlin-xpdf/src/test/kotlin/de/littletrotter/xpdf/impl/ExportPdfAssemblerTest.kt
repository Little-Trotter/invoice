package de.littletrotter.xpdf.impl

import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceMetaDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.xpdf.testsupport.OutputIntentTestKeys
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/** Which renderer produces the final bytes, and that the chosen mark reaches it. */
class ExportPdfAssemblerTest {

    private val basePdfStage: BasePdfStage = mock()
    private val mustang: MustangPdfRenderer = mock()
    private val selector = ExportWatermarkSelector(OutputIntentTestKeys.verifier, ClockTimeSource.fixedAt(Instant.parse("2026-01-15T10:30:00Z")))
    private val assembler = ExportPdfAssembler(basePdfStage, mustang, selector)

    private val invoice = InvoiceDto(
        invoiceNumber = InvoiceNumberDto("INV-ASM-1"),
        items = emptyList(),
        meta = InvoiceMetaDto(documentLanguage = "de"),
        senderProfile = OutputIntentTestKeys.SELLER,
        draft = true,
    )

    @Test
    fun `valid XML is embedded and the draft mark is handed to the embedding renderer`() {
        val xml = byteArrayOf(1)
        whenever(mustang.renderPdfWithZugferd(any(), any(), any())).thenReturn(byteArrayOf(7))

        val result = assembler.assemble(invoice, xml, embedXml = true, outputIntent = null)

        assertArrayEquals(byteArrayOf(7), result)
        verify(mustang).renderPdfWithZugferd(any(), any(), argThat { this != null })
        verify(basePdfStage, never()).renderBase(any(), any())
    }

    @Test
    fun `invalid XML yields the plain base document without embedding`() {
        whenever(basePdfStage.renderBase(any(), any())).thenReturn(byteArrayOf(9))

        val result = assembler.assemble(invoice, byteArrayOf(1), embedXml = false, outputIntent = null)

        assertArrayEquals(byteArrayOf(9), result)
        verify(mustang, never()).renderPdfWithZugferd(any(), any(), any())
    }

    @Test
    fun `the attachment filename comes from the embedding renderer`() {
        whenever(mustang.expectedZugferdAttachmentFilename("XRechnung")).thenReturn("xrechnung.xml")

        assertEquals("xrechnung.xml", assembler.expectedAttachmentFilename("XRechnung"))
    }
}
