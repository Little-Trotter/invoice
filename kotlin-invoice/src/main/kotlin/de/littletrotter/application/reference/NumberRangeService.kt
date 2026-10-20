package de.littletrotter.application.reference

import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.application.shared.SystemAuditActorProvider
import de.littletrotter.domain.compliance.NumberJournalChain
import de.littletrotter.domain.compliance.NumberJournalEntry
import de.littletrotter.domain.compliance.NumberJournalEvent
import de.littletrotter.domain.reference.AllocatedNumber
import de.littletrotter.domain.reference.Continuity
import de.littletrotter.domain.reference.CounterScope
import de.littletrotter.domain.reference.CounterSyncResult
import de.littletrotter.domain.reference.NoActiveNumberRangeException
import de.littletrotter.domain.reference.NotSealableException
import de.littletrotter.domain.reference.NumberContext
import de.littletrotter.shared.numbering.NumberFormatter
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.NumberRangeSyncNotSupportedException
import de.littletrotter.domain.reference.NumberRangeSyncUnsupportedConfigurationException
import de.littletrotter.domain.reference.PeriodAlreadySealedException
import de.littletrotter.shared.numbering.ResetCycle
import de.littletrotter.domain.repository.NumberCounterRepository
import de.littletrotter.domain.repository.NumberJournalRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.time.temporal.ChronoUnit

/**
 * Allocates numbers for ranges: `peek` is non-binding (user never types anything),
 * `allocate` is consuming. Different return types signal intent. No active range for a subject
 * means the field stays empty (user must enter manually). Without an active range,
 * peek and allocate throw [NoActiveNumberRangeException].
 */
@ApplicationScoped
class NumberRangeService @Inject constructor(
    private val ranges: NumberRangeRepository,
    private val counters: NumberCounterRepository,
    private val journal: NumberJournalRepository,
    private val time: TimeSource,
    private val auditActorProvider: AuditActorQueryPort = SystemAuditActorProvider(),
) {
    fun hasActiveRangeFor(subject: NumberRangeSubject): Boolean {
        rejectQuote(subject)
        return ranges.findActive(subject) != null
    }

    fun peek(subject: NumberRangeSubject, context: NumberContext): String {
        rejectQuote(subject)
        val range = ranges.findActive(subject) ?: throw NoActiveNumberRangeException(subject)
        val base = range.scopeKey(context)
        val period = range.periodKey(context)
        val next = counters.peekNext(range, base, period)
        return NumberFormatter.compose(range.pattern(context), context.date, next)
    }

    fun allocate(subject: NumberRangeSubject, context: NumberContext): AllocatedNumber {
        rejectQuote(subject)
        val range = ranges.findActive(subject) ?: throw NoActiveNumberRangeException(subject)
        val base = range.scopeKey(context)
        val period = range.periodKey(context)
        val pattern = range.pattern(context)
        val prefix = pattern.prefix

        return if (range.continuity == Continuity.GAPLESS) {
            val sequence = counters.allocateGapless(range, base, period)
            val value = NumberFormatter.compose(pattern, context.date, sequence)
            val allocated = AllocatedNumber(prefix, sequence, value, period)
            // Same transaction as counter increment: caller rollback reverts both counter and journal.
            writeJournal(range, allocated, context)
            allocated
        } else {
            val sequence = counters.allocateUniqueOnly(range, base, period)
            val value = NumberFormatter.compose(pattern, context.date, sequence)
            AllocatedNumber(prefix, sequence, value, period)
        }
    }

    /**
     * Seals a period by appending a PERIOD_SEALED entry. The prevHash (computed by append())
     * is the chain head—the value that must be transmitted outside the system. Caller is
     * responsible for displaying entryHash to the user. Marked @Transactional because
     * append() holds an advisory lock on the chain until transaction end. Without this scope,
     * the lock releases immediately and concurrent allocation during sealing could fork.
     */
    @Transactional
    fun sealPeriod(subject: NumberRangeSubject, periodKey: String): NumberJournalEntry {
        rejectQuote(subject)
        val range = ranges.findActive(subject) ?: throw NoActiveNumberRangeException(subject)
        // UNIQUE_ONLY ranges keep no journal; sealing would claim evidence of an empty set.
        if (range.continuity != Continuity.GAPLESS) {
            throw NotSealableException(range.code, range.continuity)
        }
        val rangeId = range.id ?: error("Nummernkreis ohne id kann nicht versiegelt werden")
        val entries = journal.findAll(rangeId)

        // Sealing twice would create two chain heads for one period—the ambiguity sealing should
        // eliminate. A period that is already sealed stays sealed; exception carries the original
        // head so UI can display it again without confusing the user.
        entries.firstOrNull { it.event == NumberJournalEvent.PERIOD_SEALED && it.periodKey == periodKey }
            ?.let { throw PeriodAlreadySealedException(periodKey, it.entryHash, it.prevHash) }

        val periodAllocations = entries
            .filter { it.event == NumberJournalEvent.ALLOCATED && it.periodKey == periodKey }
            .sortedBy { it.numberSequence }

        val sealEntry = NumberJournalEntry(
            numberRangeId = rangeId,
            event = NumberJournalEvent.PERIOD_SEALED,
            periodKey = periodKey,
            actor = auditActorProvider.currentActor(),
            // Truncate to microseconds: the clock may provide nanosecond precision,
            // but number_journal.occurred_at stores only microseconds. If hash is computed from
            // the full-precision timestamp but the stored value is truncated, verification fails
            // with "hash mismatch" even though no manipulation occurred. Truncate here to ensure
            // hash is computed from the exact value that will be stored and later read.
            occurredAt = time.nowUtc().truncatedTo(ChronoUnit.MICROS),
            prevHash = "", // von append() ermittelt
            entryHash = "", // von append() berechnet
            sealedStartValue = range.startValue,
            sealedCount = periodAllocations.size.toLong(),
            sealedFirstNumber = periodAllocations.firstOrNull()?.number,
            sealedLastNumber = periodAllocations.lastOrNull()?.number,
        )
        return journal.append(sealEntry, NumberJournalChain.genesisHash(range.code, range.startValue))
    }

    /**
     * "Synchronize number counters to existing data" admin maintenance action (L4 follow-up,
     * 2026-09-02+): raises [subject]'s active range counter up to [maxObservedSuffix] — the
     * highest numeric suffix [de.littletrotter.application.masterdata.MasterDataNumberService]
     * found among the persisted customer/article/seller numbers — instead of relying on
     * [de.littletrotter.application.masterdata.MasterDataNumberService]'s per-save skip-to-free
     * retry (L4) to walk past every stale number one at a time on the next save (bounded at 100
     * attempts there; a large bulk import can leave far more).
     *
     * Refuses GAPLESS ranges ([NumberRangeSyncNotSupportedException]): invoice numbers allocate
     * in the same transaction as the document, so they cannot drift the way UNIQUE_ONLY rows can.
     * Only GLOBAL/NEVER ranges are supported ([NumberRangeSyncUnsupportedConfigurationException]):
     * a persisted number string alone carries no seller or allocation-year bookkeeping to
     * reconstruct a PER_SELLER or YEARLY scope/period key from. Never lowers the counter — a
     * [maxObservedSuffix] at or below the current value reports `changed = false` ("bereits
     * synchron").
     */
    @Transactional
    fun syncCounterTo(subject: NumberRangeSubject, maxObservedSuffix: Long?): CounterSyncResult {
        rejectQuote(subject)
        val range = ranges.findActive(subject) ?: throw NoActiveNumberRangeException(subject)
        if (range.continuity == Continuity.GAPLESS) throw NumberRangeSyncNotSupportedException(subject)
        if (range.scope != CounterScope.GLOBAL || range.resetCycle != ResetCycle.NEVER) {
            throw NumberRangeSyncUnsupportedConfigurationException(range.code)
        }

        val before = counters.currentValue(subject)
        if (maxObservedSuffix == null || (before != null && maxObservedSuffix <= before)) {
            return CounterSyncResult(subject, before, before, changed = false)
        }

        // GLOBAL -> "*", NEVER -> "" (see NumberRange.scopeKey/periodKey) — the only
        // scope/period combination validated above, so there is exactly one counter row to raise.
        val after = counters.raiseToAtLeast(range, GLOBAL_SCOPE_KEY, NEVER_PERIOD_KEY, maxObservedSuffix)
        return CounterSyncResult(subject, before, after, changed = true)
    }

    private fun rejectQuote(subject: NumberRangeSubject) {
        require(subject != NumberRangeSubject.QUOTE) { "Nummernkreis-Subjekt QUOTE: der Zähler liegt im Angebotsmodul" }
    }

    private fun writeJournal(
        range: NumberRange,
        allocated: AllocatedNumber,
        context: NumberContext,
    ) {
        val rangeId = range.id ?: return
        val entry = NumberJournalEntry(
            numberRangeId = rangeId,
            event = NumberJournalEvent.ALLOCATED,
            periodKey = allocated.periodKey,
            numberPrefix = allocated.prefix,
            numberSequence = allocated.sequence,
            number = allocated.value,
            entityType = context.entityType,
            entityId = context.entityId,
            actor = auditActorProvider.currentActor(),
            // Truncate to microseconds (see sealPeriod() for rationale).
            occurredAt = time.nowUtc().truncatedTo(ChronoUnit.MICROS),
            prevHash = "", // von append() ermittelt
            entryHash = "", // von append() berechnet
        )
        journal.append(entry, NumberJournalChain.genesisHash(range.code, range.startValue))
    }

    private companion object {
        /** [syncCounterTo] validates `scope == GLOBAL && resetCycle == NEVER` before ever using
         *  these — see [NumberRange.scopeKey]/[NumberRange.periodKey] for the general
         *  mapping this mirrors. */
        const val GLOBAL_SCOPE_KEY = "*"
        const val NEVER_PERIOD_KEY = ""
    }
}
