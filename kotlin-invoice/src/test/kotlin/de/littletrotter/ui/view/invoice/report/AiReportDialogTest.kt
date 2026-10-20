package de.littletrotter.ui.view.invoice.report

import de.littletrotter.fixture.anInvoiceLlmAnalysis
import de.littletrotter.testing.jobView
import de.littletrotter.application.invoice.port.inbound.GenerationJobView
import de.littletrotter.application.dto.ModelReport
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.fixture.edit
import de.littletrotter.shared.DocumentStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * UI-5 slice 3a — [AiReportDialog] had no dedicated test file; [resolveMarkdown] is exposed
 * `internal` exactly so it can be exercised without Karibu/a UI. Covers the stored-report guard
 * (missing key, blank key, `documentStore.exists` throwing), the load-failure fallback text with
 * and without an exception message, and the `job.invoice` null branch feeding `findLlmAnalyses`.
 */
class AiReportDialogTest {

    private fun job(resultRefs: String? = null, invoice: Invoice? = Invoice().edit { id = 1L }): GenerationJobView =
        jobView {
            this.status = GenerationJobStatus.COMPLETED
            this.resultRefs = resultRefs
            this.invoice = invoice
        }

    @Test
    fun `no llmReportKey at all falls back to the synthesized markdown from analyses`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        val dialog = AiReportDialog({ key -> key }, invoiceDetailQueryPort, documentStore)

        val md = dialog.resolveMarkdown(job(resultRefs = null))

        assertEquals("_Kein KI-Bericht verfügbar._", md)
    }

    @Test
    fun `a blank llmReportKey is treated the same as no key at all`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        val dialog = AiReportDialog({ key -> key }, invoiceDetailQueryPort, documentStore)

        val md = dialog.resolveMarkdown(job(resultRefs = """{"llmReportKey":""}"""))

        assertEquals("_Kein KI-Bericht verfügbar._", md)
    }

    @Test
    fun `a failing documentStore exists check falls back to the synthesized markdown, not a crash`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        val analysis = anInvoiceLlmAnalysis { modelName = "qwen"; status = LlmAnalysisStatus.COMPLETED; summary = "ok" }
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(listOf(analysis).map(ModelReport::from))
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenAnswer { throw RuntimeException("s3 down") }
        val dialog = AiReportDialog({ key -> key }, invoiceDetailQueryPort, documentStore)

        val md = dialog.resolveMarkdown(job(resultRefs = """{"llmReportKey":"reports/llm-1.md"}"""))

        assertTrue(md.contains("qwen"), "must fall back to the synthesized report, got:\n$md")
    }

    @Test
    fun `a failing stored-report load shows the exception message in the fallback text`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("reports/llm-1.md")).thenReturn(true)
        whenever(documentStore.get("reports/llm-1.md")).thenAnswer { throw RuntimeException("read failed") }
        val dialog = AiReportDialog({ key -> key }, invoiceDetailQueryPort, documentStore)

        val md = dialog.resolveMarkdown(job(resultRefs = """{"llmReportKey":"reports/llm-1.md"}"""))

        assertTrue(md.contains("konnte nicht geladen werden"), "got:\n$md")
        assertTrue(md.contains("read failed"), "got:\n$md")
    }

    @Test
    fun `a failing stored-report load with no exception message falls back to the class name`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("reports/llm-1.md")).thenReturn(true)
        whenever(documentStore.get("reports/llm-1.md")).thenAnswer { throw RuntimeException() }
        val dialog = AiReportDialog({ key -> key }, invoiceDetailQueryPort, documentStore)

        val md = dialog.resolveMarkdown(job(resultRefs = """{"llmReportKey":"reports/llm-1.md"}"""))

        assertTrue(md.contains("RuntimeException"), "expected the exception class name as a fallback detail, got:\n$md")
    }

    @Test
    fun `a stored report loads successfully and is returned verbatim`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("reports/llm-1.md")).thenReturn(true)
        whenever(documentStore.get("reports/llm-1.md")).thenReturn("## Bewertung\n8/10".toByteArray())
        val dialog = AiReportDialog({ key -> key }, invoiceDetailQueryPort, documentStore)

        val md = dialog.resolveMarkdown(job(resultRefs = """{"llmReportKey":"reports/llm-1.md"}"""))

        assertEquals("## Bewertung\n8/10", md)
    }

    @Test
    fun `a job without an invoice still queries findLlmAnalyses with a null id`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(null)).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        val dialog = AiReportDialog({ key -> key }, invoiceDetailQueryPort, documentStore)

        val md = dialog.resolveMarkdown(job(resultRefs = null, invoice = null))

        assertEquals("_Kein KI-Bericht verfügbar._", md)
    }
}
