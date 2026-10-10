package de.littletrotter.application.reference

import de.littletrotter.domain.compliance.NumberJournalChain
import de.littletrotter.domain.compliance.NumberJournalEntities
import de.littletrotter.domain.compliance.NumberJournalEvent
import de.littletrotter.domain.compliance.NumberJournalReport
import de.littletrotter.domain.compliance.buildReport
import de.littletrotter.domain.compliance.reconcileAgainstInvoices
import de.littletrotter.domain.reference.Continuity
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.NumberJournalRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * task13-N4 — the combined audit report: journal self-check ([buildReport]) plus, for the
 * `INVOICE` range, reconciliation against the actual set of invoices
 * ([de.littletrotter.domain.compliance.reconcileAgainstInvoices]). Together these answer the
 * question the user actually wants: "is this sequence internally consistent, and does it match
 * what was actually booked?"
 */
@ApplicationScoped
class NumberJournalReportService @Inject constructor(
    private val ranges: NumberRangeRepository,
    private val journal: NumberJournalRepository,
    private val invoiceRepo: InvoiceRepository,
) {
    /** `null` if [subject] has no active range or its range is not `GAPLESS` — a report without
     *  a journal would be an empty claim, not evidence.
     *
     *  [externalSeals] is, per `periodKey`, the chain head kept outside the database (N4 §4) —
     *  supplied by the caller, e.g. from whatever was sent to the tax advisor at the time;
     *  without it, [de.littletrotter.domain.compliance.NumberJournalFinding.SealMismatch] cannot
     *  be checked. */
    fun reportFor(subject: NumberRangeSubject, externalSeals: Map<String, String> = emptyMap()): NumberJournalReport? {
        // The counter and journal of quote numbers live in the quote module.
        if (subject == NumberRangeSubject.QUOTE) return null
        val range = ranges.findActive(subject) ?: return null
        if (range.continuity != Continuity.GAPLESS) return null
        val rangeId = range.id ?: return null

        val entries = journal.findAll(rangeId)
        val genesis = NumberJournalChain.genesisHash(range.code, range.startValue)

        val reconciliation = if (subject == NumberRangeSubject.INVOICE) {
            // Entries without a numberSequence are skipped rather than fed into the
            // reconciliation with a placeholder (-1): a made-up number looks like a real one
            // there and produces a finding that points at no actual number (IMP-2).
            val journalAssignments = entries
                .filter { it.event == NumberJournalEvent.ALLOCATED && it.entityType == NumberJournalEntities.INVOICE }
                .mapNotNull { entry -> entry.numberSequence?.let { entry.entityId to it } }
            val invoiceAssignments = invoiceRepo.findFinalizedInvoiceNumberAssignments()
                .map { it.invoiceId to it.sequence }
            reconcileAgainstInvoices(journalAssignments, invoiceAssignments)
        } else {
            emptyList()
        }

        return buildReport(rangeId, entries, genesis, reconciliation, externalSeals, range.startValue)
    }
}
