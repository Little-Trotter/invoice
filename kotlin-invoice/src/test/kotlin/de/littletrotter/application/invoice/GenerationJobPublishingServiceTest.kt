package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.shared.time.ClockTimeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/** Unit tests for the extracted publish/delegate state-transition responsibility. */
class GenerationJobPublishingServiceTest {

    private val repository: GenerationJobRepository = mock()
    private val ops = GenerationJobPublishingService(repository, ClockTimeSource.fixedAt(Instant.parse("2026-06-15T10:00:00Z")))

    @Test
    fun `markDelegated transitions and saves`() {
        val job = GenerationJob().apply { id = 1L; status = GenerationJobStatus.PENDING }
        whenever(repository.save(job)).thenReturn(job)

        val result = ops.markDelegated(job)

        assertEquals(GenerationJobStatus.DELEGATED, result.status)
        assertNotNull(job.delegatedAt)
        verify(repository).save(job)
    }

    @Test
    fun `tryMarkDelegated returns false without an id`() {
        assertFalse(ops.tryMarkDelegated(GenerationJob()))
        verify(repository, never()).tryMarkDelegated(org.mockito.kotlin.any())
    }

    @Test
    fun `tryMarkDelegated delegates to repository when id is present`() {
        whenever(repository.tryMarkDelegated(7L)).thenReturn(true)

        assertTrue(ops.tryMarkDelegated(GenerationJob().apply { id = 7L }))
    }

    @Test
    fun `revertToPending returns false without an id`() {
        assertFalse(ops.revertToPending(GenerationJob()))
    }

    @Test
    fun `revertToPending delegates to repository when id is present`() {
        whenever(repository.revertToPending(8L)).thenReturn(true)

        assertTrue(ops.revertToPending(GenerationJob().apply { id = 8L }))
    }

    @Test
    fun `recordDelegatedStreamSeq returns false without an id`() {
        assertFalse(ops.recordDelegatedStreamSeq(GenerationJob(), 99L))
    }

    @Test
    fun `recordDelegatedStreamSeq delegates to repository when id is present`() {
        whenever(repository.recordDelegatedStreamSeq(9L, 100L)).thenReturn(true)

        assertTrue(ops.recordDelegatedStreamSeq(GenerationJob().apply { id = 9L }, 100L))
    }

    @Test
    fun `reopenExhaustedJobsWithoutDocument reopens every exhausted job`() {
        val exhausted = listOf(GenerationJob().apply { id = 10L }, GenerationJob().apply { id = 11L }, GenerationJob().apply { id = null })
        whenever(repository.findExhaustedJobsWithoutDocument()).thenReturn(exhausted)
        whenever(repository.reopenExhausted(10L)).thenReturn(true)
        whenever(repository.reopenExhausted(11L)).thenReturn(false)

        val reopened = ops.reopenExhaustedJobsWithoutDocument()

        assertEquals(1, reopened)
    }

    @Test
    fun `markStarted sets startedAt and transitions to RUNNING`() {
        val job = GenerationJob().apply { id = 12L; status = GenerationJobStatus.DELEGATED }
        whenever(repository.save(job)).thenReturn(job)

        val result = ops.markStarted(job)

        assertEquals(GenerationJobStatus.RUNNING, result.status)
        assertNotNull(job.startedAt)
    }
}
