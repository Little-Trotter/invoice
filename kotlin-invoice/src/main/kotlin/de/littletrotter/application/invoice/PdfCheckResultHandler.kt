package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.inbound.PdfCheckVerdict
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.resolveValidationStatusFromKositReport
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.ValidationStageResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger

/**
 * Appends the veraPDF verdict to the stored validation report of a document, replacing the
 * pending stage in place. One short transaction: the job row is locked while the verdict is written,
 * so a send decision on the same job waits for it and sees the verdict; the verdict is committed
 * when [append] returns.
 * Applying the same verdict twice leaves the same report, so a redelivered result is harmless.
 */
@ApplicationScoped
class PdfCheckResultHandler @Inject constructor(
    private val generationJobRepository: GenerationJobRepository,
) {
    private val log = Logger.getLogger(PdfCheckResultHandler::class.java)

    /** What [append] did, and whether a send that waited for the verdict can go on. */
    data class Appended(val job: GenerationJob, val resumeSend: Boolean)

    @Transactional
    fun append(verdict: PdfCheckVerdict): Appended? {
        val job = generationJobRepository.findByIdForUpdate(verdict.jobId)
        if (job == null) {
            log.warnf("veraPDF verdict for unknown generation job %d dropped", verdict.jobId)
            return null
        }
        val report = KositReportPayload.decode(job.kositReport)?.withStage(stageOf(verdict))
        if (report == null) {
            log.warnf("Generation job %d has no stage breakdown to append the veraPDF verdict to", verdict.jobId)
            return null
        }
        val run = checkNotNull(report.validationRun) { "withStage returns a report with a run" }
        job.replaceKositReport(
            report.copy(validationRun = run.copy(validatorVersions = run.validatorVersions + ("verapdf" to verdict.validator))).toJson(),
        )
        generationJobRepository.save(job)
        return Appended(job, resumeSend = waitsForVerdict(job))
    }

    // Only a send that finished generating with a valid document and has not moved on can continue;
    // everything else was already resolved (and reported to the user) when the job completed.
    private fun waitsForVerdict(job: GenerationJob): Boolean =
        job.trigger == GenerationJobTrigger.SEND &&
            job.status == GenerationJobStatus.COMPLETED &&
            resolveValidationStatusFromKositReport(job.kositReport) == ValidationStatus.VALID &&
            job.invoice?.status == InvoiceStatus.IN_REVIEW

    private fun stageOf(verdict: PdfCheckVerdict): ValidationStageResult {
        // Findings of this stage warn; they never decide whether the invoice may be sent.
        val findings = verdict.findings.map { it.copy(severity = "WARNING", stage = ValidationStageResult.VERAPDF) }
        return ValidationStageResult(
            stage = ValidationStageResult.VERAPDF,
            outcome = verdict.outcome,
            findings = findings,
            engineError = if (verdict.outcome == KositOutcome.ERROR) verdict.engineError ?: UNKNOWN_ENGINE_ERROR else null,
            advisory = true,
        )
    }

    private companion object {
        const val UNKNOWN_ENGINE_ERROR = "unbekannter Fehler"
    }
}
