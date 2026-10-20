package de.littletrotter.application.invoice

import de.littletrotter.application.shared.DomainEventDispatcher
import de.littletrotter.domain.invoice.recordLlmAnalysis
import de.littletrotter.fixture.aGenerationJob
import  de.littletrotter.fixture.anInvoice
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.event.InvoiceLlmAnalysisResolved
import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.fixture.anInvoice
import de.littletrotter.test.FIXED_TIME_SOURCE
import jakarta.enterprise.event.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Unit tests for the extracted LLM-callback result-handling responsibility. */
class GenerationJobLlmResultHandlerTest {

    private val generationJobRepository: GenerationJobRepository = mock()
    private val invoiceRepository: InvoiceRepository = mock()
    private val bus: Event<DomainEvent> = mock()
    private val ops = GenerationJobLlmResultHandler(
        generationJobRepository,
        invoiceRepository,
        DomainEventDispatcher(bus, null), time = FIXED_TIME_SOURCE,
    )

    /** A stored invoice carrying one queued analysis of [model]. */
    private fun pendingAnalysis(invoiceId: Long, model: String = "oMLX", number: String? = null): InvoiceLlmAnalysis {
        val invoice = if (number == null) anInvoice(id = invoiceId) else anInvoice(id = invoiceId, invoiceNumber = InvoiceNumber(number))
        whenever(invoiceRepository.findById(invoiceId)).thenReturn(invoice)
        return invoice.recordLlmAnalysis(model)
    }

    @Test
    fun `mergeLlmResult overlays new keys onto the existing resultRefs JSON`() {
        val job = aGenerationJob { resultRefs = """{"objectKey":"x"}""" }
        whenever(generationJobRepository.save(job)).thenReturn(job)

        val result = ops.mergeLlmResult(job, mapOf("llmStatus" to "COMPLETED"))

        assertEquals("COMPLETED", result.llmStatus)
        assertEquals(true, result.resultRefs?.contains("objectKey"))
        assertEquals(true, result.resultRefs?.contains("COMPLETED"))
    }

    @Test
    fun `updateLlmAnalysisFromResult is a no-op without an invoiceId`() {
        ops.updateLlmAnalysisFromResult(null, mapOf("llmStatus" to "COMPLETED"))

        verify(invoiceRepository, never()).findById(any())
    }

    @Test
    fun `updateLlmAnalysisFromResult is a no-op when no analyses exist`() {
        whenever(invoiceRepository.findById(1L)).thenReturn(anInvoice(id = 1L))

        ops.updateLlmAnalysisFromResult(1L, mapOf("llmStatus" to "COMPLETED"))

        verify(bus, never()).fireAsync(any())
    }

    @Test
    fun `updateLlmAnalysisFromResult resolves a completed run and fires the resolved event`() {
        val analysis = pendingAnalysis(2L, number = "RE-2025-01-00002")

        ops.updateLlmAnalysisFromResult(2L, mapOf("llmStatus" to "COMPLETED", "llmDurationMs" to "150"))

        assertEquals(LlmAnalysisStatus.COMPLETED, analysis.status)
        verify(bus).fireAsync(any<InvoiceLlmAnalysisResolved>())
    }

    @Test
    fun `updateLlmAnalysisFromResult marks a failed run with the worker's error`() {
        val analysis = pendingAnalysis(3L, number = "RE-2025-01-00003")

        ops.updateLlmAnalysisFromResult(3L, mapOf("llmStatus" to "FAILED", "llmError" to "timeout"))

        assertEquals(LlmAnalysisStatus.FAILED, analysis.status)
        assertEquals("timeout", analysis.issues)
    }

    @Test
    fun `updateLlmAnalysisFromResult resolves a not-requested run to DISABLED`() {
        val analysis = pendingAnalysis(4L)

        ops.updateLlmAnalysisFromResult(4L, mapOf("llmStatus" to "NOT_REQUESTED"))

        assertEquals(LlmAnalysisStatus.DISABLED, analysis.status)
        verify(bus, never()).fireAsync(any())
    }

    @Test
    fun `updateLlmAnalysisFromResult ignores a blank llmStatus`() {
        val analysis = pendingAnalysis(5L)

        ops.updateLlmAnalysisFromResult(5L, emptyMap())

        assertEquals(LlmAnalysisStatus.PENDING, analysis.status)
        verify(bus, never()).fireAsync(any())
    }

    @Test
    fun `failPendingAnalysesForInvoice is a no-op without an invoiceId`() {
        ops.failPendingAnalysesForInvoice(null)

        verify(invoiceRepository, never()).findById(any())
    }

    @Test
    fun `failPendingAnalysesForInvoice fails every still-pending analysis`() {
        val analysis = pendingAnalysis(6L)

        ops.failPendingAnalysesForInvoice(6L)

        assertEquals(LlmAnalysisStatus.FAILED, analysis.status)
    }
}
