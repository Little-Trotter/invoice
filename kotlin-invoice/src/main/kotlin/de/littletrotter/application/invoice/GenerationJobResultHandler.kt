package de.littletrotter.application.invoice

import de.littletrotter.application.compliance.ComplianceFindingMapper
import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.application.shared.SetsAuditGuc
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.ComplianceRuleSets
import de.littletrotter.domain.compliance.evaluateCompliance
import de.littletrotter.domain.event.DocumentGenerated
import de.littletrotter.domain.exception.InvoiceStatusTransitionException
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.isIssued
import de.littletrotter.domain.invoice.resolveValidationStatusFromKositReport
import de.littletrotter.domain.message.SystemMessageContent
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.ValidationRun
import com.fasterxml.jackson.core.JsonProcessingException
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger

/**
 * GenerationJobService's completion + send-outcome-resolution responsibility, extracted
 * verbatim (see `markCompleted`/`resolveSendOutcome`'s original KDoc on `GenerationJobService`
 * for the L2/L24/L25/PO-2026-09-13 background this still relies on).
 */
@ApplicationScoped
class GenerationJobResultHandler @Inject constructor(
    private val generationJobRepository: GenerationJobRepository,
    private val eventDispatcher: GenerationJobEventDispatcher,
    private val b2bProfileRuleSet: B2bProfileRuleSet? = null,
    private val sendTransitionRunner: SendTransitionRunner? = null,
    private val messageCenterService: MessageCenterService? = null,
    private val time: TimeSource,
    // Real CDI always supplies it; null only for narrow unit tests that never complete a job with
    // artifacts.
    private val checksumPersister: DocumentChecksumPersister? = null,
) {
    private val log = Logger.getLogger(GenerationJobResultHandler::class.java)

    /**
     * Marks [job] COMPLETED and, in the same transaction, persists the checksum and object-key
     * assignment of every artifact it produced (`invoice_document`). A reader that sees the job
     * completed therefore always sees matching checksums — no window in which new bytes are
     * verified against an older checksum. A failure writing the checksums rolls the completion
     * back so the result message is redelivered.
     */
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.DOCUMENT_GENERATED)
    fun markCompleted(job: GenerationJob, resultRefs: String? = null, kositReport: String? = null, llmStatus: String? = null): GenerationJob {
        val managedJob = generationJobRepository.reloadManaged(job)
        val invoice = managedJob.invoice
        val issuedDocument = invoice != null && (invoice.status.isIssued || managedJob.trigger == GenerationJobTrigger.SEND || managedJob.trigger.isRecovery)
        // Same transaction as the COMPLETED status below (document-checksum mismatch spec): the
        // checksum rows / object-key assignment and the job's completion commit together, so a
        // download can never see completed bytes against a stale or missing checksum. Recorded
        // BEFORE the transition: a recorder failure propagates, @Transactional rolls everything
        // back, and the job is never marked COMPLETED (not even on the in-memory entity).
        if (issuedDocument) checksumPersister?.persist(invoice, resultRefs, managedJob.trigger, managedJob.id)
        managedJob.finishedAt = time.nowUtc()
        // reclaimAndTransitionTo, not transitionTo: a late-but-genuine COMPLETED result
        // for a job the app's own stale-DELEGATED reset already put back to PENDING out from under
        // this still-in-flight worker attempt must not be discarded as an illegal transition.
        managedJob.reclaimAndTransitionTo(GenerationJobStatus.COMPLETED)
        managedJob.resultRefs = resultRefs
        if (llmStatus != null && !isTerminalLlmStatus(managedJob.llmStatus)) managedJob.llmStatus = llmStatus
        // The first point in the pipeline that has the compliance rule sets, so the
        // worker's always-`skipped` BUSINESS placeholder is replaced with the real evaluation here.
        val effectiveReport = mergeBusinessStage(kositReport, managedJob.invoice)
        managedJob.kositReport = effectiveReport
        val saved = generationJobRepository.save(managedJob)
        val invoiceId = invoice?.id
        // L24 (fix-l24-draft-job-status-never-pushed) — the event always fires once there is an
        // invoice to report against; `issued` carries the former guard's condition onto the event
        // itself, so consumers that only need an issued/SEND document check it themselves.
        if (invoiceId != null) {
            eventDispatcher.fireAsyncAfterCommit(
                DocumentGenerated(
                    invoiceId = invoiceId,
                    invoiceNumber = invoice.invoiceNumber.value,
                    generationJobId = saved.id ?: -1,
                    resultRefs = resultRefs,
                    issued = issuedDocument,
                )
            )
        }
        return saved
    }

    /**
     * PO decision 2026-09-13 — "Recommended completion path" (trigger-driven, no poller): the
     * deterministic, synchronous continuation of a `SEND`-triggered job's own completion, called
     * by [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer] right after its
     * own `markCompleted`/`markFailed` call returns. Deliberately never rethrows — see this
     * method's own KDoc on `GenerationJobService` (moved here unchanged): an exception escaping
     * would retry the whole NATS message, but that retry's idempotency guard would then skip
     * straight past this call forever, permanently losing this SEND job's resolution.
     */
    @Transactional
    fun resolveSendOutcome(job: GenerationJob) {
        try {
            doResolveSendOutcome(job)
        } catch (ex: Exception) {
            // Sole catch-all of this flow: see this method's own KDoc above.
            log.error("Failed to resolve send outcome for GenerationJob ${job.id} (best effort, logged only): ${ex.message}", ex)
        }
    }

    private fun doResolveSendOutcome(job: GenerationJob) {
        val transitionRunner = requireNotNull(sendTransitionRunner) {
            "SendTransitionRunner not wired -- resolveSendOutcome requires it (real CDI always supplies it)"
        }
        val messageCenter = requireNotNull(messageCenterService) {
            "MessageCenterService not wired -- resolveSendOutcome requires it (real CDI always supplies it)"
        }
        val managedJob = generationJobRepository.reloadManaged(job)
        val invoice = managedJob.invoice ?: return
        val invoiceId = invoice.id ?: return

        if (managedJob.status != GenerationJobStatus.COMPLETED) {
            // The job itself never produced a document (FAILED) — no validation outcome exists.
            val reason = extractFailureReason(managedJob.resultRefs) ?: managedJob.lastError ?: "unbekannter Fehler"
            recordSendMessage(
                messageCenter,
                SendNotice(
                    messageKey = "message.center.send.generationFailed",
                    invoiceNumber = invoice.invoiceNumber.value,
                    invoiceId = invoiceId,
                    detail = reason,
                    resolvedText = "Rechnung ${invoice.invoiceNumber.value}: Sendevorgang abgebrochen — " +
                        "E-Rechnungs-Erzeugung fehlgeschlagen: $reason.",
                ),
            )
            return
        }

        when (resolveValidationStatusFromKositReport(managedJob.kositReport) ?: ValidationStatus.UNAVAILABLE) {
            ValidationStatus.VALID -> handleValidatedSend(transitionRunner, messageCenter, managedJob, invoice)
            ValidationStatus.INVALID, ValidationStatus.UNAVAILABLE, ValidationStatus.PENDING, ValidationStatus.NOT_VALIDATED -> {
                recordSendMessage(
                    messageCenter,
                    SendNotice(
                        messageKey = "message.center.send.validationFailed",
                        invoiceNumber = invoice.invoiceNumber.value,
                        invoiceId = invoiceId,
                        detail = null,
                        resolvedText = "Rechnung ${invoice.invoiceNumber.value}: Sendevorgang abgebrochen — " +
                            "E-Rechnungs-Prüfung nicht bestanden.",
                    ),
                )
            }
        }
    }

    /**
     * Split out of [doResolveSendOutcome] (LongMethod): the `VALID` branch, including its
     * own "gate throws after job COMPLETED" edge-case handling (see `resolveSendOutcome`'s
     * original KDoc on `GenerationJobService` for the full PO-decision background). [invoiceId]
     * is [invoice]'s own id (dropped as a separate parameter, LongParameterList).
     */
    private fun handleValidatedSend(
        transitionRunner: SendTransitionRunner,
        messageCenter: MessageCenterService,
        managedJob: GenerationJob,
        invoice: Invoice,
    ) {
        val invoiceId = requireNotNull(invoice.id) { "Invoice must have an id to resolve its SEND outcome" }
        if (!invoice.canTransitionTo(InvoiceStatus.SENT)) {
            logInvoiceMovedOn(managedJob, invoiceId, invoice.status)
            return
        }
        try {
            // Own transaction, lock first: this method's transaction already read the invoice, and a
            // concurrent version bump would make a locked re-read in that context fail.
            transitionRunner.transitionToSent(invoiceId)
        } catch (ex: InvoiceStatusTransitionException) {
            // A user transition won the row lock between the check above and this call. The job's
            // document is stored and stays attached to the invoice; there is nothing to recover.
            logInvoiceMovedOn(managedJob, invoiceId, null, ex)
        } catch (ex: Exception) {
            // Sole catch-all of this flow: the "gate throws after COMPLETED"
            // edge case this method's own KDoc documents; the job itself is already correctly
            // COMPLETED, only the invoice never reached SENT.
            log.error(
                "SEND job ${managedJob.id} completed and validated VALID, but transitioning invoice " +
                    "$invoiceId to SENT failed -- invoice remains ${invoice.status}, the SENT job's document " +
                    "is stored and further generation stays blocked until the invoice is sent or " +
                    "recovered manually. Cause: ${ex.message}",
                ex,
            )
            // The failed transition() call above already marked THIS method's own ambient
            // transaction rollback-only — a message-center write in that same transaction would
            // silently vanish at commit. requiringNew suspends it into a genuinely independent one.
            QuarkusTransaction.requiringNew().call {
                recordSendMessage(
                    messageCenter,
                    SendNotice(
                        messageKey = "message.center.send.gateFailedAfterCompletion",
                        invoiceNumber = invoice.invoiceNumber.value,
                        invoiceId = invoiceId,
                        detail = ex.message ?: ex.javaClass.simpleName,
                        resolvedText = "Rechnung ${invoice.invoiceNumber.value}: Sendevorgang nach abgeschlossener " +
                            "E-Rechnungs-Erzeugung fehlgeschlagen (${ex.message ?: ex.javaClass.simpleName}). " +
                            "Die Rechnung bleibt gesperrt -- manuelle Prüfung erforderlich.",
                    ),
                )
            }
        }
    }

    /** The invoice left the state a send can start from (voided, sent back to draft, already
     *  sent) while its SEND job ran: an expected race outcome, not a failure. [status] is `null`
     *  when only the failed transition itself is known. */
    private fun logInvoiceMovedOn(job: GenerationJob, invoiceId: Long, status: InvoiceStatus?, cause: Exception? = null) {
        log.warn(
            "SEND job ${job.id} completed for invoice $invoiceId, but the invoice is no longer sendable " +
                "(status=${status ?: "changed concurrently"}); status left unchanged, document kept." +
                (cause?.message?.let { " Cause: $it" } ?: ""),
        )
    }

    /** PO decision 2026-09-13 — [doResolveSendOutcome]'s own read of a FAILED job's worker-
     *  reported message, mirroring `markFailed`'s own wrapped-JSON write. `null` on a blank/
     *  unparseable/keyless `resultRefs` rather than throwing. */
    private fun extractFailureReason(resultRefs: String?): String? =
        resultRefs?.let {
            try {
                GenerationJobResultRefsCodec.MAPPER.readValue(it, GenerationJobResultRefsCodec.MAP_TYPE)["error"]
            } catch (e: JsonProcessingException) {
                log.warn("Job resultRefs is not a String map, cannot extract a send-failure reason from it: ${e.message}", e)
                null
            }
        }

    /** PO decision 2026-09-13 — the message-center notice every branch of [resolveSendOutcome]/
     *  [handleValidatedSend] records; [detail] `null` omits the second `{1}` arg. Bundled
     *  into one value class so [recordSendMessage] stays under LongParameterList's threshold. */
    private data class SendNotice(
        val messageKey: String,
        val invoiceNumber: String,
        val invoiceId: Long,
        val detail: String?,
        val resolvedText: String,
    )

    private fun recordSendMessage(messageCenter: MessageCenterService, notice: SendNotice) {
        messageCenter.record(
            SystemMessageContent(
                severity = SystemMessageSeverity.ERROR,
                source = SystemMessageSource.GENERATION,
                messageKey = notice.messageKey,
                args = listOfNotNull(notice.invoiceNumber, notice.detail),
                resolvedText = notice.resolvedText,
                refType = "INVOICE",
                refId = notice.invoiceId.toString(),
            ),
        )
    }

    /**
     * Replaces the worker's always-`skipped`
     * `BUSINESS` [de.littletrotter.shared.ValidationStageResult] with the real compliance
     * evaluation, using exactly the rule sets [FinalizationPolicy] gates finalization on
     * ([ComplianceRuleSets.DEFAULT] plus [b2bProfileRuleSet] when wired).
     *
     * Two cases pass [rawReport] through unchanged: not decodable, or decodable but with no
     * `validationRun` (a legacy report). A compliance-evaluation failure falls back to [rawReport]
     * on any [Exception] rather than propagating — a document was already generated.
     */
    private fun mergeBusinessStage(rawReport: String?, invoice: Invoice?): String? {
        if (invoice == null) return rawReport
        val payload = KositReportPayload.decode(rawReport) ?: return rawReport
        val run = payload.validationRun ?: return rawReport
        return try {
            val ruleSets = ComplianceRuleSets.DEFAULT + listOfNotNull(b2bProfileRuleSet)
            val businessStage = ComplianceFindingMapper.toValidationStageResult(invoice.evaluateCompliance(time.today(), ruleSets))
            // Replace the worker's skipped placeholder — or append when a run carries no
            // BUSINESS entry at all (a worker predating that placeholder).
            val newStages = if (run.stages.any { it.stage == "BUSINESS" }) {
                run.stages.map { if (it.stage == "BUSINESS") businessStage else it }
            } else {
                run.stages + businessStage
            }
            val newOverall = ValidationRun.overallOutcomeOf(newStages)
            val newResult = KositValidationResult(
                status = newOverall,
                // Same "sum of all non-skipped stages' findings" shape PdfExportServiceImpl
                // (kotlin-xpdf) itself builds the flat summary from.
                summary = newStages.filterNot { it.skipped }
                    .sumOf { it.findings.size }
                    .let { count -> if (count == 0) null else "$count validation finding(s) across ${newStages.count { !it.skipped }} stage(s)" },
                details = newStages.flatMap { it.findings },
                validatorVersion = payload.validatorVersion,
                generatorVersion = payload.generatorVersion,
                profile = payload.profile,
                checkedAt = payload.checkedAt,
                validationRun = run.copy(stages = newStages, overall = newOverall),
            )
            KositReportPayload.encode(newResult)
        } catch (e: Exception) {
            // AP-EXC-1: kept broad on purpose. Rule sets can call out to repositories
            // (b2bProfileRuleSet); any unexpected failure here must not fail job completion.
            // L25 — logs at ERROR, not warn: the BUSINESS stage being silently dropped is a
            // compliance-relevant fact for this invoice, not a routine hiccup.
            log.error("BUSINESS compliance stage dropped for invoice ${invoice.id}: failed to merge into report (falling back to the worker's raw report): ${e.message}", e)
            rawReport
        }
    }
}
