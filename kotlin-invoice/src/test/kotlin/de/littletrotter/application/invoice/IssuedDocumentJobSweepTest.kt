package de.littletrotter.application.invoice

import de.littletrotter.domain.exception.InvoiceSendAlreadyInProgressException
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.repository.GenerationJobRepository
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class IssuedDocumentJobSweepTest {

    private val repository: GenerationJobRepository = mock()
    private val creation: GenerationJobCreationService = mock()
    private val sweep = IssuedDocumentJobSweep(repository, creation)

    @Test
    fun `requests one print job per credit note without a job`() {
        whenever(repository.findCreditNoteIdsWithoutJob(any())).thenReturn(listOf(11L, 12L))

        assertEquals(2, sweep.requestMissingCreditNoteJobs())

        verify(creation).createJob(11L, GenerationJobCreationService.PRINT_JOB_TYPE, DocumentJobLane.BULK)
        verify(creation).createJob(12L, GenerationJobCreationService.PRINT_JOB_TYPE, DocumentJobLane.BULK)
    }

    @Test
    fun `does nothing when every credit note already has a job`() {
        whenever(repository.findCreditNoteIdsWithoutJob(any())).thenReturn(emptyList())

        assertEquals(0, sweep.requestMissingCreditNoteJobs())

        verify(creation, never()).createJob(any<Long>(), any(), any(), org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.anyOrNull(), any(), any())
    }

    @Test
    fun `a refused or unstorable job does not stop the remaining credit notes`() {
        whenever(repository.findCreditNoteIdsWithoutJob(any())).thenReturn(listOf(1L, 2L, 3L))
        whenever(creation.createJob(1L, GenerationJobCreationService.PRINT_JOB_TYPE, DocumentJobLane.BULK)).thenThrow(InvoiceSendAlreadyInProgressException("RE-1"))
        whenever(creation.createJob(2L, GenerationJobCreationService.PRINT_JOB_TYPE, DocumentJobLane.BULK)).thenThrow(PersistenceException("db"))

        assertEquals(1, sweep.requestMissingCreditNoteJobs())

        verify(creation).createJob(3L, GenerationJobCreationService.PRINT_JOB_TYPE, DocumentJobLane.BULK)
    }
}
