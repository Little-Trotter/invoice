package de.littletrotter.domain.invoice

import de.littletrotter.fixture.anInvoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class InvoiceLlmAnalysisTest {

    private val at = OffsetDateTime.parse("2026-10-01T10:00:00+00:00")
    private val invoice = anInvoice()

    @Test
    fun `start creates a PENDING record for the model`() {
        val analysis = InvoiceLlmAnalysis.start(invoice, "model-a")

        assertSame(invoice, analysis.invoice)
        assertEquals("model-a", analysis.modelName)
        assertEquals(LlmAnalysisStatus.PENDING, analysis.status)
    }

    @Test
    fun `complete stores the summary and clears issues`() {
        val analysis = InvoiceLlmAnalysis.start(invoice, "m").also { it.fail("err", "s", at) }

        analysis.complete("done", at.plusMinutes(1))

        assertEquals(LlmAnalysisStatus.COMPLETED, analysis.status)
        assertEquals("done", analysis.summary)
        assertNull(analysis.issues)
        assertEquals(at.plusMinutes(1), analysis.analyzedAt)
    }

    @Test
    fun `fail stores issues and summary`() {
        val analysis = InvoiceLlmAnalysis.start(invoice, "m")

        analysis.fail("err", "note", at)

        assertEquals(LlmAnalysisStatus.FAILED, analysis.status)
        assertEquals("err", analysis.issues)
        assertEquals("note", analysis.summary)
        assertEquals(at, analysis.analyzedAt)
    }

    @Test
    fun `disable resolves the record without issues`() {
        val analysis = InvoiceLlmAnalysis.start(invoice, "m")

        analysis.disable("not requested", at)

        assertEquals(LlmAnalysisStatus.DISABLED, analysis.status)
        assertEquals("not requested", analysis.summary)
        assertNull(analysis.issues)
    }

    @Test
    fun `requeue puts a resolved record back to PENDING`() {
        val analysis = InvoiceLlmAnalysis.start(invoice, "m").also { it.complete("x", at) }

        analysis.requeue()

        assertEquals(LlmAnalysisStatus.PENDING, analysis.status)
    }

    @Test
    fun `adoptModel replaces the model name`() {
        val analysis = InvoiceLlmAnalysis.start(invoice, "old")

        analysis.adoptModel("new")

        assertEquals("new", analysis.modelName)
    }
}
