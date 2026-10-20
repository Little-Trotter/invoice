package de.littletrotter.xpdf.pdfcheck

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.VeraPdfResult
import de.littletrotter.shared.documentjob.PdfCheckRequest
import de.littletrotter.xpdf.impl.VeraPdfRestClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.InputStream

class PdfCheckProcessorTest {

    private val request = PdfCheckRequest(checkId = "verapdf-7", jobId = "7", objectKey = "inv/7.pdf", sha256 = "ab", correlationId = "c-7")
    private val pdf = "%PDF".toByteArray()
    private val registry = SimpleMeterRegistry()

    private val store = object : DocumentStore {
        override fun put(objectKey: String, data: ByteArray, contentType: String) = Unit
        override fun get(objectKey: String): ByteArray = if (objectKey == "inv/7.pdf") pdf else error("missing $objectKey")
        override fun exists(objectKey: String) = objectKey == "inv/7.pdf"
        override fun enableImmutability(objectKey: String) = Unit
        override fun delete(objectKey: String) = Unit
    }

    private fun verdict(outcome: KositOutcome, findings: List<ValidationMessage> = emptyList(), engineError: String? = null) =
        VeraPdfResult(outcome, "PDF/A-3B", "veraPDF 1.30.2", findings.size, findings.size, findings, engineError)

    private fun processor(client: VeraPdfRestClient) = PdfCheckProcessor(store, client, registry)

    @Test
    fun `the stored document is streamed to the service with the digest of the request`() {
        val client: VeraPdfRestClient = mock()
        whenever(client.validate(any<InputStream>(), anyOrNull())).thenReturn(verdict(KositOutcome.VALID))
        val streamed = argumentCaptor<InputStream>()
        val digest = argumentCaptor<String>()

        val result = processor(client).check(request)

        verify(client).validate(streamed.capture(), digest.capture())
        assertEquals("ab", digest.firstValue)
        assertEquals("VALID", result.outcome)
        assertEquals("verapdf-7", result.checkId)
        assertEquals("7", result.jobId)
        assertEquals("c-7", result.correlationId)
        assertNull(result.engineError)
    }

    @Test
    fun `findings are carried and capped`() {
        val many = (1..PdfCheckProcessor.MAX_FINDINGS + 5).map { ValidationMessage("ERROR", "rule $it", ruleId = "6.$it") }
        val client: VeraPdfRestClient = mock()
        whenever(client.validate(any<InputStream>(), anyOrNull())).thenReturn(verdict(KositOutcome.INVALID, many))

        val result = processor(client).check(request)

        assertEquals("INVALID", result.outcome)
        assertEquals(PdfCheckProcessor.MAX_FINDINGS, result.findings.size)
        assertEquals(many.size, result.failedRuleCount)
    }

    @Test
    fun `an engine error keeps its reason`() {
        val client: VeraPdfRestClient = mock()
        whenever(client.validate(any<InputStream>(), anyOrNull())).thenReturn(verdict(KositOutcome.ERROR, engineError = "timeout"))

        val result = processor(client).check(request)

        assertEquals("ERROR", result.outcome)
        assertEquals("timeout", result.engineError)
    }

    @Test
    fun `a missing object is thrown to the caller, not turned into a verdict`() {
        val client: VeraPdfRestClient = mock()

        assertThrows(IllegalStateException::class.java) { processor(client).check(request.copy(objectKey = "inv/gone.pdf")) }
    }

    @Test
    fun `each check is timed by outcome`() {
        val client: VeraPdfRestClient = mock()
        whenever(client.validate(any<InputStream>(), anyOrNull())).thenReturn(verdict(KositOutcome.VALID))

        processor(client).check(request)

        assertEquals(1L, registry.find(PdfCheckProcessor.METER).tag("outcome", "valid").timer()?.count())
        assertTrue(registry.find(PdfCheckProcessor.METER).tag("outcome", "error").timer() == null)
    }
}
