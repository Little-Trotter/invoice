package de.littletrotter.domain.compliance

/** A business-level audit finding, distinct from [ChainVerification]'s hash-chain check: this is
 *  about whether the sequence is actually gapless. */
sealed interface NumberJournalFinding {
    /** A number in the expected range (`min..max`) is missing from the journal — should never
     *  happen for a GAPLESS range. */
    data class Gap(val missingSequence: Long) : NumberJournalFinding

    data class Duplicate(val sequence: Long, val count: Int) : NumberJournalFinding

    /** An `ISSUED` invoice carries a number with no matching journal entry — it was set bypassing
     *  the allocation service. Found only by [reconcileAgainstInvoices], not [buildReport] alone. */
    data class InvoiceWithoutJournalEntry(val invoiceId: Long, val sequence: Long) : NumberJournalFinding

    /** A journal entry names an invoice that no longer exists as `ISSUED` with that number — the
     *  invoice was removed or changed outside the application. */
    data class JournalEntryWithoutInvoice(val invoiceId: Long?, val sequence: Long) : NumberJournalFinding

    data class MultipleJournalEntriesForInvoice(val invoiceId: Long?, val sequences: List<Long>) :
        NumberJournalFinding

    /** The chain head recorded at seal time ([sealedChainHead], the `PERIOD_SEALED` entry's
     *  `prevHash`) doesn't match the value kept outside the database ([externalChainHead]).
     *  Checkable only when the caller supplies that external value. */
    data class SealMismatch(val periodKey: String, val sealedChainHead: String, val externalChainHead: String) :
        NumberJournalFinding
}

/** Result of auditing **one** number range; `isClean` is the line that matters. */
data class NumberJournalReport(
    val numberRangeId: Long,
    val entryCount: Int,
    val firstSequence: Long?,
    val lastSequence: Long?,
    val chainVerification: ChainVerification,
    val findings: List<NumberJournalFinding>,
) {
    val isClean: Boolean get() = chainVerification is ChainVerification.Valid && findings.isEmpty()
}

/**
 * Pure evaluation, DB-free; expects [entries] already sorted by `seq`. Completeness is checked
 * on [NumberJournalEntry.numberSequence] as a number, not the formatted number's text pattern.
 *
 * [reconciliationFindings], if present, come from [reconcileAgainstInvoices] — this function has
 * no invoice access, so the caller computes them and passes them in to merge into one combined
 * finding list.
 *
 * [externalSeals] holds the externally-kept chain head per `periodKey`; where absent,
 * [NumberJournalFinding.SealMismatch] is simply left unchecked rather than falsely reported clean.
 *
 * [startValue] is the range's lower bound for gaplessness. Without it, the check starts at the
 * smallest number *present*, so missing leading numbers of a period stay invisible. `null`
 * disables the bound — for callers that don't know the range, or a takeover journal that
 * deliberately starts above it.
 */
fun buildReport(
    numberRangeId: Long,
    entries: List<NumberJournalEntry>,
    genesisHash: String,
    reconciliationFindings: List<NumberJournalFinding> = emptyList(),
    externalSeals: Map<String, String> = emptyMap(),
    startValue: Long? = null,
): NumberJournalReport {
    val allocations = entries.filter { it.event == NumberJournalEvent.ALLOCATED }
    val sequences = allocations.mapNotNull { it.numberSequence }

    val findings = mutableListOf<NumberJournalFinding>()
    // Checked per period, not across the whole range history: a YEARLY reset restarts the
    // sequence at the range's start value each year. Without this split, every other allocation
    // right after a period change would look like a Duplicate().
    allocations.groupBy { it.periodKey }.toSortedMap().forEach { (_, periodAllocations) ->
        val periodSequences = periodAllocations.mapNotNull { it.numberSequence }
        if (periodSequences.isEmpty()) return@forEach
        // The lower bound applies per period, not just the first — via minOf with the smallest
        // number present, so a journal that starts below startValue doesn't suddenly report
        // gaps that aren't real.
        val min = periodSequences.min().let { observed -> startValue?.let { minOf(it, observed) } ?: observed }
        val max = periodSequences.max()
        val counts = periodSequences.groupingBy { it }.eachCount()
        for (expected in min..max) {
            val count = counts[expected] ?: 0
            when {
                count == 0 -> findings += NumberJournalFinding.Gap(expected)
                count > 1 -> findings += NumberJournalFinding.Duplicate(expected, count)
            }
        }
    }
    findings += reconciliationFindings

    entries.filter { it.event == NumberJournalEvent.PERIOD_SEALED }.forEach { seal ->
        val external = externalSeals[seal.periodKey]
        if (external != null && external != seal.prevHash) {
            findings += NumberJournalFinding.SealMismatch(seal.periodKey, seal.prevHash, external)
        }
    }

    return NumberJournalReport(
        numberRangeId = numberRangeId,
        entryCount = entries.size,
        firstSequence = sequences.minOrNull(),
        lastSequence = sequences.maxOrNull(),
        chainVerification = verifyChain(entries, genesisHash),
        findings = findings,
    )
}
