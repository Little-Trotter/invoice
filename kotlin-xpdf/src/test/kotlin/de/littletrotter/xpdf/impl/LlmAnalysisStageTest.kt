package de.littletrotter.xpdf.impl

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.LlmModelResult
import de.littletrotter.shared.LlmValidationPort
import de.littletrotter.xpdf.infrastructure.messaging.ResultPublisher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for the deferred oMLX stage: the SECOND, later result callback
 * it publishes, the single-thread executor's serialization guarantee, and that a scheduling
 * failure never propagates (the print job itself already completed via the first callback by the
 * time [LlmAnalysisStage.schedule] is ever called). Per-call timeout enforcement itself is
 * [de.littletrotter.xpdf.MlxClientTest]'s own concern — [LlmAnalysisStage] calls
 * [LlmValidationPort.analyze] exactly the same way [PdfExportServiceImpl] used to, just from a
 * different thread; the test below only confirms a timed-out/errored [LlmModelResult] is reported
 * as "LLM_FAILED", not silently dropped or retried.
 */
class LlmAnalysisStageTest {

    private fun invoice(number: String = "INV-1") = InvoiceDto(invoiceNumber = InvoiceNumberDto(number))

    /** Runs [block] with a same-thread executor so every test below is fully synchronous and
     *  deterministic — only the dedicated serialization test needs the real executor. */
    private fun sameThreadStage(
        mlxClient: LlmValidationPort?,
        documentStore: DocumentStore = mock(),
        resultPublisher: ResultPublisher = mock(),
    ): LlmAnalysisStage =
        LlmAnalysisStage(mlxClient, documentStore, resultPublisher).apply {
            executor = Executor { it.run() }
        }

    @Test
    fun `isAvailable delegates to the mlx client, false when no client is wired`() {
        val client: LlmValidationPort = mock()
        whenever(client.isAvailable()).thenReturn(true)
        assertTrue(LlmAnalysisStage(client, mock(), mock()).isAvailable())
        assertFalse(LlmAnalysisStage(null, mock(), mock()).isAvailable())
    }

    @Test
    fun `boundary - isAvailable is false when a client is wired but reports itself unavailable`() {
        val client: LlmValidationPort = mock()
        whenever(client.isAvailable()).thenReturn(false)
        assertFalse(LlmAnalysisStage(client, mock(), mock()).isAvailable())
    }

    @Test
    fun `a successful analysis stores the report and publishes LLM_COMPLETED with the merged resultRefs`() {
        val client: LlmValidationPort = mock()
        whenever(client.analyze(any(), any(), any(), any())).thenReturn(
            LlmModelResult(model = "qwen", markdownReport = "# Bericht", success = true, durationMs = 4200),
        )
        val store: DocumentStore = mock()
        val resultPublisher: ResultPublisher = mock()
        val stage = sameThreadStage(client, store, resultPublisher)

        stage.schedule(DeferredLlmAnalysisRequest("job-9", invoice(), "<xml/>".toByteArray(), "{}", "layout", "invoice/job-9-llm-report.md"))

        verify(store).put(eq("invoice/job-9-llm-report.md"), eq("# Bericht".toByteArray()), eq("text/markdown"))
        val refsCaptor = argumentCaptor<Map<String, String>>()
        verify(resultPublisher).publishResult(eq("job-9"), eq("LLM_COMPLETED"), refsCaptor.capture(), eq(""), isNull(), anyOrNull())
        val refs = refsCaptor.firstValue
        assertEquals("COMPLETED", refs["llmStatus"])
        assertEquals("qwen", refs["llmModel"])
        assertEquals("invoice/job-9-llm-report.md", refs["llmReportKey"])
        assertEquals("4200", refs["llmDurationMs"])
        assertFalse(refs.containsKey("llmError"), "a successful run must not carry an llmError key")
    }

    @Test
    fun `boundary - a successful model call with a blank report is never stored, publishes with no report key`() {
        val client: LlmValidationPort = mock()
        whenever(client.analyze(any(), any(), any(), any())).thenReturn(
            LlmModelResult(model = "qwen", markdownReport = "   ", success = true, durationMs = 100),
        )
        val store: DocumentStore = mock()
        val resultPublisher: ResultPublisher = mock()
        val stage = sameThreadStage(client, store, resultPublisher)

        stage.schedule(DeferredLlmAnalysisRequest("job-blank", invoice(), "<xml/>".toByteArray(), "{}", "", "invoice/job-blank-llm-report.md"))

        verify(store, never()).put(any(), any(), any())
        val refsCaptor = argumentCaptor<Map<String, String>>()
        verify(resultPublisher).publishResult(eq("job-blank"), eq("LLM_COMPLETED"), refsCaptor.capture(), eq(""), isNull(), anyOrNull())
        assertFalse(refsCaptor.firstValue.containsKey("llmReportKey"))
    }

    @Test
    fun `boundary - a blank (non-null) error is never carried into resultRefs`() {
        val client: LlmValidationPort = mock()
        whenever(client.analyze(any(), any(), any(), any())).thenReturn(
            LlmModelResult(model = "qwen", markdownReport = "", success = false, durationMs = 100, error = "   "),
        )
        val resultPublisher: ResultPublisher = mock()
        val stage = sameThreadStage(client, mock(), resultPublisher)

        stage.schedule(DeferredLlmAnalysisRequest("job-blank-error", invoice(), "<xml/>".toByteArray(), "{}", "", "invoice/job-blank-error-llm-report.md"))

        val refsCaptor = argumentCaptor<Map<String, String>>()
        verify(resultPublisher).publishResult(eq("job-blank-error"), eq("LLM_FAILED"), refsCaptor.capture(), eq(""), isNull(), anyOrNull())
        assertFalse(refsCaptor.firstValue.containsKey("llmError"), "a blank error must not be carried into resultRefs")
    }

    @Test
    fun `a failed model call publishes LLM_FAILED with the error, never touching the document store`() {
        val client: LlmValidationPort = mock()
        whenever(client.analyze(any(), any(), any(), any())).thenReturn(
            LlmModelResult(model = "qwen", markdownReport = "", success = false, durationMs = 300_000, error = "Zeitüberschreitung nach 300s."),
        )
        val store: DocumentStore = mock()
        val resultPublisher: ResultPublisher = mock()
        val stage = sameThreadStage(client, store, resultPublisher)

        stage.schedule(DeferredLlmAnalysisRequest("job-10", invoice(), "<xml/>".toByteArray(), "{}", "", "invoice/job-10-llm-report.md"))

        verify(store, never()).put(any(), any(), any())
        val refsCaptor = argumentCaptor<Map<String, String>>()
        verify(resultPublisher).publishResult(eq("job-10"), eq("LLM_FAILED"), refsCaptor.capture(), eq(""), isNull(), anyOrNull())
        val refs = refsCaptor.firstValue
        assertEquals("FAILED", refs["llmStatus"])
        assertEquals("Zeitüberschreitung nach 300s.", refs["llmError"])
        assertFalse(refs.containsKey("llmReportKey"), "no report was ever stored — the key must be absent, not blank")
    }

    @Test
    fun `a successful model call whose report cannot be stored still publishes LLM_FAILED`() {
        val client: LlmValidationPort = mock()
        whenever(client.analyze(any(), any(), any(), any())).thenReturn(
            LlmModelResult(model = "qwen", markdownReport = "# Bericht", success = true, durationMs = 500),
        )
        val store: DocumentStore = mock()
        doThrow(RuntimeException("S3 unreachable")).whenever(store).put(any(), any(), any())
        val resultPublisher: ResultPublisher = mock()
        val stage = sameThreadStage(client, store, resultPublisher)

        stage.schedule(DeferredLlmAnalysisRequest("job-11", invoice(), "<xml/>".toByteArray(), "{}", "", "invoice/job-11-llm-report.md"))

        val refsCaptor = argumentCaptor<Map<String, String>>()
        verify(resultPublisher).publishResult(eq("job-11"), eq("LLM_FAILED"), refsCaptor.capture(), eq(""), isNull(), anyOrNull())
        assertEquals("FAILED", refsCaptor.firstValue["llmStatus"])
        assertTrue(refsCaptor.firstValue["llmError"]!!.contains("could not be stored"))
    }

    @Test
    fun `a scheduling failure is logged and swallowed, never propagated to the caller`() {
        val stage = LlmAnalysisStage(mock(), mock(), mock())
        stage.executor = Executor { throw RejectedExecutionException("pool shutting down") }

        // Must not throw — see the class KDoc: the print job already completed successfully via
        // the first callback by the time this is reached.
        stage.schedule(DeferredLlmAnalysisRequest("job-12", invoice(), ByteArray(0), "{}", "", "key"))
    }

    @Test
    fun `two schedule calls run strictly one after another on the single-thread executor`() {
        val order = CopyOnWriteArrayList<String>()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val client: LlmValidationPort = mock()
        whenever(client.analyze(any(), any(), any(), any())).thenAnswer { invocation ->
            val inv = invocation.getArgument<InvoiceDto>(1)
            order.add("start-${inv.invoiceNumber.value}")
            if (inv.invoiceNumber.value == "INV-1") {
                firstStarted.countDown()
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS), "test setup: release never signaled")
            }
            order.add("end-${inv.invoiceNumber.value}")
            LlmModelResult(model = "m", markdownReport = "r", success = true, durationMs = 1)
        }
        val resultPublisher: ResultPublisher = mock()
        // Real (default) single-thread executor — this is the one behavior that must NOT be
        // faked with a same-thread stand-in, since it's exactly what's under test.
        val stage = LlmAnalysisStage(client, mock(), resultPublisher)

        stage.schedule(DeferredLlmAnalysisRequest("job-a", invoice("INV-1"), ByteArray(0), "{}", "", "key-a"))
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        stage.schedule(DeferredLlmAnalysisRequest("job-b", invoice("INV-2"), ByteArray(0), "{}", "", "key-b"))

        // job-b must not have started yet — job-a is still blocking the one executor thread.
        Thread.sleep(200)
        assertEquals(listOf("start-INV-1"), order.toList())

        releaseFirst.countDown()
        val deadline = System.currentTimeMillis() + 2_000
        while (order.size < 4 && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertEquals(listOf("start-INV-1", "end-INV-1", "start-INV-2", "end-INV-2"), order.toList())
    }

    @Test
    fun `boundary - schedule with no mlx client wired still publishes an LLM_FAILED result, never NPEs`() {
        val resultPublisher: ResultPublisher = mock()
        val stage = sameThreadStage(mlxClient = null, resultPublisher = resultPublisher)

        stage.schedule(DeferredLlmAnalysisRequest("job-13", invoice(), ByteArray(0), "{}", "", "key"))

        val refsCaptor = argumentCaptor<Map<String, String>>()
        verify(resultPublisher).publishResult(eq("job-13"), eq("LLM_FAILED"), refsCaptor.capture(), eq(""), isNull(), anyOrNull())
        assertEquals("FAILED", refsCaptor.firstValue["llmStatus"])
        assertEquals("oMLX", refsCaptor.firstValue["llmModel"])
    }

    // ── DeferredLlmAnalysisRequest.equals/hashCode — hand-written for the ByteArray
    // field (ArrayInDataClass), never exercised by schedule()'s own tests above since
    // none of them compare two request instances. ────────────────────────────────────────────

    private fun request(
        jobId: String = "job-eq",
        invoice: InvoiceDto = invoice(),
        xmlBytes: ByteArray = byteArrayOf(1, 2, 3),
        kositReportJson: String = "{}",
        layoutReport: String = "layout",
        llmReportKey: String = "key",
    ) = DeferredLlmAnalysisRequest(jobId, invoice, xmlBytes, kositReportJson, layoutReport, llmReportKey)

    @Test
    fun `equals is reflexive and true for two instances with the same values, including content-equal but distinct ByteArrays`() {
        val a = request(xmlBytes = byteArrayOf(1, 2, 3))
        val b = request(xmlBytes = byteArrayOf(1, 2, 3))

        assertEquals(a, a)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equals is false against a different type or null`() {
        val a = request()
        val notARequest: Any? = "not a request"
        val nothing: Any? = null
        assertFalse(a.equals(notARequest))
        assertFalse(a.equals(nothing))
    }

    @Test
    fun `boundary - equals is false when jobId differs`() {
        assertFalse(request(jobId = "a") == request(jobId = "b"))
    }

    @Test
    fun `boundary - equals is false when invoice differs`() {
        assertFalse(request(invoice = invoice("INV-1")) == request(invoice = invoice("INV-2")))
    }

    @Test
    fun `boundary - equals is false when xmlBytes content differs`() {
        assertFalse(request(xmlBytes = byteArrayOf(1)) == request(xmlBytes = byteArrayOf(2)))
    }

    @Test
    fun `boundary - equals is false when kositReportJson differs`() {
        assertFalse(request(kositReportJson = "{\"a\":1}") == request(kositReportJson = "{\"a\":2}"))
    }

    @Test
    fun `boundary - equals is false when layoutReport differs`() {
        assertFalse(request(layoutReport = "one") == request(layoutReport = "two"))
    }

    @Test
    fun `boundary - equals is false when llmReportKey differs`() {
        assertFalse(request(llmReportKey = "key-a") == request(llmReportKey = "key-b"))
    }

    @Test
    fun `boundary - a result-publish failure is logged and swallowed, not propagated`() {
        val client: LlmValidationPort = mock()
        whenever(client.analyze(any(), any(), any(), any())).thenReturn(
            LlmModelResult(model = "qwen", markdownReport = "# Bericht", success = true, durationMs = 42),
        )
        val store: DocumentStore = mock()
        val resultPublisher: ResultPublisher = mock()
        doThrow(RuntimeException("NATS unreachable")).whenever(resultPublisher)
            .publishResult(any(), any(), any(), any(), isNull(), anyOrNull())
        val stage = sameThreadStage(client, store, resultPublisher)

        // Must not throw — see publishOutcome's own KDoc: there is no NATS job message left to
        // nak here, the print job already completed successfully via the first callback.
        stage.schedule(DeferredLlmAnalysisRequest("job-14", invoice(), ByteArray(0), "{}", "", "invoice/job-14-llm-report.md"))
    }
}
