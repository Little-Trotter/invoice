package de.littletrotter.domain.compliance

/**
 * The check [buildReport] alone can't do: [buildReport] only verifies the journal *internally*
 * — consistent, unbroken chain — but is blind to a number written straight into the `invoice`
 * table without ever producing a journal entry. Only a reconciliation against the actual invoice
 * population catches that.
 *
 * Pure set difference on `entityId` — DB-free and therefore testable without a Testcontainer;
 * the caller supplies both input lists (journal repository, resp.
 * [de.littletrotter.domain.repository.InvoiceListingRepository.findFinalizedInvoiceNumberAssignments]).
 *
 * Findings share [NumberJournalFinding] with [buildReport] — the audit report is **one**
 * deviation list, not two.
 *
 * @param journalAssignments `(entityId, numberSequence)` from a range's `ALLOCATED` entries —
 *   the caller has already filtered to `entityType == "Invoice"`.
 * @param invoiceAssignments `(invoiceId, invoiceNumberSequence)` of all `ISSUED` invoices with a
 *   number set, from [de.littletrotter.domain.repository.InvoiceRepository
 *   .findFinalizedInvoiceNumberAssignments].
 */
fun reconcileAgainstInvoices(
    journalAssignments: List<Pair<Long?, Long>>,
    invoiceAssignments: List<Pair<Long, Long>>,
): List<NumberJournalFinding> {
    // groupBy, not associateBy: with two journal entries for the same invoice, associateBy
    // would silently drop the first — and "one invoice pulled two numbers" is exactly a finding,
    // not a detail to discard.
    val journalByEntity = journalAssignments.filter { it.first != null }.groupBy { it.first }
    val invoiceById = invoiceAssignments.associateBy { it.first }

    val findings = mutableListOf<NumberJournalFinding>()

    journalByEntity.forEach { (entityId, forEntity) ->
        if (forEntity.size > 1) {
            findings += NumberJournalFinding.MultipleJournalEntriesForInvoice(
                entityId, forEntity.map { it.second }.sorted(),
            )
        }
    }

    invoiceAssignments.forEach { (invoiceId, sequence) ->
        val inJournal = journalByEntity[invoiceId].orEmpty()
        if (inJournal.none { it.second == sequence }) {
            findings += NumberJournalFinding.InvoiceWithoutJournalEntry(invoiceId, sequence)
        }
    }

    journalAssignments.forEach { (entityId, sequence) ->
        val matches = entityId != null && invoiceById[entityId]?.second == sequence
        if (!matches) {
            findings += NumberJournalFinding.JournalEntryWithoutInvoice(entityId, sequence)
        }
    }

    return findings
}
