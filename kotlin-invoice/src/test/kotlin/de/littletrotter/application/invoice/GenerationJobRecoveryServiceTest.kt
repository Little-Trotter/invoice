package de.littletrotter.application.invoice

import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.event.GenerationJobExhausted
import de.littletrotter.domain.event.GenerationJobFailed
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.forceStatusForTest
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.domain.shared.ValidationStatus
import jakarta.enterprise.event.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/** Unit tests for the extracted failure/retry/DLQ + stale-LLM-recovery responsibility. */
class GenerationJobRecoveryServiceTest {

    private val generationJobRepository: GenerationJobRepository = mock()
    private val invoiceRepository: InvoiceRepository = mock()
    private val bus: Event<DomainEvent> = mock()
    private val llmResultHandler: GenerationJobLlmResultHandler = mock()
    private val time = ClockTimeSource.fixedAt(Instant.parse("2026-06-15T10:00:00Z"))
    private val ops = GenerationJobRecoveryService(
        generationJobRepository,
        invoiceRepository,
        GenerationJobEventDispatcher(bus, null),
        llmResultHandler,
        time,
    )

    private fun invoice(status: InvoiceStatus = InvoiceStatus.SENT): Invoice = Invoice().apply {
        id = 700L
        invoiceNumber = InvoiceNumber("RE-2025-01-00700")
        forceStatusForTest(status)
        validationStatus = ValidationStatus.PENDING
    }

    @Test
    fun `markFailed transitions to FAILED, clears pending validation, fires GenerationJobFailed`() {
        val job = GenerationJob().apply { invoice = invoice() }
        whenever(generationJobRepository.save(job)).thenReturn(job)

        val result = ops.markFailed(job, message = "boom")

        assertEquals(de.littletrotter.domain.invoice.GenerationJobStatus.FAILED, result.status)
        assertTrue(result.resultRefs?.contains("boom") == true)
        verify(invoiceRepository).save(job.invoice!!)
        verify(bus).fireAsync(any<GenerationJobFailed>())
    }

    @Test
    fun `markFailed skips the event when the job has no invoice`() {
        val job = GenerationJob()
        whenever(generationJobRepository.save(job)).thenReturn(job)

        ops.markFailed(job)

        verify(bus, never()).fireAsync(any())
    }

    @Test
    fun `failExhaustedJob returns false without an id`() {
        assertFalse(ops.failExhaustedJob(GenerationJob(), 5))
    }

    @Test
    fun `failExhaustedJob returns false when the row is no longer PENDING`() {
        whenever(generationJobRepository.failExhausted(any(), any(), any())).thenReturn(false)

        assertFalse(ops.failExhaustedJob(GenerationJob().apply { id = 1L }, 5))
    }

    @Test
    fun `failExhaustedJob fires GenerationJobExhausted for an issued invoice`() {
        whenever(generationJobRepository.failExhausted(any(), any(), any())).thenReturn(true)
        val job = GenerationJob().apply { id = 1L; invoice = invoice() }

        assertTrue(ops.failExhaustedJob(job, 5))

        verify(llmResultHandler).failPendingAnalysesForInvoice(700L)
        verify(bus).fireAsync(any<GenerationJobExhausted>())
    }

    @Test
    fun `failExhaustedJob does not fire for a non-issued invoice`() {
        whenever(generationJobRepository.failExhausted(any(), any(), any())).thenReturn(true)
        val job = GenerationJob().apply { id = 1L; invoice = invoice(InvoiceStatus.DRAFT) }

        ops.failExhaustedJob(job, 5)

        verify(bus, never()).fireAsync(any())
    }

    @Test
    fun `recordLastError delegates to the repository`() {
        whenever(generationJobRepository.recordLastError(1L, "x")).thenReturn(true)

        assertTrue(ops.recordLastError(1L, "x"))
    }

    @Test
    fun `recordLastError swallows a repository failure`() {
        whenever(generationJobRepository.recordLastError(any(), any())).thenThrow(RuntimeException("db down"))

        assertFalse(ops.recordLastError(1L, "x"))
    }

    @Test
    fun `markResultProcessingFailed returns true without further writes when the job no longer resolves`() {
        whenever(generationJobRepository.failDlq(eq(1L), eq("x"), any())).thenReturn(true)
        whenever(generationJobRepository.findById(1L)).thenReturn(null)

        assertTrue(ops.markResultProcessingFailed(1L, "x"))
    }

    @Test
    fun `markResultProcessingFailed returns false when failDlq itself declines`() {
        whenever(generationJobRepository.failDlq(eq(1L), eq("x"), any())).thenReturn(false)

        assertFalse(ops.markResultProcessingFailed(1L, "x"))
    }

    @Test
    fun `markResultProcessingFailed swallows an unexpected repository failure`() {
        whenever(generationJobRepository.failDlq(any(), any(), any())).thenThrow(RuntimeException("db down"))

        assertFalse(ops.markResultProcessingFailed(1L, "x"))
    }

    @Test
    fun `resetStaleLlmPending resolves candidates whose llmStatus is still PENDING`() {
        val job = GenerationJob().apply {
            id = 1L
            invoice = Invoice().apply { id = 700L }
            resultRefs = """{"llmStatus":"PENDING"}"""
            finishedAt = java.time.OffsetDateTime.now().minusMinutes(30)
        }
        whenever(generationJobRepository.findCompletedBetween(any(), any())).thenReturn(listOf(job))
        whenever(generationJobRepository.findById(1L)).thenReturn(job)
        whenever(generationJobRepository.save(job)).thenReturn(job)

        val resolved = ops.resetStaleLlmPending()

        assertEquals(1, resolved)
        assertTrue(job.resultRefs?.contains("FAILED") == true)
        verify(llmResultHandler).updateLlmAnalysisFromResult(eq(700L), any())
    }

    @Test
    fun `resolveStaleLlmPendingForIds skips a job whose llmStatus is not PENDING`() {
        val job = GenerationJob().apply { id = 2L; resultRefs = """{"llmStatus":"COMPLETED"}""" }
        whenever(generationJobRepository.findById(2L)).thenReturn(job)

        val resolved = ops.resolveStaleLlmPendingForIds(listOf(2L))

        assertEquals(0, resolved)
        verify(generationJobRepository, never()).save(any())
    }

    @Test
    fun `resolveStaleLlmPendingForIds skips an id that no longer resolves`() {
        whenever(generationJobRepository.findById(3L)).thenReturn(null)

        assertEquals(0, ops.resolveStaleLlmPendingForIds(listOf(3L)))
    }

    @Test
    fun `resetStaleLlmPendingBatched chunks the candidate ids through the batch collaborator`() {
        val job1 = GenerationJob().apply { id = 10L; resultRefs = """{"llmStatus":"PENDING"}""" }
        val job2 = GenerationJob().apply { id = 11L; resultRefs = """{"llmStatus":"PENDING"}""" }
        whenever(generationJobRepository.findCompletedBetween(any(), any())).thenReturn(listOf(job1, job2))
        val batchTransaction: LlmPendingRecoveryBatchTransaction = mock()
        whenever(batchTransaction.resolveBatch(listOf(10L, 11L))).thenReturn(2)

        val resolved = ops.resetStaleLlmPendingBatched(batchTransaction, batchSize = 200)

        assertEquals(2, resolved)
        verify(batchTransaction).resolveBatch(listOf(10L, 11L))
    }
}
