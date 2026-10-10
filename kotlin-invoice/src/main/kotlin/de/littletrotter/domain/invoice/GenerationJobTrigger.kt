package de.littletrotter.domain.invoice

/**
 * D4 (PO decision 2026-09-13) — why a [GenerationJob] exists, not merely what state it is in:
 * [MANUAL] is a user-initiated export/print request
 * ([de.littletrotter.ui.view.invoice.InvoiceExportPanel]'s print button, and the System-tab
 * backfill sweep's own jobs — [de.littletrotter.infrastructure.persistence
 * .PostgresDatabaseMaintenanceService.performQueueIssuedGenerationJobs] — until the PO decides
 * to distinguish [MAINTENANCE] there), [MAINTENANCE] is reserved for that same backfill sweep
 * once distinguished, and [SEND] is the "Senden" action itself
 * ([de.littletrotter.application.invoice.InvoiceSendService.requestSend]) — the one trigger that
 * always becomes the invoice's issued document regardless of the invoice's own status at
 * job-creation time (see [GenerationJob.trigger]'s own KDoc).
 * [REGENERATION] is a document regenerated because the original was lost and the invoice was never sent.
 * [REPRODUCTION] is a re-render of a sent invoice whose original cannot be restored; the result is tagged as a reproduction and never sent.
 *
 * Smallint-backed via [de.littletrotter.domain.invoice.converters.GenerationJobTriggerConverter],
 * same ordinal-persistence pattern as [GenerationJobStatus]'s own converter — ordinals are
 * append-only from here on.
 */
enum class GenerationJobTrigger {
    MANUAL,
    SEND,
    MAINTENANCE,
    REGENERATION,
    REPRODUCTION,
    ;

    /** True for the two triggers that re-create the bytes of an already issued document. */
    val isRecovery: Boolean get() = this == REGENERATION || this == REPRODUCTION
}
