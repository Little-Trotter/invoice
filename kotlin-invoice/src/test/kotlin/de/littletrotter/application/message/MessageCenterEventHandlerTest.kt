package de.littletrotter.application.message

import de.littletrotter.domain.message.SystemMessageContent
import de.littletrotter.domain.event.DocumentGenerated
import de.littletrotter.domain.event.GenerationJobExhausted
import de.littletrotter.domain.event.GenerationJobFailed
import de.littletrotter.domain.event.GenerationJobResultProcessingFailed
import de.littletrotter.domain.event.InvoiceCancelled
import de.littletrotter.domain.event.InvoiceFinalized
import de.littletrotter.domain.event.InvoiceItemAdded
import de.littletrotter.domain.event.InvoiceLlmAnalysisResolved
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

/**
 * [MessageCenterEventHandler.onDomainEvent] against a mocked [MessageCenterService]
 * and [GenerationJobRepository]: every curated event maps to exactly one `record(...)` call with
 * the exact severity/source/key/args/ref from the task doc's table (deviations documented on the
 * handler methods themselves and in [GenerationJobFailed]/[InvoiceLlmAnalysisResolved]'s own
 * KDoc); an uncurated event produces none; a throwing service never propagates out of the
 * `@ObservesAsync` entry point.
 */
class MessageCenterEventHandlerTest {

    private val messageCenterService = mock<MessageCenterService>()
    private val generationJobRepository = mock<GenerationJobRepository>()
    private val handler = MessageCenterEventHandler(messageCenterService, generationJobRepository)

    private fun kositReport(outcome: KositOutcome): String =
        KositReportPayload.encode(KositValidationResult(status = outcome))

    // ── DocumentGenerated ────────────────────────────────────────────────────────────────────

    @Test
    fun `DocumentGenerated with a VALID KoSIT report records an INFO EXPORT message`() {
        whenever(generationJobRepository.findById(10L)).thenReturn(
            aGenerationJob { kositReport = kositReport(KositOutcome.VALID) },
        )

        handler.onDomainEvent(DocumentGenerated(invoiceId = 1L, invoiceNumber = "RE-2026-0001", generationJobId = 10L, resultRefs = null))

        verify(messageCenterService).record(
            SystemMessageContent(
                severity = SystemMessageSeverity.INFO,
                source = SystemMessageSource.EXPORT,
                messageKey = "message.center.documentGenerated.kositValid",
                args = listOf("RE-2026-0001"),
                resolvedText = "Rechnung RE-2026-0001: Dokument erzeugt, LT-Prüfung bestanden",
                refType = "INVOICE",
                refId = "1",
            ),
        )
    }

    @Test
    fun `DocumentGenerated with an INVALID KoSIT report records an ERROR EXPORT message`() {
        whenever(generationJobRepository.findById(11L)).thenReturn(
            aGenerationJob { kositReport = kositReport(KositOutcome.INVALID) },
        )

        handler.onDomainEvent(DocumentGenerated(invoiceId = 2L, invoiceNumber = "RE-2026-0002", generationJobId = 11L, resultRefs = null))

        verify(messageCenterService).record(
            SystemMessageContent(
                severity = SystemMessageSeverity.ERROR,
                source = SystemMessageSource.EXPORT,
                messageKey = "message.center.documentGenerated.kositFailed",
                args = listOf("RE-2026-0002"),
                resolvedText = "Rechnung RE-2026-0002: Dokument erzeugt, LT-Prüfung fehlgeschlagen",
                refType = "INVOICE",
                refId = "2",
            ),
        )
    }

    @Test
    fun `DocumentGenerated with a technical ERROR KoSIT report also records an ERROR EXPORT message`() {
        whenever(generationJobRepository.findById(12L)).thenReturn(
            aGenerationJob { kositReport = kositReport(KositOutcome.ERROR) },
        )

        handler.onDomainEvent(DocumentGenerated(invoiceId = 3L, invoiceNumber = "RE-2026-0003", generationJobId = 12L, resultRefs = null))

        verify(messageCenterService).record(
            SystemMessageContent(
                severity = SystemMessageSeverity.ERROR,
                source = SystemMessageSource.EXPORT,
                messageKey = "message.center.documentGenerated.kositFailed",
                args = listOf("RE-2026-0003"),
                resolvedText = "Rechnung RE-2026-0003: Dokument erzeugt, LT-Prüfung fehlgeschlagen",
                refType = "INVOICE",
                refId = "3",
            ),
        )
    }

    @Test
    fun `L24 a draft's DocumentGenerated (issued=false) records no message`() {
        handler.onDomainEvent(
            DocumentGenerated(invoiceId = 1L, invoiceNumber = "RE-2026-0001", generationJobId = 10L, resultRefs = null, issued = false),
        )

        verify(messageCenterService, never()).record(
            any(),
            recipientUserId = anyOrNull(),
        )
    }

    @Test
    fun `DocumentGenerated with no resolvable job (absent report) records a WARNING, not an ERROR`() {
        whenever(generationJobRepository.findById(13L)).thenReturn(null)

        handler.onDomainEvent(DocumentGenerated(invoiceId = 4L, invoiceNumber = "RE-2026-0004", generationJobId = 13L, resultRefs = null))

        verify(messageCenterService).record(
            SystemMessageContent(
                severity = SystemMessageSeverity.WARNING,
                source = SystemMessageSource.EXPORT,
                messageKey = "message.center.documentGenerated.kositFailed",
                args = listOf("RE-2026-0004"),
                resolvedText = "Rechnung RE-2026-0004: Dokument erzeugt, LT-Prüfung fehlgeschlagen",
                refType = "INVOICE",
                refId = "4",
            ),
        )
    }

    // ── GenerationJobFailed ──────────────────────────────────────────────────────────────────

    @Test
    fun `L24 a draft's GenerationJobFailed (issued=false) records no message`() {
        handler.onDomainEvent(
            GenerationJobFailed(invoiceId = 5L, invoiceNumber = "RE-2026-0005", generationJobId = 20L, reason = "worker crashed", issued = false),
        )

        verify(messageCenterService, never()).record(
            any(),
            recipientUserId = anyOrNull(),
        )
    }

    @Test
    fun `GenerationJobFailed records an ERROR GENERATION message with the reason`() {
        handler.onDomainEvent(GenerationJobFailed(invoiceId = 5L, invoiceNumber = "RE-2026-0005", generationJobId = 20L, reason = "external worker reported failure"))

        verify(messageCenterService).record(
            SystemMessageContent(
                severity = SystemMessageSeverity.ERROR,
                source = SystemMessageSource.GENERATION,
                messageKey = "message.center.generationJob.failed",
                args = listOf("RE-2026-0005", "external worker reported failure"),
                resolvedText = "Rechnung RE-2026-0005: Dokumenterzeugung fehlgeschlagen: external worker reported failure",
                refType = "INVOICE",
                refId = "5",
            ),
        )
    }

    // ── GenerationJobExhausted ──────────────────────────────────────────────────────

    @Test
    fun `GenerationJobExhausted records an ERROR GENERATION message naming the attempt count`() {
        handler.onDomainEvent(
            GenerationJobExhausted(invoiceId = 15L, invoiceNumber = "RE-2026-0015", generationJobId = 40L, attempts = 5, reason = "Attempt budget exhausted after 5 attempt(s) (max 5)"),
        )

        verify(messageCenterService).record(
            SystemMessageContent(
                severity = SystemMessageSeverity.ERROR,
                source = SystemMessageSource.GENERATION,
                messageKey = "message.center.generationJob.exhausted",
                args = listOf("RE-2026-0015", "5"),
                resolvedText = "Rechnung RE-2026-0015: E-Rechnungs-Auftrag nach 5 Versuchen endgültig fehlgeschlagen",
                refType = "INVOICE",
                refId = "15",
            ),
        )
    }

    // ── GenerationJobResultProcessingFailed (L3, fix-generation-job-failure-visibility.md) ──────

    @Test
    fun `GenerationJobResultProcessingFailed records an ERROR GENERATION message with the give-up reason`() {
        handler.onDomainEvent(
            GenerationJobResultProcessingFailed(
                invoiceId = 25L, invoiceNumber = "RE-2026-0025", generationJobId = 60L,
                reason = "processing failed after 3 attempts: db timeout",
            ),
        )

        verify(messageCenterService).record(
            SystemMessageContent(
                severity = SystemMessageSeverity.ERROR,
                source = SystemMessageSource.GENERATION,
                messageKey = "message.center.generationJob.resultProcessingFailed",
                args = listOf("RE-2026-0025", "processing failed after 3 attempts: db timeout"),
                resolvedText = "Rechnung RE-2026-0025: E-Rechnungs-Auftrag konnte nicht verarbeitet werden: processing failed after 3 attempts: db timeout",
                refType = "INVOICE",
                refId = "25",
            ),
        )
    }

    // ── InvoiceFinalized ─────────────────────────────────────────────────────────────────────

    @Test
    fun `InvoiceFinalized records an INFO INVOICE message`() {
        handler.onDomainEvent(InvoiceFinalized(invoiceId = 6L, invoiceNumber = "RE-2026-0006"))

        verify(messageCenterService).record(
            SystemMessageContent(
                severity = SystemMessageSeverity.INFO,
                source = SystemMessageSource.INVOICE,
                messageKey = "message.center.invoice.sent",
                args = listOf("RE-2026-0006"),
                resolvedText = "Rechnung RE-2026-0006 versandt",
                refType = "INVOICE",
                refId = "6",
            ),
        )
    }

    // ── InvoiceCancelled ─────────────────────────────────────────────────────────────

    @Test
    fun `InvoiceCancelled records an INFO INVOICE message naming both documents`() {
        handler.onDomainEvent(
            InvoiceCancelled(
                creditNoteId = 99L,
                creditNoteNumber = "RE-2026-0042",
                originalInvoiceId = 7L,
                originalInvoiceNumber = "RE-2026-0007",
            ),
        )

        verify(messageCenterService).record(
            SystemMessageContent(
                severity = SystemMessageSeverity.INFO,
                source = SystemMessageSource.INVOICE,
                messageKey = "message.center.invoice.cancelled",
                args = listOf("RE-2026-0007", "RE-2026-0042"),
                resolvedText = "Rechnung RE-2026-0007 storniert · Stornorechnung RE-2026-0042 erstellt",
                refType = "INVOICE",
                refId = "99",
            ),
        )
    }

    // ── InvoiceLlmAnalysisResolved ───────────────────────────────────────────────────────────

    @Test
    fun `InvoiceLlmAnalysisResolved COMPLETED records an INFO VALIDATION message`() {
        handler.onDomainEvent(InvoiceLlmAnalysisResolved(invoiceId = 8L, invoiceNumber = "RE-2026-0008", modelName = "Qwen3.6-35B-A3B-8bit", status = LlmAnalysisStatus.COMPLETED))

        verify(messageCenterService).record(
            SystemMessageContent(
                severity = SystemMessageSeverity.INFO,
                source = SystemMessageSource.VALIDATION,
                messageKey = "message.center.llmAnalysis.resolved",
                args = listOf("RE-2026-0008", "abgeschlossen"),
                resolvedText = "Rechnung RE-2026-0008: LLM-Analyse abgeschlossen",
                refType = "INVOICE",
                refId = "8",
            ),
        )
    }

    @Test
    fun `InvoiceLlmAnalysisResolved FAILED records a WARNING VALIDATION message`() {
        handler.onDomainEvent(InvoiceLlmAnalysisResolved(invoiceId = 9L, invoiceNumber = "RE-2026-0009", modelName = "Qwen3.6-35B-A3B-8bit", status = LlmAnalysisStatus.FAILED))

        verify(messageCenterService).record(
            SystemMessageContent(
                severity = SystemMessageSeverity.WARNING,
                source = SystemMessageSource.VALIDATION,
                messageKey = "message.center.llmAnalysis.resolved",
                args = listOf("RE-2026-0009", "fehlgeschlagen"),
                resolvedText = "Rechnung RE-2026-0009: LLM-Analyse fehlgeschlagen",
                refType = "INVOICE",
                refId = "9",
            ),
        )
    }

    @Test
    fun `InvoiceLlmAnalysisResolved with a non-terminal status records nothing (defensive, not reachable in practice)`() {
        handler.onDomainEvent(InvoiceLlmAnalysisResolved(invoiceId = 8L, invoiceNumber = "RE-2026-0008", modelName = "m", status = LlmAnalysisStatus.PENDING))

        verify(messageCenterService, never()).record(
            any(),
            recipientUserId = anyOrNull(),
        )
    }

    // ── uncurated events ─────────────────────────────────────────────────────────────────────

    @Test
    fun `an uncurated event produces no record call`() {
        handler.onDomainEvent(InvoiceItemAdded(invoiceId = 1L, invoiceNumber = "RE-2026-0001", positionId = "p1"))

        verifyNoMoreInteractions(messageCenterService)
    }

    // ── failure isolation ────────────────────────────────────────────────────────────────────

    @Test
    fun `a throwing MessageCenterService never propagates out of onDomainEvent`() {
        whenever(
            messageCenterService.record(
                any(),
                recipientUserId = anyOrNull(),
            )
        ).thenThrow(RuntimeException("DB down"))

        assertDoesNotThrow {
            handler.onDomainEvent(InvoiceFinalized(invoiceId = 1L, invoiceNumber = "RE-2026-0001"))
        }
    }

    @Test
    fun `a throwing GenerationJobRepository lookup never propagates either`() {
        whenever(generationJobRepository.findById(any())).thenThrow(RuntimeException("DB down"))

        assertDoesNotThrow {
            handler.onDomainEvent(DocumentGenerated(invoiceId = 1L, invoiceNumber = "RE-2026-0001", generationJobId = 1L, resultRefs = null))
        }
    }
}
