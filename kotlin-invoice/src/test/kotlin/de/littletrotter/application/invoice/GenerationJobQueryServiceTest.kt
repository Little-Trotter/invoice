package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.repository.GenerationJobRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Unit tests for the extracted read-query responsibility. */
class GenerationJobQueryServiceTest {

    private val repository: GenerationJobRepository = mock()
    private val ops = GenerationJobQueryService(repository)

    @Test
    fun `findByInvoiceId delegates to repository`() {
        val jobs = listOf(GenerationJob().apply { id = 1L })
        whenever(repository.findByInvoiceId(42L)).thenReturn(jobs)

        assertEquals(jobs, ops.findByInvoiceId(42L))
    }

    @Test
    fun `findByInvoiceIdWithTaxRates delegates to repository`() {
        val jobs = listOf(GenerationJob().apply { id = 2L })
        whenever(repository.findByInvoiceIdWithTaxRates(43L)).thenReturn(jobs)

        assertEquals(jobs, ops.findByInvoiceIdWithTaxRates(43L))
    }

    @Test
    fun `findPending delegates to repository`() {
        val jobs = listOf(GenerationJob().apply { id = 3L })
        whenever(repository.findPending()).thenReturn(jobs)

        assertEquals(jobs, ops.findPending())
    }

    @Test
    fun `findPendingWithInvoice delegates to repository`() {
        val jobs = listOf(GenerationJob().apply { id = 4L })
        whenever(repository.findPendingWithInvoice()).thenReturn(jobs)

        assertEquals(jobs, ops.findPendingWithInvoice())
    }

    @Test
    fun `findById returns null when not found`() {
        whenever(repository.findById(99L)).thenReturn(null)

        assertNull(ops.findById(99L))
    }

    @Test
    fun `findByIdWithInvoice returns the loaded job`() {
        val job = GenerationJob().apply { id = 5L }
        whenever(repository.findByIdWithInvoice(5L)).thenReturn(job)

        assertEquals(job, ops.findByIdWithInvoice(5L))
    }
}
