package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.application.dto.B2bFindingDto
import de.littletrotter.application.dto.CompletenessCheckDto
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ModelReport
import de.littletrotter.application.dto.PartySnapshotDiffDto
import de.littletrotter.application.dto.CancellationRelationDto
import de.littletrotter.application.dto.CancellationRole
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.outbound.InvoiceLlmAnalysisReadRepository
import de.littletrotter.application.invoice.port.outbound.InvoiceReadRepository
import de.littletrotter.domain.compliance.CoreCompletenessRules
import de.littletrotter.domain.exception.GenerationBlockReason
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.invoice.isIssued
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/** Implements [InvoiceDetailQueryPort] on top of [InvoiceReadRepository]'s own detail
 *  projection plus the compliance/completeness/LLM/action-gate reads the detail view needs. */
@ApplicationScoped
class InvoiceDetailQueries @Inject constructor(
    private val invoiceReadRepository: InvoiceReadRepository,
    private val invoiceLifecycleService: InvoiceLifecycleService,
    private val invoiceCancellationService: InvoiceCancellationService,
    private val generationJobRepository: GenerationJobRepository,
    private val llmAnalysisReadRepository: InvoiceLlmAnalysisReadRepository,
    private val invoiceRepository: InvoiceRepository,
    private val invoiceLookup: InvoiceLookupRepository,
    // Nullable like InvoiceLifecycleService's own reference to it: absent whenever no B2B
    // profile ruleset is configured, which UI display simply treats as "no findings".
    private val b2bProfileRuleSet: B2bProfileRuleSet? = null,
) : InvoiceDetailQueryPort {

    override fun findInvoiceDetailDto(id: Long): InvoiceDetailDto? = invoiceReadRepository.findInvoiceDetailDto(id)

    // The @Transactional reads below load the Invoice entity: outside a transaction Quarkus keeps one
    // persistence session per request, and the poll tick + queued push update share one request context,
    // so a repeat findById would return the entity cached by the first read (still REVIEW) while the
    // status badge's plain column query shows ISSUED ("Stornieren" hidden until reload). A transaction
    // gets its own session -> current row, with no new repository API or manual refresh/clear.
    @Transactional
    override fun findB2bComplianceFindings(invoiceId: Long): List<B2bFindingDto> {
        val ruleSet = b2bProfileRuleSet ?: return emptyList()
        val invoice = invoiceLifecycleService.findById(invoiceId) ?: return emptyList()
        return ruleSet.evaluate(invoice).map { it.toB2bFindingDto() }
    }

    @Transactional
    override fun findCompletenessChecks(invoiceId: Long): List<CompletenessCheckDto> {
        val invoice = invoiceLifecycleService.findById(invoiceId) ?: return emptyList()
        return CoreCompletenessRules.checks(invoice).map { it.toCompletenessCheckDto() }
    }

    override fun findLlmAnalyses(invoiceId: Long?): List<ModelReport> =
        invoiceId?.let { llmAnalysisReadRepository.findReports(it) } ?: emptyList()

    // PO order 2026-08-31 — a missing invoice is "not stornierbar", not an error the UI gate
    // needs to distinguish; the button simply stays hidden, same as every other rejection case.
    @Transactional
    override fun canCreateCreditNote(invoiceId: Long): Boolean {
        val invoice = invoiceLifecycleService.findById(invoiceId) ?: return false
        return invoiceCancellationService.canCreateCreditNote(invoice)
    }

    // Credit note -> original via originalInvoiceNumber; original -> first credit note via the
    // reverse lookup. Null when the invoice is in neither role or the counterpart is not persisted.
    @Transactional
    override fun findCancellationRelation(invoiceId: Long): CancellationRelationDto? {
        val invoice = invoiceLifecycleService.findById(invoiceId) ?: return null
        val originalNumber = invoice.originalInvoiceNumber?.value
        return when {
            invoice.typeCode == InvoiceTypeCode.CORRECTED_INVOICE && originalNumber != null ->
                invoiceRepository.findByInvoiceNumber(originalNumber)?.toRelation(CancellationRole.ORIGINAL)
            invoice.invoiceNumber.isAssigned() ->
                invoiceLookup.findByOriginalInvoiceNumber(invoice.invoiceNumber.value).firstOrNull()?.toRelation(CancellationRole.CREDIT_NOTE)
            else -> null
        }
    }

    private fun Invoice.toRelation(role: CancellationRole): CancellationRelationDto? =
        id?.let { CancellationRelationDto(it, invoiceNumber.value, role) }

    // Mirrors GenerationJobService.createJob's own guard exactly (VOIDED first, then
    // the completed-job check restricted to isIssued statuses) without ever calling createJob
    // itself — a missing invoice is simply "not blocked", the same "hide, don't explain" stance
    // canCreateCreditNote above already takes for its own not-found case.
    @Transactional
    override fun generationBlockReason(invoiceId: Long): GenerationBlockReason? {
        val invoice = invoiceLifecycleService.findById(invoiceId) ?: return null
        return when {
            invoice.status == InvoiceStatus.VOIDED -> GenerationBlockReason.VOIDED
            invoice.status.isIssued && generationJobRepository.hasCompletedIssuedJob(invoiceId) -> GenerationBlockReason.IMMUTABLE
            else -> null
        }
    }

    override fun previewPartySnapshotRefresh(invoiceId: Long): PartySnapshotDiffDto =
        invoiceLifecycleService.previewPartySnapshotRefresh(invoiceId).toDiffDto()
}
