package de.littletrotter.application.dto

import de.littletrotter.domain.invoice.GenerationJobStatus
import java.time.OffsetDateTime

/**
 * Filter for the global "Druckaufträge" view (every print run across every invoice).
 * Every field is optional; an unset one applies no restriction. [de.littletrotter.application
 * .invoice.PrintJobOverviewQueryServiceImpl] unpacks this into the plain parameters
 * [de.littletrotter.domain.repository.GenerationJobRepository.findPaged]/[de.littletrotter.domain
 * .repository.GenerationJobRepository.countFiltered] take — the domain port itself must not
 * depend on this application-layer type (`ArchitectureRulesTest`).
 */
data class GenerationJobFilter(
    /** Empty = no status restriction (matches any [GenerationJobStatus]). */
    val statuses: Set<GenerationJobStatus> = emptySet(),
    /** [de.littletrotter.domain.masterdata.Recipient.id] of the invoice's billing recipient. */
    val recipientId: Long? = null,
    /** Inclusive lower bound on [de.littletrotter.domain.invoice.GenerationJob.createdAt]. */
    val createdFrom: OffsetDateTime? = null,
    /** Inclusive upper bound on [de.littletrotter.domain.invoice.GenerationJob.createdAt]. */
    val createdTo: OffsetDateTime? = null,
)
