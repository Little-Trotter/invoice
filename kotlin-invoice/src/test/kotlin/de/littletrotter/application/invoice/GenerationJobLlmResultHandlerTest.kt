package de.littletrotter.application.invoice

import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.event.InvoiceLlmAnalysisResolved
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceLlmAnalysisRepository
import de.littletrotter.domain.repository.InvoiceRepository
import jakarta.enterprise.event.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/** Unit tests for the extracted LLM-callback result-handling responsibility. */
class GenerationJobLlmResultHandlerTest {

    private val generationJobRepository: GenerationJobRepository = mock()
    private val llmAnalysisRepository: InvoiceLlmAnalysisRepository = mock()
    private val invoiceRepository: InvoiceRepository = mock()
    private val bus: Event<DomainEvent> = mock()
    private val ops = GenerationJobLlmResultHandler(
        generationJobRepository,
        llmAnalysisRepository,
        invoiceRepository,
        GenerationJobEventDispatcher(bus, null), time = FIXED_TIME_SOURCE,
    )

    private fun pendingAnalysis(invoiceId: Long, model: String = "oMLX"): InvoiceLlmAnalysis =
        InvoiceLlmAnalysis().apply {
            invoice = Invoice().apply { id = invoiceId }
            modelName = model
            status = LlmAnalysisStatus.PENDING
        }

    @Test
    fun `mergeLlmResult overlays new keys onto the existing resultRefs JSON`() {
        val job = GenerationJob().apply { resultRefs = """{"objectKey":"x"}""" }
        whenever(generationJobRepository.save(job)).thenReturn(job)

        val result = ops.mergeLlmResult(job, mapOf("llmStatus" to "COMPLETED"))

        assertEquals("COMPLETED", result.llmStatus)
        assertEquals(true, result.resultRefs?.contains("objectKey"))
        assertEquals(true, result.resultRefs?.contains("COMPLETED"))
    }

    @Test
    fun `updateLlmAnalysisFromResult is a no-op without an invoiceId`() {
        ops.updateLlmAnalysisFromResult(null, mapOf("llmStatus" to "COMPLETED"))

        verify(llmAnalysisRepository, never()).findByInvoiceId(any())
    }

    @Test
    fun `updateLlmAnalysisFromResult is a no-op when no analyses exist`() {
        whenever(llmAnalysisRepository.findByInvoiceId(1L)).thenReturn(emptyList())

        ops.updateLlmAnalysisFromResult(1L, mapOf("llmStatus" to "COMPLETED"))

        verify(llmAnalysisRepository, never()).save(any())
    }

    @Test
    fun `updateLlmAnalysisFromResult resolves a completed run and fires the resolved event`() {
        val analysis = pendingAnalysis(2L)
        whenever(llmAnalysisRepository.findByInvoiceId(2L)).thenReturn(listOf(analysis))
        whenever(invoiceRepository.findById(2L)).thenReturn(Invoice().apply { id = 2L; invoiceNumber = InvoiceNumber("RE-2025-01-00002") })

        ops.updateLlmAnalysisFromResult(2L, mapOf("llmStatus" to "COMPLETED", "llmDurationMs" to "150"))

        assertEquals(LlmAnalysisStatus.COMPLETED, analysis.status)
        verify(bus).fireAsync(any<InvoiceLlmAnalysisResolved>())
    }

    @Test
    fun `updateLlmAnalysisFromResult marks a failed run with the worker's error`() {
        val analysis = pendingAnalysis(3L)
        whenever(llmAnalysisRepository.findByInvoiceId(3L)).thenReturn(listOf(analysis))
        whenever(invoiceRepository.findById(3L)).thenReturn(Invoice().apply { id = 3L; invoiceNumber = InvoiceNumber("RE-2025-01-00003") })

        ops.updateLlmAnalysisFromResult(3L, mapOf("llmStatus" to "FAILED", "llmError" to "timeout"))

        assertEquals(LlmAnalysisStatus.FAILED, analysis.status)
        assertEquals("timeout", analysis.issues)
    }

    @Test
    fun `updateLlmAnalysisFromResult resolves a not-requested run to DISABLED`() {
        val analysis = pendingAnalysis(4L)
        whenever(llmAnalysisRepository.findByInvoiceId(4L)).thenReturn(listOf(analysis))

        ops.updateLlmAnalysisFromResult(4L, mapOf("llmStatus" to "NOT_REQUESTED"))

        assertEquals(LlmAnalysisStatus.DISABLED, analysis.status)
        verify(bus, never()).fireAsync(any())
    }

    @Test
    fun `updateLlmAnalysisFromResult ignores a blank llmStatus`() {
        val analysis = pendingAnalysis(5L)
        whenever(llmAnalysisRepository.findByInvoiceId(5L)).thenReturn(listOf(analysis))

        ops.updateLlmAnalysisFromResult(5L, emptyMap())

        assertEquals(LlmAnalysisStatus.PENDING, analysis.status)
        verify(llmAnalysisRepository, never()).save(any())
    }

    @Test
    fun `failPendingAnalysesForInvoice is a no-op without an invoiceId`() {
        ops.failPendingAnalysesForInvoice(null)

        verify(llmAnalysisRepository, never()).findByInvoiceId(any())
    }

    @Test
    fun `failPendingAnalysesForInvoice fails every still-pending analysis`() {
        val analysis = pendingAnalysis(6L)
        whenever(llmAnalysisRepository.findByInvoiceId(6L)).thenReturn(listOf(analysis))

        ops.failPendingAnalysesForInvoice(6L)

        assertEquals(LlmAnalysisStatus.FAILED, analysis.status)
        verify(llmAnalysisRepository).save(analysis)
    }
}
