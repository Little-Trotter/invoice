package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceDocumentRepository
import de.littletrotter.shared.GenerationJobResultRefs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

class IssuedDocumentLocatorTest {

    private val jobs: GenerationJobRepository = mock()
    private val documents: InvoiceDocumentRepository = mock()
    private val locator = IssuedDocumentLocator(jobs, documents)

    private fun job(
        id: Long,
        pdf: String?,
        xml: String? = null,
        minute: Long = 0,
        status: GenerationJobStatus = GenerationJobStatus.COMPLETED,
        issued: Boolean = true,
    ) = GenerationJob().apply {
        this.id = id
        this.status = status
        this.issued = issued
        finishedAt = OffsetDateTime.parse("2026-10-01T10:00:00Z").plusMinutes(minute)
        resultRefs = GenerationJobResultRefs.encode(GenerationJobResultRefs(objectKey = pdf, zugferdObjectKey = xml))
    }

    @Test
    fun `the latest completed issued job names the keys`() {
        whenever(jobs.findByInvoiceId(1L)).thenReturn(
            listOf(job(1, "a.pdf", "a.xml", 0), job(2, "b.pdf", "b.xml", 5), job(3, "c.pdf", minute = 9, issued = false)),
        )
        assertEquals(setOf("b.pdf", "b.xml"), locator.latestIssuedJobKeys(1L))
    }

    @Test
    fun `no issued job or undecodable refs mean no keys and no document`() {
        whenever(jobs.findByInvoiceId(1L)).thenReturn(listOf(job(1, "a.pdf", status = GenerationJobStatus.PENDING)))
        assertEquals(emptySet<String>(), locator.latestIssuedJobKeys(1L))
        assertNull(locator.currentPdf(1L))
        whenever(jobs.findByInvoiceId(2L)).thenReturn(listOf(GenerationJob().apply { id = 5; status = GenerationJobStatus.COMPLETED; issued = true }))
        assertEquals(emptySet<String>(), locator.latestIssuedJobKeys(2L))
        assertNull(locator.currentPdf(2L))
    }

    @Test
    fun `blank keys are dropped and a job without a pdf key has no current pdf`() {
        whenever(jobs.findByInvoiceId(1L)).thenReturn(listOf(job(1, " ", "x.xml")))
        assertEquals(setOf("x.xml"), locator.latestIssuedJobKeys(1L))
        assertNull(locator.currentPdf(1L))
    }

    @Test
    fun `the current pdf is the row of the latest job, whatever its status`() {
        val row = InvoiceDocument().apply { objectKey = "b.pdf" }
        whenever(jobs.findByInvoiceId(1L)).thenReturn(listOf(job(1, "a.pdf"), job(2, "b.pdf", minute = 3)))
        whenever(documents.findByObjectKey("b.pdf")).thenReturn(row)

        val current = locator.currentPdf(1L)!!

        assertSame(row, current.document)
        assertEquals(2L, current.jobId)
    }

    @Test
    fun `an untracked pdf has no current row`() {
        whenever(jobs.findByInvoiceId(1L)).thenReturn(listOf(job(1, "a.pdf")))
        assertNull(locator.currentPdf(1L))
    }
}
