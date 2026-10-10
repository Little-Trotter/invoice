package de.littletrotter.application.invoice

import de.littletrotter.domain.exception.InvoiceSendAlreadyInProgressException
import de.littletrotter.domain.invoice.DocumentJobLane
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CreditNoteIssuedObserverTest {

    @Test
    fun `issued credit note gets a print job`() {
        val jobs = mock<GenerationJobCreationService>()

        CreditNoteIssuedObserver(jobs).onCreditNoteIssued(CreditNoteIssued(77L))

        verify(jobs).createJob(77L, GenerationJobCreationService.PRINT_JOB_TYPE, DocumentJobLane.INTERACTIVE)
    }

    @Test
    fun `refused job only logs`() {
        val jobs = mock<GenerationJobCreationService>()
        whenever(jobs.createJob(77L, GenerationJobCreationService.PRINT_JOB_TYPE, DocumentJobLane.INTERACTIVE))
            .thenThrow(InvoiceSendAlreadyInProgressException("RE-1"))

        assertDoesNotThrow { CreditNoteIssuedObserver(jobs).onCreditNoteIssued(CreditNoteIssued(77L)) }
    }

    @Test
    fun `persistence failure only logs`() {
        val jobs = mock<GenerationJobCreationService>()
        whenever(jobs.createJob(77L, GenerationJobCreationService.PRINT_JOB_TYPE, DocumentJobLane.INTERACTIVE))
            .thenThrow(PersistenceException("db down"))

        assertDoesNotThrow { CreditNoteIssuedObserver(jobs).onCreditNoteIssued(CreditNoteIssued(77L)) }
    }
}
