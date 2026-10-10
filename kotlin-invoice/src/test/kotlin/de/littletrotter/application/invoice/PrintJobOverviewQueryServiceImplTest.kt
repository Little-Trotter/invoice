package de.littletrotter.application.invoice

import de.littletrotter.application.dto.GenerationJobFilter
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.shared.GermanTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

class PrintJobOverviewQueryServiceImplTest {

    private fun jobWithInvoice(
        jobId: Long = 1L,
        invoiceId: Long = 10L,
        invoiceNumber: String = "PQ-2026-08-00001",
        recipientName: String? = "Acme Corp",
        status: GenerationJobStatus = GenerationJobStatus.COMPLETED,
        attempts: Int = 0,
        lastError: String? = null,
        llmStatus: String? = null,
    ): GenerationJob {
        val invoice = Invoice().apply {
            id = invoiceId
            this.invoiceNumber = InvoiceNumber(invoiceNumber)
            recipient = recipientName?.let { Recipient().apply { name = it } }
        }
        return GenerationJob().apply {
            id = jobId
            this.invoice = invoice
            jobType = "PDF_EXPORT"
            engine = DocumentEngine.MUSTANG
            this.status = status
            createdAt = OffsetDateTime.parse("2026-08-20T10:00:00Z")
            finishedAt = OffsetDateTime.parse("2026-08-20T10:05:00Z")
            this.attempts = attempts
            this.lastError = lastError
            this.llmStatus = llmStatus
        }
    }

    @Test
    fun `findPaged maps every field of the fetch-joined entity onto the flat row, including attempts, lastError and llmStatus`() {
        val repo = mock<GenerationJobRepository>()
        val job = jobWithInvoice(attempts = 2, lastError = "worker timeout", llmStatus = "FAILED")
        whenever(
            repo.findPaged(
                statuses = any(), recipientId = eq(null), createdFrom = eq(null), createdTo = eq(null),
                offset = eq(0), limit = eq(60),
            )
        ).thenReturn(listOf(job))

        val sut = PrintJobOverviewQueryServiceImpl(repo)
        val rows = sut.findPaged()

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(1L, row.jobId)
        assertEquals(10L, row.invoiceId)
        assertEquals("PQ-2026-08-00001", row.invoiceNumber)
        assertEquals("Acme Corp", row.recipientName)
        assertEquals(GenerationJobStatus.COMPLETED, row.status)
        assertEquals(OffsetDateTime.parse("2026-08-20T10:00:00Z"), row.createdAt)
        assertEquals(OffsetDateTime.parse("2026-08-20T10:05:00Z"), row.finishedAt)
        assertEquals(2, row.attempts)
        assertEquals("worker timeout", row.lastError)
        assertEquals("FAILED", row.llmStatus)
    }

    @Test
    fun `findPaged maps a missing recipient to a null recipientName instead of throwing`() {
        val repo = mock<GenerationJobRepository>()
        val job = jobWithInvoice(recipientName = null)
        whenever(repo.findPaged(any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), any())).thenReturn(listOf(job))

        val sut = PrintJobOverviewQueryServiceImpl(repo)
        val row = sut.findPaged().single()

        assertNull(row.recipientName)
    }

    @Test
    fun `findPaged unpacks every GenerationJobFilter field into the repository's plain parameters`() {
        val repo = mock<GenerationJobRepository>()
        whenever(repo.findPaged(any(), any(), any(), any(), any(), any())).thenReturn(emptyList())

        val from = OffsetDateTime.parse("2026-08-01T00:00:00Z")
        val to = OffsetDateTime.parse("2026-08-31T23:59:59Z")
        val filter = GenerationJobFilter(
            statuses = setOf(GenerationJobStatus.FAILED, GenerationJobStatus.COMPLETED),
            recipientId = 42L,
            createdFrom = from,
            createdTo = to,
        )

        val sut = PrintJobOverviewQueryServiceImpl(repo)
        sut.findPaged(filter, offset = 20, limit = 10)

        verify(repo).findPaged(
            statuses = setOf(GenerationJobStatus.FAILED, GenerationJobStatus.COMPLETED),
            recipientId = 42L,
            createdFrom = from,
            createdTo = to,
            offset = 20,
            limit = 10,
        )
    }

    @Test
    fun `count unpacks the filter and returns the repository's total`() {
        val repo = mock<GenerationJobRepository>()
        val filter = GenerationJobFilter(statuses = setOf(GenerationJobStatus.PENDING), recipientId = 5L)
        whenever(
            repo.countFiltered(
                statuses = setOf(GenerationJobStatus.PENDING), recipientId = 5L,
                createdFrom = null, createdTo = null,
            )
        ).thenReturn(3L)

        val sut = PrintJobOverviewQueryServiceImpl(repo)
        val total = sut.count(filter)

        assertEquals(3L, total)
    }

    @Test
    fun `findPaged and count default to an empty filter and the standard page window`() {
        val repo = mock<GenerationJobRepository>()
        whenever(repo.findPaged(any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), any())).thenReturn(emptyList())
        whenever(repo.countFiltered(any(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(0L)

        val sut = PrintJobOverviewQueryServiceImpl(repo)
        assertTrue(sut.findPaged().isEmpty())
        assertEquals(0L, sut.count())

        verify(repo).findPaged(
            statuses = emptySet(), recipientId = null, createdFrom = null, createdTo = null,
            offset = 0, limit = 60,
        )
        verify(repo).countFiltered(
            statuses = emptySet(), recipientId = null, createdFrom = null, createdTo = null,
        )
    }

    @Test
    fun `boundary - toRow maps a job without result refs when called directly (not only through findPaged's map reference)`() {
        val row = PrintJobOverviewQueryServiceImpl.toRow(jobWithInvoice())

        assertNull(row.pdfObjectKey)
        assertNull(row.xmlObjectKey)
    }

    /**
     * [PrintJobOverviewQueryService.findDaySummaries]'s own default (`filter =
     * GenerationJobFilter()`) was never exercised: every existing caller (the IT suite, the
     * production `PrintJobListView`) always passes an explicit filter. Calling through a
     * reference statically typed as the interface — not the impl — is what makes the default
     * value resolve at all.
     */
    @Test
    fun `findDaySummaries defaults to an empty filter`() {
        val repo = mock<GenerationJobRepository>()
        whenever(repo.findDaySummaries(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenReturn(emptyList())

        val sut: PrintJobOverviewQueryService = PrintJobOverviewQueryServiceImpl(repo)
        assertTrue(sut.findDaySummaries().isEmpty())

        verify(repo).findDaySummaries(statuses = emptySet(), recipientId = null, createdFrom = null, createdTo = null, zone = GermanTime.ZONE)
    }
}
