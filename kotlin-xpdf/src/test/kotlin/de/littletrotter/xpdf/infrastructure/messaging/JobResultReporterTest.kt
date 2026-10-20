package de.littletrotter.xpdf.infrastructure.messaging

import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.documentjob.DocumentJobRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/**
 * Dedicated unit coverage for [JobResultReporter], split out of `JobConsumer`. The
 * composed pipeline stays covered end-to-end by [JobConsumerTest]; this file tests the exact wire
 * shape of every result-publish call in isolation, constructed directly.
 */
class JobResultReporterTest {

    @AfterEach
    fun clearSystemProperties() {
        System.clearProperty("s3.endpoint")
        System.clearProperty("s3.bucket")
    }

    private fun job(correlationId: String? = "corr-1") = DocumentJobRequest(id = "job-1", type = "GENERATE", correlationId = correlationId)

    @Test
    fun `publishCompleted builds resultRefs from the export result and publishes COMPLETED (happy path)`() {
        System.setProperty("s3.endpoint", "http://rustfs-test:9000")
        System.setProperty("s3.bucket", "documents-test")
        val resultPublisher: ResultPublisher = mock()
        val reporter = JobResultReporter(resultPublisher)
        val result = ExportResult(
            objectKey = "invoice/job-1.pdf",
            zugferdXmlKey = "invoice/job-1.xml",
            kositSuccess = true,
            kositReport = "kosit-ok",
            sha256 = "abc123",
            zugferdXmlSha256 = "xml-sha",
        )

        reporter.publishCompleted(job(), result)

        val refsCaptor = argumentCaptor<Map<String, String>>()
        verify(resultPublisher).publishResult(eq("job-1"), eq("COMPLETED"), refsCaptor.capture(), eq("kosit-ok"), eq("corr-1"), anyOrNull())
        assertEquals("invoice/job-1.pdf", refsCaptor.firstValue["objectKey"])
        assertEquals("http://rustfs-test:9000/documents-test/invoice/job-1.pdf", refsCaptor.firstValue["downloadUrl"])
        assertEquals("abc123", refsCaptor.firstValue["sha256"])
        assertEquals("xml-sha", refsCaptor.firstValue["zugferdXmlSha256"])
    }

    @Test
    fun `every outcome is published on the job's own result subject`() {
        val resultPublisher: ResultPublisher = mock()
        val reporter = JobResultReporter(resultPublisher)
        val quoteJob = DocumentJobRequest(id = "job-q", type = "QUOTE_PDF", correlationId = "corr-q", resultSubject = "quote-job-results")

        reporter.publishCompleted(quoteJob, ExportResult(objectKey = "quote/job-q.pdf", zugferdXmlKey = "quote/job-q.xml", kositSuccess = true, kositReport = "", sha256 = "s"))
        reporter.publishRetryExhaustedFailure(quoteJob, IllegalStateException("boom"), false)

        verify(resultPublisher).publishResult(eq("job-q"), eq("COMPLETED"), any(), any(), eq("corr-q"), eq("quote-job-results"))
        verify(resultPublisher).publishResult(eq("job-q"), eq("FAILED"), any(), eq(""), eq("corr-q"), eq("quote-job-results"))
    }

    @Test
    fun `publishCompleted omits every blank-or-null optional field from resultRefs`() {
        val resultPublisher: ResultPublisher = mock()
        val reporter = JobResultReporter(resultPublisher)
        val result = ExportResult(
            objectKey = "invoice/job-1.pdf",
            zugferdXmlKey = "invoice/job-1.xml",
            kositSuccess = true,
            kositReport = "",
            sha256 = "",
        )

        reporter.publishCompleted(job(), result)

        val refsCaptor = argumentCaptor<Map<String, String>>()
        verify(resultPublisher).publishResult(any(), any(), refsCaptor.capture(), any(), any(), anyOrNull())
        assertFalse(refsCaptor.firstValue.containsKey("sha256"))
        assertFalse(refsCaptor.firstValue.containsKey("zugferdXmlSha256"))
        assertFalse(refsCaptor.firstValue.containsKey("archiveObjectKey"))
        assertFalse(refsCaptor.firstValue.containsKey("llmError"))
    }

    @Test
    fun `llmStatus is NOT_REQUESTED on a FAILED result when llmOptimize was off (boundary)`() {
        val resultPublisher: ResultPublisher = mock()
        val reporter = JobResultReporter(resultPublisher)

        reporter.publishUnknownEngine(job(), "BOGUS", llmOptimize = false)

        val refsCaptor = argumentCaptor<Map<String, String>>()
        verify(resultPublisher).publishResult(any(), eq("FAILED"), refsCaptor.capture(), any(), any(), anyOrNull())
        assertEquals("NOT_REQUESTED", refsCaptor.firstValue["llmStatus"])
    }

    @Test
    fun `llmStatus is FAILED on a FAILED result when llmOptimize was on (boundary)`() {
        val resultPublisher: ResultPublisher = mock()
        val reporter = JobResultReporter(resultPublisher)

        reporter.publishUnknownEngine(job(), "BOGUS", llmOptimize = true)

        val refsCaptor = argumentCaptor<Map<String, String>>()
        verify(resultPublisher).publishResult(any(), eq("FAILED"), refsCaptor.capture(), any(), any(), anyOrNull())
        assertEquals("FAILED", refsCaptor.firstValue["llmStatus"])
    }

    @Test
    fun `publishUnknownEngine names the offending engine value in the error message`() {
        val resultPublisher: ResultPublisher = mock()
        val reporter = JobResultReporter(resultPublisher)

        reporter.publishUnknownEngine(job(), "TOTALLY_UNKNOWN", llmOptimize = false)

        val refsCaptor = argumentCaptor<Map<String, String>>()
        verify(resultPublisher).publishResult(eq("job-1"), eq("FAILED"), refsCaptor.capture(), eq(""), eq("corr-1"), anyOrNull())
        assertTrue(refsCaptor.firstValue["error"]?.contains("TOTALLY_UNKNOWN") == true)
    }

    @Test
    fun `publishRetryExhaustedFailure reports the exception message as the error`() {
        val resultPublisher: ResultPublisher = mock()
        val reporter = JobResultReporter(resultPublisher)

        reporter.publishRetryExhaustedFailure(job(), RuntimeException("disk quota exceeded"), llmOptimize = false)

        val refsCaptor = argumentCaptor<Map<String, String>>()
        verify(resultPublisher).publishResult(any(), eq("FAILED"), refsCaptor.capture(), any(), any(), anyOrNull())
        assertEquals("disk quota exceeded", refsCaptor.firstValue["error"])
    }

    @Test
    fun `publishRetryExhaustedFailure reports an empty error string for a null exception message (boundary)`() {
        val resultPublisher: ResultPublisher = mock()
        val reporter = JobResultReporter(resultPublisher)

        reporter.publishRetryExhaustedFailure(job(), RuntimeException(null as String?), llmOptimize = false)

        val refsCaptor = argumentCaptor<Map<String, String>>()
        verify(resultPublisher).publishResult(any(), eq("FAILED"), refsCaptor.capture(), any(), any(), anyOrNull())
        assertEquals("", refsCaptor.firstValue["error"])
    }

    @Test
    fun `a null correlationId is passed through unchanged to publishResult (boundary)`() {
        val resultPublisher: ResultPublisher = mock()
        val reporter = JobResultReporter(resultPublisher)

        reporter.publishUnknownEngine(job(correlationId = null), "X", llmOptimize = false)

        verify(resultPublisher).publishResult(any(), any(), any(), any(), eq(null), anyOrNull())
    }
}
