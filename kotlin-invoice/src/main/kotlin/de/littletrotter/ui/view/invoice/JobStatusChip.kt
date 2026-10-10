package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.ui.component.ChipTone

/** Tone of a generation job's status chip: waiting is neutral, in flight is accent, done is ok. */
internal fun generationJobStatusTone(status: GenerationJobStatus): ChipTone = when (status) {
    GenerationJobStatus.PENDING -> ChipTone.NEUTRAL
    GenerationJobStatus.DELEGATED -> ChipTone.ACCENT
    GenerationJobStatus.RUNNING -> ChipTone.ACCENT
    GenerationJobStatus.COMPLETED -> ChipTone.OK
    GenerationJobStatus.FAILED -> ChipTone.ERROR
}

/** Symbol of a generation job's status, one silhouette per state so the column reads without colour:
 *  waiting (hourglass), handed on (share), working (cog), done (check), failed (exclamation). */
internal fun generationJobStatusGlyphIcon(status: GenerationJobStatus): VaadinIcon = when (status) {
    GenerationJobStatus.PENDING -> VaadinIcon.HOURGLASS
    GenerationJobStatus.DELEGATED -> VaadinIcon.SHARE
    GenerationJobStatus.RUNNING -> VaadinIcon.COG
    GenerationJobStatus.COMPLETED -> VaadinIcon.CHECK
    GenerationJobStatus.FAILED -> VaadinIcon.EXCLAMATION_CIRCLE
}

/** Symbol of a single job's validation outcome; the same silhouettes as the invoice list's validation column. */
internal fun validationOutcomeGlyphIcon(outcome: ValidationOutcome): VaadinIcon = when (outcome) {
    is ValidationOutcome.Successful -> VaadinIcon.CHECK_CIRCLE
    is ValidationOutcome.NotSuccessful -> VaadinIcon.CLOSE_CIRCLE
    is ValidationOutcome.Pending -> VaadinIcon.CLOCK
}

/** Tone of the outcome chip of a single job's validation: the outward result is binary
 *  (valid or not), plus the pending state while a check is genuinely running. */
internal fun validationOutcomeTone(job: GenerationJob): ChipTone = when (resolveValidationOutcome(job)) {
    is ValidationOutcome.Successful -> ChipTone.OK
    is ValidationOutcome.NotSuccessful -> ChipTone.ERROR
    is ValidationOutcome.Pending -> ChipTone.ACCENT
}

/** Tone of an invoice's own [ValidationStatus] chip; shared by the list cell, the legend and the
 *  cockpit so every chip agrees on one face per status. "Not checkable" is a warning, not a verdict. */
internal fun validationStatusTone(status: ValidationStatus): ChipTone = when (status) {
    ValidationStatus.VALID -> ChipTone.OK
    ValidationStatus.INVALID -> ChipTone.ERROR
    ValidationStatus.UNAVAILABLE -> ChipTone.WARN
    ValidationStatus.NOT_VALIDATED -> ChipTone.NEUTRAL
    ValidationStatus.PENDING -> ChipTone.ACCENT
}

/** Symbol of an invoice's [ValidationStatus] in the list overview: one silhouette per status so the column reads without colour
 *  (check = valid, cross = invalid, warning = not checkable, dash-circle = not yet validated, clock = check running). */
internal fun validationStatusGlyphIcon(status: ValidationStatus): VaadinIcon = when (status) {
    ValidationStatus.VALID -> VaadinIcon.CHECK_CIRCLE
    ValidationStatus.INVALID -> VaadinIcon.CLOSE_CIRCLE
    ValidationStatus.UNAVAILABLE -> VaadinIcon.WARNING
    ValidationStatus.NOT_VALIDATED -> VaadinIcon.CIRCLE_THIN
    ValidationStatus.PENDING -> VaadinIcon.CLOCK
}

/** Tone of an [InvoiceStatus] chip. The quote status mapping follows the same semantics:
 *  draft neutral, in review review, sent accent, cancelled storno; a voided invoice never left the
 *  house and reads as an error. */
internal fun invoiceStatusTone(status: InvoiceStatus): ChipTone = when (status) {
    InvoiceStatus.DRAFT -> ChipTone.NEUTRAL
    InvoiceStatus.IN_REVIEW -> ChipTone.REVIEW
    InvoiceStatus.SENT -> ChipTone.ACCENT
    InvoiceStatus.VOIDED -> ChipTone.ERROR
    InvoiceStatus.CANCELLED -> ChipTone.STORNO
}

/** [InvoiceStatus]'s own glyph icon for the quick-filter chip: one distinct silhouette per status so a colour-blind user or
 *  a greyscale printout still tells them apart — DRAFT is being written (pencil), IN_REVIEW is
 *  being watched (eye), SENT departs (paper plane), VOIDED is blocked (ban), CANCELLED (Storno)
 *  reverses an already-sent one (return arrow). */
internal fun invoiceStatusGlyphIcon(status: InvoiceStatus): VaadinIcon = when (status) {
    InvoiceStatus.DRAFT -> VaadinIcon.EDIT
    InvoiceStatus.IN_REVIEW -> VaadinIcon.EYE
    InvoiceStatus.SENT -> VaadinIcon.PAPERPLANE
    InvoiceStatus.VOIDED -> VaadinIcon.BAN
    InvoiceStatus.CANCELLED -> VaadinIcon.ROTATE_LEFT
}

/** [ValidationStatus]'s own display label, shared by the invoice-list
 *  grid's cell tile, its filter combo, its export scope summary and its column-header legend
 *  ([de.littletrotter.ui.view.invoice.openValidationLegend]) so all four surfaces agree on the
 *  exact wording — moved out of [de.littletrotter.ui.view.invoice.InvoiceListView] so the legend
 *  builder (a separate file) does not need a private method of that class to render its rows. */
internal fun validationStatusLabel(status: ValidationStatus, tr: (String) -> String): String = when (status) {
    ValidationStatus.VALID -> tr("invoice.validation.valid")
    ValidationStatus.INVALID -> tr("invoice.validation.invalid")
    ValidationStatus.UNAVAILABLE -> tr("invoice.validation.unavailable")
    ValidationStatus.NOT_VALIDATED -> tr("invoice.validation.notValidated")
    ValidationStatus.PENDING -> tr("invoice.validation.pending")
}

/** See [validationStatusLabel]'s KDoc; the same split this codebase already
 *  uses everywhere a chip/tile shows a short label plus a longer explanatory `title`. */
internal fun validationStatusTooltip(status: ValidationStatus, tr: (String) -> String): String = when (status) {
    ValidationStatus.UNAVAILABLE -> tr("invoice.validation.unavailable.tooltip")
    ValidationStatus.PENDING -> tr("invoice.validation.pending.tooltip")
    else -> tr("invoiceDetail.jobs.kosit.status.tooltip")
}
