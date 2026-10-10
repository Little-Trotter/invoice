package de.littletrotter.application.dto

import de.littletrotter.domain.invoice.InvoiceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DocumentGenerationFailureTest {

    private fun job(status: String, lastError: String? = null, refs: String? = null) =
        JobSummaryDto(id = 1L, status = status, createdAt = null, resultRefs = refs, kositReport = null, lastError = lastError)

    private val sent = InvoiceStatus.SENT.ordinal

    @Test
    fun `failed latest job of a sent invoice yields the last error`() {
        assertEquals("Typst crashed", documentGenerationFailureOf(sent, job("FAILED", "Typst crashed"))?.reason)
    }

    @Test
    fun `cancelled invoices count as issued`() {
        assertEquals("x", documentGenerationFailureOf(InvoiceStatus.CANCELLED.ordinal, job("FAILED", "x"))?.reason)
    }

    @Test
    fun `reason falls back to the worker error in the result refs`() {
        assertEquals("render error", documentGenerationFailureOf(sent, job("FAILED", refs = """{"error":"render error"}"""))?.reason)
    }

    @Test
    fun `no usable text still reports the failure with an empty reason`() {
        assertEquals("", documentGenerationFailureOf(sent, job("FAILED", "  ", refs = "not json"))?.reason)
        assertEquals("", documentGenerationFailureOf(sent, job("FAILED", refs = """{"error":5}"""))?.reason)
        assertEquals("", documentGenerationFailureOf(sent, job("FAILED"))?.reason)
    }

    @Test
    fun `reason is reduced to the first line and capped`() {
        assertEquals("first", documentGenerationFailureOf(sent, job("FAILED", "\n first \n at some.Stack.trace"))?.reason)
        val capped = documentGenerationFailureOf(sent, job("FAILED", "a".repeat(500)))!!.reason
        assertEquals(160, capped.length)
        assertEquals('…', capped.last())
        assertEquals(160, documentGenerationFailureOf(sent, job("FAILED", "b".repeat(160)))!!.reason.length)
    }

    @Test
    fun `nothing is reported without a failed job or an issued invoice`() {
        assertNull(documentGenerationFailureOf(sent, null))
        assertNull(documentGenerationFailureOf(sent, job("COMPLETED")))
        assertNull(documentGenerationFailureOf(sent, job("PENDING")))
        assertNull(documentGenerationFailureOf(InvoiceStatus.DRAFT.ordinal, job("FAILED", "x")))
        assertNull(documentGenerationFailureOf(99, job("FAILED", "x")))
    }
}
