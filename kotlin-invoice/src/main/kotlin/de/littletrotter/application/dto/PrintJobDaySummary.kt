package de.littletrotter.application.dto

import java.time.LocalDate

/**
 * One calendar day's row in the global "Druckaufträge" TreeGrid: how many jobs were
 * created that day (matching the active [GenerationJobFilter]) and how many of them ended FAILED.
 * Mirrors [de.littletrotter.domain.repository.GenerationJobDaySummary] one-to-one; kept as its own
 * application-layer type for the same reason [PrintJobOverviewRow] mirrors the job entity — the UI
 * must never depend on a domain-layer repository return type directly
 * (`ArchitectureRulesTest`).
 */
data class PrintJobDaySummary(
    val day: LocalDate,
    val jobCount: Long,
    val failedCount: Long,
)
