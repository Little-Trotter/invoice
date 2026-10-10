package de.littletrotter.domain.invoice

/**
 * Queue lane a [GenerationJob] is published on. [INTERACTIVE] jobs are ones a user in the UI
 * waits for (send, print button, credit note issued) and must never queue behind [BULK] work
 * (imports, regeneration, reproduction, sweeps, admin maintenance).
 *
 * A separate value rather than a [GenerationJobTrigger]: the MANUAL trigger covers both the UI
 * print button and the scheduled and admin sweeps, so the trigger cannot tell the lanes apart.
 *
 * Smallint-backed via [de.littletrotter.domain.invoice.converters.DocumentJobLaneConverter];
 * ordinals are append-only (0 = BULK, 1 = INTERACTIVE).
 */
enum class DocumentJobLane {
    BULK,
    INTERACTIVE,
}
