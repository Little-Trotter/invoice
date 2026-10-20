package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.fixture.aGenerationJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SchedulePrintJobServiceTest {

    private val creation: GenerationJobCreationService = mock()
    private val publisher: DocumentJobPublisher = mock()
    private val service = SchedulePrintJobService(creation, publisher)

    private fun stubJob(job: GenerationJob) {
        whenever(creation.createJob(eq(7L), eq("PRINT_JOB"), eq(DocumentJobLane.INTERACTIVE), anyOrNull(), any(), any()))
            .thenReturn(job)
    }

    @Test
    fun `creates an interactive print job, publishes it and returns its id`() {
        val job = aGenerationJob { id = 42L }
        stubJob(job)

        val id = service.schedulePrintJob(7L, true)

        assertEquals(42L, id)
        verify(creation).createJob(
            7L, "PRINT_JOB", DocumentJobLane.INTERACTIVE,
            llmOptimize = true,
        )
        verify(publisher).publish(job, skipValidation = false)
    }

    @Test
    fun `a job without id yields null`() {
        val job = GenerationJob()
        stubJob(job)

        assertNull(service.schedulePrintJob(7L, false))

        verify(creation).createJob(
            7L, "PRINT_JOB", DocumentJobLane.INTERACTIVE,
            llmOptimize = false,
        )
        verify(publisher).publish(job, skipValidation = false)
    }
}
