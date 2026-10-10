package de.littletrotter.xpdf.infrastructure.messaging

import de.littletrotter.shared.documentjob.DocumentJobRequest
import de.littletrotter.shared.documentjob.QuoteDocumentJob
import de.littletrotter.xpdf.impl.QuoteFixtures
import de.littletrotter.xpdf.QuotePdfExport
import de.littletrotter.xpdf.QuotePdfResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class QuoteJobProcessorTest {

    private val pipeline: QuotePdfExport = mock()
    private val publisher: ResultPublisher = mock()
    private val job = DocumentJobRequest("q-1", QuoteDocumentJob.TYPE, "corr", resultSubject = QuoteDocumentJob.RESULT_SUBJECT)

    private fun processor() = QuoteJobProcessor(pipeline, publisher).apply {
        backoffMs = 1
        retryCount = 2
    }

    @Test
    fun `a rendered offer is reported COMPLETED with key checksum and report on the offer result subject`() {
        whenever(pipeline.export(any(), any())).thenReturn(QuotePdfResult("quotes/x.pdf", "abc", "{\"status\":\"VALID\"}"))

        processor().process(job, QuoteFixtures.payload())

        val refs = argumentCaptor<Map<String, String>>()
        verify(publisher).publishResult(eq("q-1"), eq("COMPLETED"), refs.capture(), any(), eq("corr"), eq(QuoteDocumentJob.RESULT_SUBJECT))
        assertEquals("quotes/x.pdf", refs.firstValue[QuoteDocumentJob.REF_OBJECT_KEY])
        assertEquals("abc", refs.firstValue[QuoteDocumentJob.REF_SHA256])
        assertEquals("{\"status\":\"VALID\"}", refs.firstValue[QuoteDocumentJob.REF_VALIDATION_REPORT])
    }

    @Test
    fun `a transient failure is retried and the later success is reported`() {
        whenever(pipeline.export(any(), any()))
            .thenThrow(IllegalStateException("typst busy"))
            .thenReturn(QuotePdfResult("quotes/x.pdf", "abc", "{}"))

        processor().process(job, QuoteFixtures.payload())

        verify(pipeline, times(2)).export(any(), any())
        verify(publisher).publishResult(eq("q-1"), eq("COMPLETED"), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `an exhausted retry budget is reported FAILED with the reason`() {
        doThrow(IllegalStateException("typst down")).whenever(pipeline).export(any(), any())

        processor().process(job, QuoteFixtures.payload())

        verify(pipeline, times(3)).export(any(), any())
        val refs = argumentCaptor<Map<String, String>>()
        verify(publisher).publishResult(eq("q-1"), eq("FAILED"), refs.capture(), any(), eq("corr"), eq(QuoteDocumentJob.RESULT_SUBJECT))
        assertTrue(refs.firstValue.getValue(QuoteDocumentJob.REF_ERROR).contains("typst down"))
    }

    @Test
    fun `a failure without a message reports the exception type`() {
        doThrow(IllegalStateException()).whenever(pipeline).export(any(), any())

        processor().process(job, QuoteFixtures.payload())

        val refs = argumentCaptor<Map<String, String>>()
        verify(publisher).publishResult(eq("q-1"), eq("FAILED"), refs.capture(), any(), anyOrNull(), anyOrNull())
        assertEquals("IllegalStateException", refs.firstValue[QuoteDocumentJob.REF_ERROR])
    }
}
