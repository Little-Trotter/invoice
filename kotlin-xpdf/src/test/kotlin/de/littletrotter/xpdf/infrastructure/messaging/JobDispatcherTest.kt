package de.littletrotter.xpdf.infrastructure.messaging

import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.FacturXExportOutcome
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.PayloadExchange
import de.littletrotter.shared.PdfExportPort
import de.littletrotter.shared.TripExportDto
import de.littletrotter.shared.TripWorkbookRefDto
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.documentjob.DocumentJobOptions
import de.littletrotter.shared.documentjob.DocumentJobRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal

/**
 * Dedicated unit coverage for [JobDispatcher], split out of `JobConsumer`. The composed
 * pipeline stays covered end-to-end by [JobConsumerTest]; this file tests routing/retry/ref
 * resolution in isolation, constructed directly with a mocked [JobResultReporter].
 */
class JobDispatcherTest {

    @AfterEach
    fun clearSystemProperties() {
        System.clearProperty("job.retry.count")
        System.clearProperty("job.retry.backoff.ms")
    }

    private fun newDispatcher(
        pdfExportPort: PdfExportPort,
        resultReporter: JobResultReporter,
        payloadExchange: PayloadExchange = mock(),
    ): JobDispatcher = JobDispatcher(pdfExportPort, payloadExchange, resultReporter)

    private val invoice = InvoiceDto()
    private fun job(engine: String? = null, options: DocumentJobOptions? = null) =
        DocumentJobRequest(id = "job-1", type = "GENERATE", correlationId = "corr-1", engine = engine, options = options)

    @Test
    fun `dispatch routes a null engine to Mustang and reports the completed export (happy path)`() {
        System.setProperty("job.retry.count", "0")
        val pdfExportPort: PdfExportPort = mock()
        val resultReporter: JobResultReporter = mock()
        val result = ExportResult("k.pdf", "k.xml", true, "kosit-ok", "sha")
        whenever(pdfExportPort.performExport(any(), eq("job-1"), any(), any(), any(), anyOrNull())).thenReturn(result)
        val dispatcher = newDispatcher(pdfExportPort, resultReporter)

        dispatcher.dispatch(job(), invoice)

        verify(resultReporter).publishCompleted(any(), eq(result))
        verify(resultReporter, never()).publishRetryExhaustedFailure(any(), any(), any())
    }

    @Test
    fun `dispatch hands the output-intent token of the job options to the export port`() {
        System.setProperty("job.retry.count", "0")
        val pdfExportPort: PdfExportPort = mock()
        val resultReporter: JobResultReporter = mock()
        val result = ExportResult("k.pdf", "k.xml", true, "kosit-ok", "sha")
        whenever(pdfExportPort.performExport(any(), eq("job-1"), any(), any(), any(), eq("oi1.token"))).thenReturn(result)

        newDispatcher(pdfExportPort, resultReporter).dispatch(job(options = DocumentJobOptions(outputIntent = "oi1.token")), invoice)

        verify(resultReporter).publishCompleted(any(), eq(result))
    }

    @Test
    fun `dispatch routes engine MUSTANG explicitly to Mustang, same as null`() {
        System.setProperty("job.retry.count", "0")
        val pdfExportPort: PdfExportPort = mock()
        val resultReporter: JobResultReporter = mock()
        val result = ExportResult("k.pdf", "k.xml", true, "kosit-ok", "sha")
        whenever(pdfExportPort.performExport(any(), eq("job-1"), any(), any(), any(), anyOrNull())).thenReturn(result)
        val dispatcher = newDispatcher(pdfExportPort, resultReporter)

        dispatcher.dispatch(job(engine = "MUSTANG"), invoice)

        verify(pdfExportPort).performExport(any(), eq("job-1"), any(), any(), any(), anyOrNull())
        verify(resultReporter).publishCompleted(any(), eq(result))
    }

    @Test
    fun `dispatch routes FACTURX to the Factur-X engine and reports success`() {
        System.setProperty("job.retry.count", "0")
        val pdfExportPort: PdfExportPort = mock()
        val resultReporter: JobResultReporter = mock()
        val result = ExportResult("k.pdf", "k.xml", true, "kosit-ok", "sha")
        whenever(pdfExportPort.performFacturXExport(any(), eq("job-1"), any(), any(), any(), anyOrNull()))
            .thenReturn(FacturXExportOutcome.Success(result))
        val dispatcher = newDispatcher(pdfExportPort, resultReporter)

        dispatcher.dispatch(job(engine = "FACTURX"), invoice)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        verify(resultReporter).publishCompleted(any(), eq(result))
    }

    @Test
    fun `dispatch reports a Factur-X engine rejection without retrying`() {
        System.setProperty("job.retry.count", "3")
        val pdfExportPort: PdfExportPort = mock()
        val resultReporter: JobResultReporter = mock()
        val outcome = FacturXExportOutcome.Failed(listOf(ValidationMessage("ERROR", "bad CII")), "{}")
        whenever(pdfExportPort.performFacturXExport(any(), eq("job-1"), any(), any(), any(), anyOrNull())).thenReturn(outcome)
        val dispatcher = newDispatcher(pdfExportPort, resultReporter)

        dispatcher.dispatch(job(engine = "FACTURX"), invoice)

        verify(pdfExportPort, times(1)).performFacturXExport(any(), any(), any(), any(), any(), anyOrNull())
        verify(resultReporter).publishFacturXEngineFailure(any(), eq(outcome), eq(false))
    }

    @Test
    fun `dispatch reports an unknown engine without ever calling either export port`() {
        val pdfExportPort: PdfExportPort = mock()
        val resultReporter: JobResultReporter = mock()
        val dispatcher = newDispatcher(pdfExportPort, resultReporter)

        dispatcher.dispatch(job(engine = "SOME_NEW_ENGINE"), invoice)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        verify(pdfExportPort, never()).performFacturXExport(any(), any(), any(), any(), any(), anyOrNull())
        verify(resultReporter).publishUnknownEngine(any(), eq("SOME_NEW_ENGINE"), eq(false))
    }

    @Test
    fun `dispatch retries a transient export failure and succeeds on the second attempt`() {
        System.setProperty("job.retry.count", "2")
        System.setProperty("job.retry.backoff.ms", "0")
        val pdfExportPort: PdfExportPort = mock()
        val resultReporter: JobResultReporter = mock()
        val result = ExportResult("k.pdf", "k.xml", true, "kosit-ok", "sha")
        whenever(pdfExportPort.performExport(any(), eq("job-1"), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("transient"))
            .thenReturn(result)
        val dispatcher = newDispatcher(pdfExportPort, resultReporter)

        dispatcher.dispatch(job(), invoice)

        verify(pdfExportPort, times(2)).performExport(any(), any(), any(), any(), any(), anyOrNull())
        verify(resultReporter).publishCompleted(any(), eq(result))
    }

    @Test
    fun `dispatch reports retry-exhausted once the retry budget is used up`() {
        System.setProperty("job.retry.count", "1")
        System.setProperty("job.retry.backoff.ms", "0")
        val pdfExportPort: PdfExportPort = mock()
        val resultReporter: JobResultReporter = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-1"), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("hard fail"))
        val dispatcher = newDispatcher(pdfExportPort, resultReporter)

        dispatcher.dispatch(job(), invoice)

        verify(pdfExportPort, times(2)).performExport(any(), any(), any(), any(), any(), anyOrNull())
        verify(resultReporter).publishRetryExhaustedFailure(any(), any(), eq(false))
    }

    @Test
    fun `a negative configured retryCount skips every attempt, including the first, and reports nothing`() {
        System.setProperty("job.retry.count", "-1")
        val pdfExportPort: PdfExportPort = mock()
        val resultReporter: JobResultReporter = mock()
        val dispatcher = newDispatcher(pdfExportPort, resultReporter)

        dispatcher.dispatch(job(), invoice)

        verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())
        verify(resultReporter, never()).publishCompleted(any(), any())
        verify(resultReporter, never()).publishRetryExhaustedFailure(any(), any(), any())
    }

    @Test
    fun `llmOptimize true is threaded through to the reporter on a retry-exhausted failure`() {
        System.setProperty("job.retry.count", "0")
        val pdfExportPort: PdfExportPort = mock()
        val resultReporter: JobResultReporter = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-1"), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("hard fail"))
        val dispatcher = newDispatcher(pdfExportPort, resultReporter)

        dispatcher.dispatch(job(options = DocumentJobOptions(llmOptimize = true)), invoice)

        verify(resultReporter).publishRetryExhaustedFailure(any(), any(), eq(true))
    }

    @Test
    fun `a resolvable trip workbookRef reaches the export service as resolvedWorkbookBase64`() {
        System.setProperty("job.retry.count", "0")
        val content = "workbook-bytes"
        val sha256 = PayloadExchange.sha256Hex(content)
        val payloadExchange: PayloadExchange = mock()
        whenever(payloadExchange.get("lt:tripwb:key1")).thenReturn(content)
        val pdfExportPort: PdfExportPort = mock()
        val resultReporter: JobResultReporter = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-1"), any(), any(), any(), anyOrNull()))
            .thenReturn(ExportResult("k.pdf", "k.xml", true, "kosit-ok", "sha"))
        val invoiceCaptor = argumentCaptor<InvoiceDto>()
        val dispatcher = newDispatcher(pdfExportPort, resultReporter, payloadExchange)
        val tripItem = InvoiceItemDto(
            positionId = "1",
            taxRatePercent = BigDecimal("19"),
            category = InvoiceItemCategory.TRIP,
            trip = TripExportDto(
                detailedOnInvoice = false,
                fixedNetPrice = null,
                calculatedNet = de.littletrotter.shared.Money.ZERO,
                workbookRef = TripWorkbookRefDto("lt:tripwb:key1", sha256, "trip.xlsx"),
            ),
        )

        dispatcher.dispatch(job(), invoice.copy(items = listOf(tripItem)))

        verify(pdfExportPort).performExport(invoiceCaptor.capture(), any(), any(), any(), any(), anyOrNull())
        assertEquals(content, invoiceCaptor.firstValue.items.single().trip?.resolvedWorkbookBase64)
    }

    @Test
    fun `a missing trip workbookRef key leaves resolvedWorkbookBase64 null and the job still completes`() {
        System.setProperty("job.retry.count", "0")
        val payloadExchange: PayloadExchange = mock()
        whenever(payloadExchange.get(any())).thenReturn(null)
        val pdfExportPort: PdfExportPort = mock()
        val resultReporter: JobResultReporter = mock()
        val result = ExportResult("k.pdf", "k.xml", true, "kosit-ok", "sha")
        whenever(pdfExportPort.performExport(any(), eq("job-1"), any(), any(), any(), anyOrNull())).thenReturn(result)
        val invoiceCaptor = argumentCaptor<InvoiceDto>()
        val dispatcher = newDispatcher(pdfExportPort, resultReporter, payloadExchange)
        val tripItem = InvoiceItemDto(
            positionId = "1",
            taxRatePercent = BigDecimal("19"),
            category = InvoiceItemCategory.TRIP,
            trip = TripExportDto(
                detailedOnInvoice = false,
                fixedNetPrice = null,
                calculatedNet = de.littletrotter.shared.Money.ZERO,
                workbookRef = TripWorkbookRefDto("lt:tripwb:missing", "abc", "trip.xlsx"),
            ),
        )

        dispatcher.dispatch(job(), invoice.copy(items = listOf(tripItem)))

        verify(pdfExportPort).performExport(invoiceCaptor.capture(), any(), any(), any(), any(), anyOrNull())
        assertEquals(null, invoiceCaptor.firstValue.items.single().trip?.resolvedWorkbookBase64)
        verify(resultReporter).publishCompleted(any(), eq(result))
    }

    @Test
    fun `an invoice with no TRIP items never touches PayloadExchange for workbook resolution`() {
        System.setProperty("job.retry.count", "0")
        val payloadExchange: PayloadExchange = mock()
        val pdfExportPort: PdfExportPort = mock()
        val resultReporter: JobResultReporter = mock()
        whenever(pdfExportPort.performExport(any(), eq("job-1"), any(), any(), any(), anyOrNull()))
            .thenReturn(ExportResult("k.pdf", "k.xml", true, "kosit-ok", "sha"))
        val dispatcher = newDispatcher(pdfExportPort, resultReporter, payloadExchange)
        val serviceItem = InvoiceItemDto(positionId = "1", taxRatePercent = BigDecimal("19"), category = InvoiceItemCategory.SERVICE)

        dispatcher.dispatch(job(), invoice.copy(items = listOf(serviceItem)))

        verify(payloadExchange, never()).get(any())
    }
}
