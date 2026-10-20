package de.littletrotter.application.message

import de.littletrotter.domain.message.SystemMessageContent
import de.littletrotter.application.invoice.KositReportReader
import de.littletrotter.application.invoice.KositStatus
import de.littletrotter.domain.event.DocumentGenerated
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.event.GenerationJobExhausted
import de.littletrotter.domain.event.GenerationJobFailed
import de.littletrotter.domain.event.GenerationJobResultProcessingFailed
import de.littletrotter.domain.event.InvoiceCancelled
import de.littletrotter.domain.event.InvoiceCreatedFromQuote
import de.littletrotter.domain.event.InvoiceCreationFromQuoteRejected
import de.littletrotter.domain.event.InvoiceFinalized
import de.littletrotter.domain.event.InvoiceLlmAnalysisResolved
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.domain.repository.GenerationJobRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.ObservesAsync
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * The message center's first producer: a single CDI observer on the domain-event
 * bus mapping ONLY the curated event list onto
 * [MessageCenterService.record] calls. Every mapping broadcasts (`recipientUserId = null` —
 * per-user targeting comes once Authentik supplies real user identity). Dispatch style matches
 * [de.littletrotter.application.invoice.InvoiceDocumentEventHandler]/
 * [de.littletrotter.application.invoice.InvoiceLlmAnalysisEventHandler]: one entry point, `when`
 * dispatch by event subtype, every branch defensive and never propagating.
 *
 * Two of the five curated rows had no domain event to observe at all before this task —
 * "generation job failure" and "LLM analysis result" were only ever visible as entity-state
 * writes ([de.littletrotter.domain.invoice.GenerationJob.status],
 * [de.littletrotter.domain.invoice.InvoiceLlmAnalysis.status]), never as a fired
 * [DomainEvent] — so [GenerationJobFailed]/[InvoiceLlmAnalysisResolved] were added
 * (`domain/event/DomainEvents.kt`, fired from
 * [de.littletrotter.application.invoice.GenerationJobService]) to give this handler something to
 * observe, mirroring [DocumentGenerated]'s existing shape/firing style. See those two events' own
 * KDoc for the exact firing conditions.
 *
 * **Failure isolation:** every event this handler cares about is published through
 * `DomainEventDispatcher.publishAfterCommit` at its origin (asynchronously, after the commit), so this observer
 * already runs off the transaction's thread — but
 * [onDomainEvent] still wraps the whole dispatch in its own try/catch: a message-center failure
 * (bad service/repository state, a DB hiccup) must never surface as an unhandled exception back
 * onto whatever thread the CDI event bus runs the observer on. Logged, never rethrown — see
 * `MessageCenterEventHandlerTest`'s "does not propagate" case.
 */
@ApplicationScoped
class MessageCenterEventHandler @Inject constructor(
    private val messageCenterService: MessageCenterService,
    private val generationJobRepository: GenerationJobRepository,
) {

    private val log = Logger.getLogger(MessageCenterEventHandler::class.java)

    private companion object {
        const val REF_INVOICE = "INVOICE"
    }

    fun onDomainEvent(@ObservesAsync event: DomainEvent) {
        try {
            when (event) {
                is DocumentGenerated -> onDocumentGenerated(event)
                is GenerationJobFailed -> onGenerationJobFailed(event)
                is GenerationJobExhausted -> onGenerationJobExhausted(event)
                is GenerationJobResultProcessingFailed -> onGenerationJobResultProcessingFailed(event)
                is InvoiceFinalized -> onInvoiceFinalized(event)
                is InvoiceCancelled -> onInvoiceCancelled(event)
                is InvoiceCreatedFromQuote -> onInvoiceCreatedFromQuote(event)
                is InvoiceCreationFromQuoteRejected -> onInvoiceCreationFromQuoteRejected(event)
                is InvoiceLlmAnalysisResolved -> onInvoiceLlmAnalysisResolved(event)
                // Every other DomainEvent (InvoiceCreated, InvoiceStatusChanged, InvoiceItemAdded/
                // Removed, GenerationJobCreated, PartySnapshotsRefreshed, ...) is deliberately not
                // curated — the whole point is a short, important list, not an audit log.
                else -> Unit
            }
        } catch (ex: Exception) {
            // Sole catch-all of this flow: see this class's own "Failure
            // isolation" KDoc above. This is an @ObservesAsync CDI observer; letting an exception
            // propagate would surface on
            // the CDI event bus's thread, unrelated to and well after the business transaction that
            // fired the event. No counter exists for message-center failures; already logged at
            // error with the event type as business context.
            log.errorf(
                ex,
                "Message-center recording failed for %s — the observed business transaction is unaffected",
                event.javaClass.simpleName,
            )
        }
    }

    /**
     * [DocumentGenerated] only ever fires on a *completed* job — a completed job can still carry
     * a failed KoSIT outcome, which is exactly what the curated table's two `DocumentGenerated`
     * rows distinguish. The event itself carries no KoSIT outcome, so the job is re-read
     * (read-only) via [generationJobRepository] for [de.littletrotter.domain.invoice.GenerationJob
     * .kositReport], interpreted through the same [KositReportReader] the export UI uses.
     * [KositStatus.UNKNOWN] (job not found, or no/unparseable report) is treated as WARNING rather
     * than ERROR — it is "no result", not a confirmed failed check like
     * [KositStatus.INVALID]/[KositStatus.ERROR].
     */
    private fun onDocumentGenerated(event: DocumentGenerated) {
        // L24 — this curated message only ever fired for an issued/SEND job before the event
        // started firing unconditionally for drafts too (see DocumentGenerated's own KDoc); a
        // draft preview completing must not start posting "Dokument erzeugt" messages.
        if (!event.issued) return
        val job = generationJobRepository.findById(event.generationJobId)
        val kositStatus = KositReportReader.read(job?.kositReport).status
        val valid = kositStatus == KositStatus.VALID
        val severity = when (kositStatus) {
            KositStatus.VALID -> SystemMessageSeverity.INFO
            KositStatus.INVALID, KositStatus.ERROR -> SystemMessageSeverity.ERROR
            KositStatus.UNKNOWN -> SystemMessageSeverity.WARNING
        }
        val messageKey = if (valid) "message.center.documentGenerated.kositValid" else "message.center.documentGenerated.kositFailed"
        val resolvedText = if (valid) {
            "Rechnung ${event.invoiceNumber}: Dokument erzeugt, LT-Prüfung bestanden"
        } else {
            "Rechnung ${event.invoiceNumber}: Dokument erzeugt, LT-Prüfung fehlgeschlagen"
        }
        messageCenterService.record(
            SystemMessageContent(
                severity = severity,
                source = SystemMessageSource.EXPORT,
                messageKey = messageKey,
                args = listOf(event.invoiceNumber),
                resolvedText = resolvedText,
                refType = REF_INVOICE,
                refId = event.invoiceId.toString(),
            ),
        )
    }

    private fun onGenerationJobFailed(event: GenerationJobFailed) {
        // L24 — same "preserve pre-L24 behavior for a draft" reasoning as onDocumentGenerated
        // above; see GenerationJobFailed's own KDoc.
        if (!event.issued) return
        messageCenterService.record(
            SystemMessageContent(
                severity = SystemMessageSeverity.ERROR,
                source = SystemMessageSource.GENERATION,
                messageKey = "message.center.generationJob.failed",
                args = listOf(event.invoiceNumber, event.reason),
                resolvedText = "Rechnung ${event.invoiceNumber}: Dokumenterzeugung fehlgeschlagen: ${event.reason}",
                refType = REF_INVOICE,
                refId = event.invoiceId.toString(),
            ),
        )
    }

    /** The attempt-budget-exhaustion variant: a job that never even reached a worker
     *  result on its last cycle, distinct from [onGenerationJobFailed]'s worker-reported failure
     *  (see [GenerationJobExhausted]'s own KDoc). */
    private fun onGenerationJobExhausted(event: GenerationJobExhausted) {
        messageCenterService.record(
            SystemMessageContent(
                severity = SystemMessageSeverity.ERROR,
                source = SystemMessageSource.GENERATION,
                messageKey = "message.center.generationJob.exhausted",
                args = listOf(event.invoiceNumber, event.attempts.toString()),
                resolvedText = "Rechnung ${event.invoiceNumber}: E-Rechnungs-Auftrag nach ${event.attempts} Versuchen endgültig fehlgeschlagen",
                refType = REF_INVOICE,
                refId = event.invoiceId.toString(),
            ),
        )
    }

    /** L3 (fix-generation-job-failure-visibility.md) — the DLQ give-up variant: a worker result
     *  DID arrive, only this app's own processing of it never durably completed (see
     *  [GenerationJobResultProcessingFailed]'s own KDoc for how this differs from
     *  [onGenerationJobFailed]/[onGenerationJobExhausted]'s two cases). */
    private fun onGenerationJobResultProcessingFailed(event: GenerationJobResultProcessingFailed) {
        messageCenterService.record(
            SystemMessageContent(
                severity = SystemMessageSeverity.ERROR,
                source = SystemMessageSource.GENERATION,
                messageKey = "message.center.generationJob.resultProcessingFailed",
                args = listOf(event.invoiceNumber, event.reason),
                resolvedText = "Rechnung ${event.invoiceNumber}: E-Rechnungs-Auftrag konnte nicht verarbeitet werden: ${event.reason}",
                refType = REF_INVOICE,
                refId = event.invoiceId.toString(),
            ),
        )
    }

    private fun onInvoiceFinalized(event: InvoiceFinalized) {
        messageCenterService.record(
            SystemMessageContent(
                severity = SystemMessageSeverity.INFO,
                source = SystemMessageSource.INVOICE,
                messageKey = "message.center.invoice.sent",
                args = listOf(event.invoiceNumber),
                resolvedText = "Rechnung ${event.invoiceNumber} versandt",
                refType = REF_INVOICE,
                refId = event.invoiceId.toString(),
            ),
        )
    }

    /**
     * The message names both documents — the cancelled original and the cancellation document that
     * replaces it — because after ST-3 the event changed both, and a reader who sees only one of
     * the two numbers cannot tell what happened. The ref points at the cancellation document
     * ([InvoiceCancelled.creditNoteId]): it is the new entity, and the one a reader clicking the
     * row would want to open.
     */
    private fun onInvoiceCancelled(event: InvoiceCancelled) {
        messageCenterService.record(
            SystemMessageContent(
                severity = SystemMessageSeverity.INFO,
                source = SystemMessageSource.INVOICE,
                messageKey = "message.center.invoice.cancelled",
                args = listOf(event.originalInvoiceNumber, event.creditNoteNumber),
                resolvedText = "Rechnung ${event.originalInvoiceNumber} storniert · Stornorechnung ${event.creditNoteNumber} erstellt",
                refType = REF_INVOICE,
                refId = event.creditNoteId.toString(),
            ),
        )
    }

    private fun onInvoiceCreatedFromQuote(event: InvoiceCreatedFromQuote) {
        val skipped = event.skippedAttachments.joinToString(", ")
        val withSkipped = event.skippedAttachments.isNotEmpty()
        messageCenterService.record(
            SystemMessageContent(
                severity = SystemMessageSeverity.INFO,
                source = SystemMessageSource.INVOICE,
                messageKey = if (withSkipped) "message.center.invoice.fromQuoteSkipped" else "message.center.invoice.fromQuote",
                args = if (withSkipped) listOf(event.quoteNumber, event.invoiceNumber, skipped) else listOf(event.quoteNumber, event.invoiceNumber),
                resolvedText = if (withSkipped) {
                    "Angebot ${event.quoteNumber}: Rechnungsentwurf ${event.invoiceNumber} erstellt · nicht übernommen: $skipped"
                } else {
                    "Angebot ${event.quoteNumber}: Rechnungsentwurf ${event.invoiceNumber} erstellt"
                },
                refType = REF_INVOICE,
                refId = event.invoiceId.toString(),
            ),
        )
    }

    private fun onInvoiceCreationFromQuoteRejected(event: InvoiceCreationFromQuoteRejected) {
        messageCenterService.record(
            SystemMessageContent(
                severity = SystemMessageSeverity.ERROR,
                source = SystemMessageSource.INVOICE,
                messageKey = "message.center.invoice.fromQuoteRejected",
                args = listOf(event.quoteNumber, event.reason),
                resolvedText = "Angebot ${event.quoteNumber}: Rechnungsentwurf nicht erstellt: ${event.reason}",
            ),
        )
    }

    private fun onInvoiceLlmAnalysisResolved(event: InvoiceLlmAnalysisResolved) {
        val (severity, outcome) = when (event.status) {
            LlmAnalysisStatus.COMPLETED -> SystemMessageSeverity.INFO to "abgeschlossen"
            LlmAnalysisStatus.FAILED -> SystemMessageSeverity.WARNING to "fehlgeschlagen"
            // DISABLED/PENDING never reach here — InvoiceLlmAnalysisResolved is only ever fired
            // for the two terminal, model-reported outcomes above (see its own KDoc). Kept as a
            // defensive no-op rather than an exhaustiveness cast so a future firing site change
            // degrades silently instead of throwing out of this handler.
            else -> return
        }
        messageCenterService.record(
            SystemMessageContent(
                severity = severity,
                source = SystemMessageSource.VALIDATION,
                messageKey = "message.center.llmAnalysis.resolved",
                args = listOf(event.invoiceNumber, outcome),
                resolvedText = "Rechnung ${event.invoiceNumber}: LLM-Analyse $outcome",
                refType = REF_INVOICE,
                refId = event.invoiceId.toString(),
            ),
        )
    }
}
