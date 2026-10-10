package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.dto.B2bFindingDto
import de.littletrotter.application.dto.CompletenessCheckDto
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ModelReport
import de.littletrotter.application.dto.PartySnapshotDiffDto
import de.littletrotter.domain.exception.GenerationBlockReason

/**
 * Input port of the "read one invoice's detail view" use case: the invoice's own
 * projection, its compliance/completeness/LLM findings and the read-only action gates
 * (Storno/generation/Stammdaten-Refresh) the detail view and its dialogs need before a write.
 */
interface InvoiceDetailQueryPort {
    fun findInvoiceDetailDto(id: Long): InvoiceDetailDto?

    /** The B2B profile's missing-mandatory-field findings for [invoiceId]; empty when the
     *  invoice's seller has no B2B profile assigned, or the invoice doesn't exist. */
    fun findB2bComplianceFindings(invoiceId: Long): List<B2bFindingDto>

    /** The invoice's completeness checks, evaluated against the current, persisted state.
     *  Empty when the invoice doesn't exist. */
    fun findCompletenessChecks(invoiceId: Long): List<CompletenessCheckDto>

    /** LLM analyses for an invoice; `null`/absent [invoiceId] yields an empty list. */
    fun findLlmAnalyses(invoiceId: Long?): List<ModelReport>

    /** Gate for the "Stornieren" action — `false` (not an exception) whenever [invoiceId]
     *  cannot be found. */
    fun canCreateCreditNote(invoiceId: Long): Boolean

    /** Gate for the generation ("Drucken"/"E-Rechnung erzeugen") action. `null` whenever
     *  generation is currently allowed, including when [invoiceId] cannot be found. */
    fun generationBlockReason(invoiceId: Long): GenerationBlockReason?

    /** Read-only diff preview for "Stammdaten erneut übernehmen"; never writes. */
    fun previewPartySnapshotRefresh(invoiceId: Long): PartySnapshotDiffDto
}
