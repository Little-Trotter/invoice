package de.littletrotter.application.mail

import de.littletrotter.application.mail.port.inbound.MailSendGate
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus

private val IN_FLIGHT = setOf(GenerationJobStatus.PENDING, GenerationJobStatus.DELEGATED, GenerationJobStatus.RUNNING)

/** The plain facts the send gate decides on; collected by the caller so the decision does no I/O. */
data class MailSendFacts(
    val status: InvoiceStatus,
    val validationStatus: ValidationStatus,
    val jobs: List<GenerationJob>,
    val hasAttachableDocument: Boolean,
    val currentPdfReproduced: Boolean,
    val recipientEmail: String?,
)

/**
 * The single decision whether an invoice may be mailed; the first violated condition wins, in the
 * declaration order of [MailSendGate]. Pure on purpose: the offer in the UI and the handover call
 * the same function with the same facts, so they cannot disagree.
 *
 * A `PENDING` validation (still running, e.g. after a re-print) is reported as its own reason
 * instead of a failed check. An `UNAVAILABLE` validation passes: an invoice can only have reached `ISSUED` through the logged
 * finalisation override in that case, and the issued check runs before the validation check.
 */
fun mailSendGateOf(facts: MailSendFacts): MailSendGate = with(facts) {
    when {
        jobs.any { it.trigger == GenerationJobTrigger.SEND && it.status in IN_FLIGHT } -> MailSendGate.JOB_IN_FLIGHT
        status != InvoiceStatus.ISSUED -> MailSendGate.NOT_ISSUED
        validationStatus == ValidationStatus.PENDING -> MailSendGate.VALIDATION_PENDING
        validationStatus != ValidationStatus.VALID && validationStatus != ValidationStatus.UNAVAILABLE -> MailSendGate.NOT_VALID
        !hasAttachableDocument -> MailSendGate.DOCUMENT_MISSING
        currentPdfReproduced -> MailSendGate.REPRODUCED
        recipientEmail.isNullOrBlank() -> MailSendGate.NO_RECIPIENT_EMAIL
        else -> MailSendGate.READY
    }
}
