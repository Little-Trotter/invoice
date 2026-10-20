package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.fixture.edit
import java.time.OffsetDateTime
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
        val jobs = listOf(aGenerationJob { id = 1L })
        whenever(repository.findByInvoiceId(42L)).thenReturn(jobs)

        assertEquals(jobs, ops.findByInvoiceId(42L))
    }

    @Test
    fun `findByInvoiceIdWithTaxRates delegates to repository`() {
        val jobs = listOf(aGenerationJob { id = 2L })
        whenever(repository.findByInvoiceIdWithTaxRates(43L)).thenReturn(jobs)

        assertEquals(jobs, ops.findByInvoiceIdWithTaxRates(43L))
    }

    @Test
    fun `findPending delegates to repository`() {
        val jobs = listOf(aGenerationJob { id = 3L })
        whenever(repository.findPending()).thenReturn(jobs)

        assertEquals(jobs, ops.findPending())
    }

    @Test
    fun `findPendingWithInvoice delegates to repository`() {
        val jobs = listOf(aGenerationJob { id = 4L })
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
        val job = aGenerationJob { id = 5L }
        whenever(repository.findByIdWithInvoice(5L)).thenReturn(job)

        assertEquals(job, ops.findByIdWithInvoice(5L))
    }

    private val created = OffsetDateTime.parse("2026-05-01T10:15:30+02:00")
    private val finished = OffsetDateTime.parse("2026-05-01T10:16:00+02:00")

    private fun fullJob(): GenerationJob = aGenerationJob {
        id = 7L
        invoice = Invoice().edit {
            id = 11L
            invoiceNumber = InvoiceNumber("RE-2026-0042")
        }
        jobType = "PRINT_JOB"
        status = GenerationJobStatus.COMPLETED
        trigger = GenerationJobTrigger.SEND
        issued = true
        resultRefs = """{"objectKey":"k.pdf"}"""
        kositReport = "<report/>"
        llmStatus = "DONE"
        attempts = 3
        lastError = "boom"
        createdAt = created
        finishedAt = finished
    }

    @Test
    fun `toView copies every field and flattens the invoice id and number`() {
        val view = fullJob().toView()

        assertEquals(7L, view.id)
        assertEquals(11L, view.invoiceId)
        assertEquals("RE-2026-0042", view.invoiceNumber)
        assertEquals("PRINT_JOB", view.jobType)
        assertEquals(GenerationJobStatus.COMPLETED, view.status)
        assertEquals(GenerationJobTrigger.SEND, view.trigger)
        assertEquals(true, view.issued)
        assertEquals("""{"objectKey":"k.pdf"}""", view.resultRefs)
        assertEquals("<report/>", view.kositReport)
        assertEquals("DONE", view.llmStatus)
        assertEquals(3, view.attempts)
        assertEquals("boom", view.lastError)
        assertEquals(created, view.createdAt)
        assertEquals(finished, view.finishedAt)
    }

    @Test
    fun `toView of a job without invoice and optional data leaves those fields null`() {
        val job = aGenerationJob {
            id = 8L
            createdAt = created
        }

        val view = job.toView()

        assertEquals(8L, view.id)
        assertNull(view.invoiceId)
        assertNull(view.invoiceNumber)
        assertNull(view.resultRefs)
        assertNull(view.kositReport)
        assertNull(view.llmStatus)
        assertNull(view.lastError)
        assertNull(view.finishedAt)
        assertEquals(GenerationJobStatus.PENDING, view.status)
        assertEquals(GenerationJobTrigger.MANUAL, view.trigger)
        assertEquals(false, view.issued)
        assertEquals(0, view.attempts)
    }

    @Test
    fun `findJobViewsByInvoiceId maps every job of the invoice`() {
        whenever(repository.findByInvoiceId(11L)).thenReturn(listOf(fullJob(), aGenerationJob { id = 9L }))

        val views = ops.findJobViewsByInvoiceId(11L)

        assertEquals(listOf(7L, 9L), views.map { it.id })
    }

    @Test
    fun `findJobViewWithInvoice maps the loaded job`() {
        whenever(repository.findByIdWithInvoice(7L)).thenReturn(fullJob())

        assertEquals(fullJob().toView(), ops.findJobViewWithInvoice(7L))
    }

    @Test
    fun `findJobViewWithInvoice returns null for an unknown id`() {
        whenever(repository.findByIdWithInvoice(404L)).thenReturn(null)

        assertNull(ops.findJobViewWithInvoice(404L))
    }
}
