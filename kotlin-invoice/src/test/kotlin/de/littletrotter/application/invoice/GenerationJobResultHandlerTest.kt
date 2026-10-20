package de.littletrotter.application.invoice

import de.littletrotter.application.shared.DomainEventDispatcher
import de.littletrotter.fixture.aGenerationJob
import  de.littletrotter.fixture.anInvoice
import de.littletrotter.domain.event.DocumentGenerated
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.forceStatusForTest
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.fixture.anInvoice
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.test.FIXED_TIME_SOURCE
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Unit tests for the extracted completion + send-outcome-resolution responsibility. */
class GenerationJobResultHandlerTest {

    private fun invoice(status: InvoiceStatus = InvoiceStatus.IN_REVIEW): Invoice = anInvoice(id = 900L, invoiceNumber = InvoiceNumber("RE-2025-01-00900"))
        .also { it.forceStatusForTest(status) }

    private fun kositReportWithStatus(status: KositOutcome): String =
        KositReportPayload.encode(KositValidationResult(status = status, details = emptyList()))

    private val generationJobRepository: GenerationJobRepository = mock()

    // Used by the resolveSendOutcome tests below, which only assert on the InvoiceLifecycleService/
    // MessageCenterService mocks, not on fired events (markCompleted's own tests use a dedicated
    // dispatcher/bus pair instead so they can verify the event).
    private val dispatcher = DomainEventDispatcher(mock<jakarta.enterprise.event.Event<DomainEvent>>(), null)

    @Test
    fun `markCompleted transitions to COMPLETED and fires DocumentGenerated`() {
        val bus: jakarta.enterprise.event.Event<DomainEvent> = mock()
        val ops = GenerationJobResultHandler(generationJobRepository, DomainEventDispatcher(bus, null), time = FIXED_TIME_SOURCE)
        val job = aGenerationJob { invoice = invoice(); status = GenerationJobStatus.RUNNING }
        whenever(generationJobRepository.save(job)).thenReturn(job)

        val result = ops.markCompleted(job, resultRefs = "{}")

        assertEquals(GenerationJobStatus.COMPLETED, result.status)
        verify(bus).fireAsync(any<DocumentGenerated>())
    }

    @Test
    fun `markCompleted - a checksum write failure propagates, rolls back and never marks the job COMPLETED`() {
        val persister: DocumentChecksumPersister = mock()
        val bus: jakarta.enterprise.event.Event<DomainEvent> = mock()
        val ops = GenerationJobResultHandler(
            generationJobRepository, DomainEventDispatcher(bus, null),
            time = FIXED_TIME_SOURCE, checksumPersister = persister,
        )
        val inv = invoice(InvoiceStatus.ISSUED)
        val job = aGenerationJob { invoice = inv; status = GenerationJobStatus.RUNNING }
        whenever(persister.persist(any(), org.mockito.kotlin.anyOrNull(), any(), org.mockito.kotlin.anyOrNull())).thenThrow(IllegalStateException("db down"))

        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            ops.markCompleted(job, resultRefs = "{\"objectKey\":\"invoices/x.pdf\",\"sha256\":\"ab\"}")
        }

        assertEquals(GenerationJobStatus.RUNNING, job.status, "job must not be marked COMPLETED")
        assertEquals(null, job.resultRefs)
        verify(generationJobRepository, never()).save(any())
        verify(bus, never()).fireAsync(any<DocumentGenerated>())
        // The rollback itself is the container's: markCompleted is @Transactional and an unchecked
        // exception is not exempted, so the whole unit (status, refs, checksum rows) rolls back.
        val tx = GenerationJobResultHandler::class.java.methods
            .first { it.name == "markCompleted" && !it.isSynthetic && it.parameterCount == 4 }
            .getAnnotation(jakarta.transaction.Transactional::class.java)
        org.junit.jupiter.api.Assertions.assertNotNull(tx, "markCompleted must be @Transactional")
        org.junit.jupiter.api.Assertions.assertTrue(tx.dontRollbackOn.isEmpty(), "no exception may be exempted from rollback")
    }

    @Test
    fun `markCompleted does not downgrade an already terminal llmStatus`() {
        val bus: jakarta.enterprise.event.Event<DomainEvent> = mock()
        val ops = GenerationJobResultHandler(generationJobRepository, DomainEventDispatcher(bus, null), time = FIXED_TIME_SOURCE)
        val job = aGenerationJob { invoice = invoice(); status = GenerationJobStatus.RUNNING; llmStatus = "COMPLETED" }
        whenever(generationJobRepository.save(job)).thenReturn(job)

        ops.markCompleted(job, llmStatus = "PENDING")

        assertEquals("COMPLETED", job.llmStatus)
    }

    @Test
    fun `markCompleted persists the checksums in the same call for an issued invoice`() {
        val bus: jakarta.enterprise.event.Event<DomainEvent> = mock()
        val persister: DocumentChecksumPersister = mock()
        val ops = GenerationJobResultHandler(
            generationJobRepository, DomainEventDispatcher(bus, null), time = FIXED_TIME_SOURCE, checksumPersister = persister,
        )
        val issuedInvoice = invoice(InvoiceStatus.ISSUED)
        val job = aGenerationJob { invoice = issuedInvoice; status = GenerationJobStatus.RUNNING }
        whenever(generationJobRepository.save(job)).thenReturn(job)

        ops.markCompleted(job, resultRefs = "{refs}")

        verify(persister).persist(eq(issuedInvoice), eq("{refs}"), any(), org.mockito.kotlin.anyOrNull())
    }

    @Test
    fun `markCompleted persists no checksums for a draft review job`() {
        val bus: jakarta.enterprise.event.Event<DomainEvent> = mock()
        val persister: DocumentChecksumPersister = mock()
        val ops = GenerationJobResultHandler(
            generationJobRepository, DomainEventDispatcher(bus, null), time = FIXED_TIME_SOURCE, checksumPersister = persister,
        )
        val job = aGenerationJob { invoice = invoice(); status = GenerationJobStatus.RUNNING }
        whenever(generationJobRepository.save(job)).thenReturn(job)

        ops.markCompleted(job, resultRefs = "{refs}")

        verify(persister, never()).persist(any(), org.mockito.kotlin.anyOrNull(), any(), org.mockito.kotlin.anyOrNull())
    }

    @Test
    fun `markCompleted skips the event when the job has no invoice`() {
        val bus: jakarta.enterprise.event.Event<DomainEvent> = mock()
        val ops = GenerationJobResultHandler(generationJobRepository, DomainEventDispatcher(bus, null), time = FIXED_TIME_SOURCE)
        val job = aGenerationJob { status = GenerationJobStatus.RUNNING }
        whenever(generationJobRepository.save(job)).thenReturn(job)

        ops.markCompleted(job)

        verify(bus, never()).fireAsync(any())
    }

    @Test
    fun `resolveSendOutcome transitions to ISSUED when validation is VALID`() {
        val lifecycle: SendTransitionRunner = mock()
        val messageCenter = mock<de.littletrotter.application.message.MessageCenterService>()
        val ops = GenerationJobResultHandler(generationJobRepository, dispatcher, null, lifecycle, messageCenter, time = FIXED_TIME_SOURCE)
        val job = aGenerationJob {
            invoice = invoice()
            status = GenerationJobStatus.COMPLETED
            kositReport = kositReportWithStatus(KositOutcome.VALID)
        }

        ops.resolveSendOutcome(job)

        verify(lifecycle).transitionToSent(900L)
    }

    @Test
    fun `resolveSendOutcome records a message and never transitions when validation is INVALID`() {
        val lifecycle: SendTransitionRunner = mock()
        val messageCenter = mock<de.littletrotter.application.message.MessageCenterService>()
        val ops = GenerationJobResultHandler(generationJobRepository, dispatcher, null, lifecycle, messageCenter, time = FIXED_TIME_SOURCE)
        val job = aGenerationJob {
            invoice = invoice()
            status = GenerationJobStatus.COMPLETED
            kositReport = kositReportWithStatus(KositOutcome.INVALID)
        }

        ops.resolveSendOutcome(job)

        verify(lifecycle, never()).transitionToSent(any())
        verify(messageCenter).record(any(), org.mockito.kotlin.anyOrNull())
    }

    @Test
    fun `resolveSendOutcome records a generationFailed message for a FAILED job`() {
        val lifecycle: SendTransitionRunner = mock()
        val messageCenter = mock<de.littletrotter.application.message.MessageCenterService>()
        val ops = GenerationJobResultHandler(generationJobRepository, dispatcher, null, lifecycle, messageCenter, time = FIXED_TIME_SOURCE)
        val job = aGenerationJob {
            invoice = invoice()
            status = GenerationJobStatus.FAILED
            resultRefs = """{"error":"boom"}"""
        }

        ops.resolveSendOutcome(job)

        verify(lifecycle, never()).transitionToSent(any())
        verify(messageCenter).record(any(), org.mockito.kotlin.anyOrNull())
    }

    @Test
    fun `resolveSendOutcome leaves a voided invoice alone and does not call transition`() {
        val lifecycle: SendTransitionRunner = mock()
        val messageCenter = mock<de.littletrotter.application.message.MessageCenterService>()
        val ops = GenerationJobResultHandler(generationJobRepository, dispatcher, null, lifecycle, messageCenter, time = FIXED_TIME_SOURCE)
        val job = aGenerationJob {
            invoice = invoice(InvoiceStatus.VOIDED)
            status = GenerationJobStatus.COMPLETED
            kositReport = kositReportWithStatus(KositOutcome.VALID)
        }

        ops.resolveSendOutcome(job)

        verify(lifecycle, never()).transitionToSent(any())
        verify(messageCenter, never()).record(any(), org.mockito.kotlin.anyOrNull())
    }

    @Test
    fun `resolveSendOutcome treats a transition lost to a concurrent user change as a warning without a message`() {
        val lifecycle: SendTransitionRunner = mock()
        whenever(lifecycle.transitionToSent(900L))
            .thenThrow(de.littletrotter.domain.exception.InvoiceStatusTransitionException("VOIDED", "ISSUED"))
        val messageCenter = mock<de.littletrotter.application.message.MessageCenterService>()
        val ops = GenerationJobResultHandler(generationJobRepository, dispatcher, null, lifecycle, messageCenter, time = FIXED_TIME_SOURCE)
        val job = aGenerationJob {
            invoice = invoice()
            status = GenerationJobStatus.COMPLETED
            kositReport = kositReportWithStatus(KositOutcome.VALID)
        }

        assertDoesNotThrow { ops.resolveSendOutcome(job) }

        verify(messageCenter, never()).record(any(), org.mockito.kotlin.anyOrNull())
    }

    @Test
    fun `resolveSendOutcome swallows and logs when its collaborators are not wired`() {
        val ops = GenerationJobResultHandler(generationJobRepository, dispatcher, time = FIXED_TIME_SOURCE)
        val job = aGenerationJob { invoice = invoice(); status = GenerationJobStatus.COMPLETED }

        assertDoesNotThrow { ops.resolveSendOutcome(job) }
    }

    // ── veraPDF verdict outstanding ───────────────────────────────────────

    private fun reportWithVeraPdf(pending: Boolean, outcome: KositOutcome = KositOutcome.VALID): String {
        val stages = listOf(
            de.littletrotter.shared.ValidationStageResult("PDFA", KositOutcome.VALID),
            de.littletrotter.shared.ValidationStageResult("VERAPDF", outcome, pending = pending, advisory = true),
        )
        return KositReportPayload(
            status = "VALID",
            validationRun = de.littletrotter.shared.ValidationRun(stages, KositOutcome.VALID, startedAt = "2026-10-12T10:00:00Z"),
        ).toJson()
    }

    private fun sendJob(report: String) = aGenerationJob {
        invoice = invoice()
        status = GenerationJobStatus.COMPLETED
        kositReport = report
    }

    private fun handlerWaiting(lifecycle: SendTransitionRunner, messageCenter: de.littletrotter.application.message.MessageCenterService, mustWait: Boolean): GenerationJobResultHandler {
        val guard: VeraPdfSendGuard = mock()
        whenever(guard.mustWait(any())).thenReturn(mustWait)
        return GenerationJobResultHandler(
            generationJobRepository, dispatcher, null, lifecycle, messageCenter,
            time = FIXED_TIME_SOURCE, veraPdfSendGuard = guard,
        )
    }

    @Test
    fun `resolveSendOutcome waits while the veraPDF verdict is outstanding and tells the user`() {
        val lifecycle: SendTransitionRunner = mock()
        val messageCenter = mock<de.littletrotter.application.message.MessageCenterService>()
        val ops = handlerWaiting(lifecycle, messageCenter, mustWait = true)

        ops.resolveSendOutcome(sendJob(reportWithVeraPdf(pending = true)))

        verify(lifecycle, never()).transitionToSent(any())
        val content = org.mockito.kotlin.argumentCaptor<de.littletrotter.domain.message.SystemMessageContent>()
        verify(messageCenter).record(content.capture(), org.mockito.kotlin.anyOrNull())
        assertEquals("message.center.send.veraPdfPending", content.firstValue.messageKey)
        assertEquals(de.littletrotter.domain.message.SystemMessageSeverity.INFO, content.firstValue.severity)
    }

    @Test
    fun `resolveSendOutcome sends once the veraPDF verdict is in, findings or not`() {
        val lifecycle: SendTransitionRunner = mock()
        val messageCenter = mock<de.littletrotter.application.message.MessageCenterService>()
        val ops = handlerWaiting(lifecycle, messageCenter, mustWait = false)

        ops.resolveSendOutcome(sendJob(reportWithVeraPdf(pending = false, outcome = KositOutcome.INVALID)))

        verify(lifecycle).transitionToSent(900L)
        verify(messageCenter, never()).record(any(), org.mockito.kotlin.anyOrNull())
    }

    @Test
    fun `markCompleted asks for the veraPDF check with the stored document and the final report`() {
        val scheduler: PdfCheckRequestScheduler = mock()
        val bus: jakarta.enterprise.event.Event<DomainEvent> = mock()
        val ops = GenerationJobResultHandler(
            generationJobRepository, DomainEventDispatcher(bus, null),
            time = FIXED_TIME_SOURCE, pdfCheckScheduler = scheduler,
        )
        val job = aGenerationJob { id = 7L; invoice = invoice(); status = GenerationJobStatus.RUNNING; correlationId = "c-7" }
        whenever(generationJobRepository.save(job)).thenReturn(job)
        val refs = """{"objectKey":"inv/7.pdf","sha256":"ab"}"""

        ops.markCompleted(job, resultRefs = refs, kositReport = reportWithVeraPdf(pending = true))

        verify(scheduler).scheduleIfPending(eq(7L), eq("c-7"), eq(refs), any())
    }
}
